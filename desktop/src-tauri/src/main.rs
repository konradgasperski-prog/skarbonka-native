#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

// Lokalne AI na komputerze: silnik llama.cpp (llama-server) i model Qwen 2.5 1.5B sa wbudowane
// w apke. Silnik startuje w tle dopiero przy pierwszym uzyciu AI i dziala tylko na tym komputerze
// (adres 127.0.0.1) - bez internetu, nic nie wychodzi z komputera.

use serde_json::{json, Value};
use std::net::TcpListener;
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};
use tauri::Manager;

// ---------------------------------------------------------------------------------------------
// Automatyczne aktualizacje: apka sama pobiera nowszy index.html z GitHuba (desktop/dist/index.html).
// Pobrana wersja jest podawana stronie przy starcie; strona sama sprawdza, czy jest nowsza i ja wlacza.
// Jesli nowa wersja sie nie uruchomi (brak sygnalu "web_ready" w 15 s), apka ja usuwa i startuje od nowa.
// ---------------------------------------------------------------------------------------------
const UPDATE_URL: &str =
    "https://raw.githubusercontent.com/konradgasperski-prog/skarbonka-native/main/desktop/dist/index.html";
static WEB_READY: AtomicBool = AtomicBool::new(false);

fn cache_file(app: &tauri::AppHandle) -> Option<PathBuf> {
    app.path_resolver().app_data_dir().map(|d| d.join("web").join("index.html"))
}

fn html_version(html: &str) -> u64 {
    let head = &html[..html.len().min(4000)];
    let key = "name=\"stash-version\" content=\"";
    head.find(key)
        .and_then(|i| {
            let rest = &head[i + key.len()..];
            rest.find('"').map(|j| rest[..j].to_string())
        })
        .and_then(|v| v.parse().ok())
        .unwrap_or(0)
}

#[tauri::command]
fn web_ready() {
    WEB_READY.store(true, Ordering::SeqCst);
}

/// Sprawdza GitHuba; zwraca numer nowej wersji, jesli zostala pobrana (inaczej 0).
#[tauri::command]
async fn web_check_update(app: tauri::AppHandle, current: String) -> u64 {
    let current: u64 = current.parse().unwrap_or(0);
    tauri::async_runtime::spawn_blocking(move || -> u64 {
        let html = match ureq::get(UPDATE_URL)
            .set("Cache-Control", "no-cache")
            .timeout(Duration::from_secs(20))
            .call()
        {
            Ok(r) => r.into_string().unwrap_or_default(),
            Err(_) => return 0,
        };
        let ver = html_version(&html);
        // tylko prawdziwa strona apki i tylko nowsza wersja
        if ver <= current || html.len() < 50_000 || !html.contains("</html>") {
            return 0;
        }
        if let Some(path) = cache_file(&app) {
            if let Some(dir) = path.parent() {
                let _ = std::fs::create_dir_all(dir);
            }
            let tmp = path.with_extension("part");
            if std::fs::write(&tmp, &html).is_ok() && std::fs::rename(&tmp, &path).is_ok() {
                return ver;
            }
        }
        0
    })
    .await
    .unwrap_or(0)
}

#[tauri::command]
fn web_restart(app: tauri::AppHandle) {
    app.restart();
}

const MODEL_FILE: &str = "models/qwen2.5-1.5b-instruct-q4_k_m.gguf";
// Silnik: "llama-server" (starsze wydania llama.cpp) albo wspolny program "llama" + komenda "serve" (nowsze)
#[cfg(windows)]
const SERVER_FILES: [(&str, bool); 2] = [("llama/llama-server.exe", false), ("llama/llama.exe", true)];
#[cfg(not(windows))]
const SERVER_FILES: [(&str, bool); 2] = [("llama/llama-server", false), ("llama/llama", true)];

struct Ai {
    child: Option<Child>,
    port: u16,
    state: String, // "" (none) | loading | ready | error | missing
    error: String,
}

static AI: Mutex<Ai> = Mutex::new(Ai { child: None, port: 0, state: String::new(), error: String::new() });

fn status_json(ai: &Ai) -> Value {
    json!({ "state": if ai.state.is_empty() { "none" } else { ai.state.as_str() }, "error": ai.error })
}

fn free_port() -> u16 {
    TcpListener::bind("127.0.0.1:0")
        .ok()
        .and_then(|l| l.local_addr().ok())
        .map(|a| a.port())
        .unwrap_or(38917)
}

#[tauri::command]
fn ai_status() -> Value {
    let ai = AI.lock().unwrap();
    status_json(&ai)
}

