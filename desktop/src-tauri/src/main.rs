#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

use std::path::PathBuf;
use std::sync::atomic::{AtomicBool, Ordering};
use std::thread;
use std::time::Duration;
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

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![web_ready, web_check_update, web_restart])
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
        .run(|_app, _event| {});
}
