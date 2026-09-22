#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

// Lokalne AI na komputerze: silnik llama.cpp (llama-server) i model Qwen 2.5 1.5B sa wbudowane
// w apke. Silnik startuje w tle dopiero przy pierwszym uzyciu AI i dziala tylko na tym komputerze
// (adres 127.0.0.1) - bez internetu, nic nie wychodzi z komputera.

use serde_json::{json, Value};
use std::net::TcpListener;
use std::process::{Child, Command, Stdio};
use std::sync::Mutex;
use std::thread;
use std::time::{Duration, Instant};

const MODEL_FILE: &str = "models/qwen2.5-1.5b-instruct-q4_k_m.gguf";
#[cfg(windows)]
const SERVER_FILE: &str = "llama/llama-server.exe";
#[cfg(not(windows))]
const SERVER_FILE: &str = "llama/llama-server";

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
    let server = app.path_resolver().resolve_resource(SERVER_FILE);
    let model = app.path_resolver().resolve_resource(MODEL_FILE);
    let (server, model) = match (server, model) {
        (Some(s), Some(m)) if s.exists() && m.exists() => (s, m),
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
        .invoke_handler(tauri::generate_handler![ai_status, ai_init, ai_chat])
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