#[tauri::command]
fn ai_init(app: tauri::AppHandle) -> Value {
    let mut ai = AI.lock().unwrap();
    if ai.state == "ready" || ai.state == "loading" {
        return status_json(&ai);
    }
    let found = SERVER_FILES.iter().find_map(|(f, serve)| {
        app.path_resolver().resolve_resource(f).filter(|p| p.exists()).map(|p| (p, *serve))
    });
    let model = app.path_resolver().resolve_resource(MODEL_FILE).filter(|p| p.exists());
    let ((server, use_serve), model) = match (found, model) {
        (Some(s), Some(m)) => (s, m),
        _ => {
            ai.state = "missing".into();
            return status_json(&ai);
        }
    };

    #[cfg(unix)]
    {
        use std::os::unix::fs::PermissionsExt;
        let _ = std::fs::set_permissions(&server, std::fs::Permissions::from_mode(0o755));
    }

    let port = free_port();
    let mut cmd = Command::new(&server);
    if use_serve {
        cmd.arg("serve");
    }
    cmd.arg("-m").arg(&model)
        .args(["--host", "127.0.0.1", "--port", &port.to_string(), "-c", "4096", "-ngl", "99"])
        .stdin(Stdio::null())
        .stdout(Stdio::null())
        .stderr(Stdio::null());
    #[cfg(windows)]
    {
        use std::os::windows::process::CommandExt;
        cmd.creation_flags(0x0800_0000); // bez czarnego okna konsoli
    }

    match cmd.spawn() {
        Ok(child) => {
            ai.child = Some(child);
            ai.port = port;
            ai.state = "loading".into();
            ai.error.clear();
            // czekamy w tle, az silnik wczyta model
            thread::spawn(move || {
                let start = Instant::now();
                let url = format!("http://127.0.0.1:{}/health", port);
                loop {
                    if ureq::get(&url).timeout(Duration::from_secs(2)).call().is_ok() {
                        AI.lock().unwrap().state = "ready".into();
                        return;
                    }
                    {
                        let mut ai = AI.lock().unwrap();
                        let exited = ai.child.as_mut().map(|c| matches!(c.try_wait(), Ok(Some(_)))).unwrap_or(true);
                        if exited {
                            ai.state = "error".into();
                            ai.error = "Silnik AI zakończył działanie".into();
                            ai.child = None;
                            return;
                        }
                        if start.elapsed() > Duration::from_secs(240) {
                            ai.state = "error".into();
                            ai.error = "Silnik AI nie uruchomił się w 4 minuty".into();
                            return;
                        }
                    }
                    thread::sleep(Duration::from_millis(500));
                }
            });
        }
        Err(e) => {
            ai.state = "error".into();
            ai.error = e.to_string();
        }
    }
    status_json(&ai)
}

#[tauri::command]
async fn ai_chat(messages: Value, temperature: Option<f64>) -> Result<String, String> {
    let port = {
        let ai = AI.lock().unwrap();
        if ai.state != "ready" {
            return Err("AI nie jest gotowe".into());
        }
        ai.port
    };
    tauri::async_runtime::spawn_blocking(move || -> Result<String, String> {
        let body = json!({
            "messages": messages,
            "temperature": temperature.unwrap_or(0.7),
            "max_tokens": 1500,
            "stream": false
        });
        let resp: Value = ureq::post(&format!("http://127.0.0.1:{}/v1/chat/completions", port))
            .timeout(Duration::from_secs(300))
            .send_json(body)
            .map_err(|e| e.to_string())?
            .into_json()
            .map_err(|e| e.to_string())?;
        Ok(resp["choices"][0]["message"]["content"].as_str().unwrap_or("").to_string())
    })
    .await
    .map_err(|e| e.to_string())?
}

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![ai_status, ai_init, ai_chat, web_ready, web_check_update, web_restart])
        .setup(|app| {
            let handle = app.handle();
            // pobrana (nowsza) wersja strony trafia do okna jeszcze przed jego zaladowaniem
            let cached = cache_file(&handle).and_then(|p| std::fs::read_to_string(p).ok());
            let init = match &cached {
                Some(html) => format!(
                    "window.__STASH_UPDATE_HTML = {};",
                    serde_json::to_string(html).unwrap_or_else(|_| "null".into())
                ),
                None => String::new(),
            };
            tauri::WindowBuilder::new(app, "main", tauri::WindowUrl::App("index.html".into()))
                .title("Skarbonka")
                .inner_size(480.0, 900.0)
                .min_inner_size(380.0, 600.0)
                .resizable(true)
                .initialization_script(&init)
                .build()?;
            // zabezpieczenie: jesli pobrana wersja sie nie uruchomi - usun ja i wystartuj od nowa
            if cached.is_some() {
                let h = handle.clone();
                thread::spawn(move || {
                    thread::sleep(Duration::from_secs(15));
                    if !WEB_READY.load(Ordering::SeqCst) {
                        if let Some(p) = cache_file(&h) {
                            let _ = std::fs::remove_file(p);
                        }
                        h.restart();
                    }
                });
            }
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while running Skarbonka")
        .run(|_app, event| {
            // zamykamy silnik AI razem z apka
            if let tauri::RunEvent::Exit = event {
                if let Some(mut c) = AI.lock().unwrap().child.take() {
                    let _ = c.kill();
                }
            }
        });
}
