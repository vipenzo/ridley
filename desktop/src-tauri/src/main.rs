// Prevents additional console window on Windows in release
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod geo_server;
mod sdf_ops;

use std::process::{Child, Command};
use std::sync::Mutex;

use tauri::{WebviewUrl, WebviewWindowBuilder};

const INIT_SCRIPT: &str = r#"window.RIDLEY_ENV = "desktop";"#;

/// Project root, computed at compile time from CARGO_MANIFEST_DIR.
fn project_root() -> std::path::PathBuf {
    // CARGO_MANIFEST_DIR = .../desktop/src-tauri → project root is ../..
    std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"))
        .join("../..")
        .canonicalize()
        .unwrap_or_else(|_| std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../.."))
}

/// Spawn a child process, return handle for cleanup on exit.
fn spawn_child(name: &str, cmd: &str, args: &[&str], dir: &std::path::Path) -> Option<Child> {
    if !dir.exists() {
        eprintln!("{}: directory {:?} not found, skipping", name, dir);
        return None;
    }
    eprintln!("{}: starting in {:?}", name, dir);
    match Command::new(cmd)
        .args(args)
        .current_dir(dir)
        .spawn()
    {
        Ok(child) => {
            eprintln!("{}: started (pid {})", name, child.id());
            Some(child)
        }
        Err(e) => {
            eprintln!("{}: failed to start: {}", name, e);
            None
        }
    }
}

/// Wait for a TCP port to accept connections (up to timeout).
fn wait_for_port(port: u16, timeout: std::time::Duration) -> bool {
    let start = std::time::Instant::now();
    while start.elapsed() < timeout {
        if std::net::TcpStream::connect(format!("127.0.0.1:{}", port)).is_ok() {
            return true;
        }
        std::thread::sleep(std::time::Duration::from_millis(500));
    }
    false
}

fn main() {
    let root = project_root();

    // Start local HTTP server for synchronous geometry ops
    geo_server::start();

    // In dev mode, spawn frontend dev server and wait for it
    #[cfg(debug_assertions)]
    let frontend_child: Mutex<Option<Child>> = {
        let child = spawn_child("frontend", "npm", &["run", "dev"], &root);
        if child.is_some() {
            eprintln!("frontend: waiting for :9000...");
            if wait_for_port(9000, std::time::Duration::from_secs(30)) {
                eprintln!("frontend: ready on :9000");
            } else {
                eprintln!("frontend: timeout waiting for :9000");
            }
        }
        Mutex::new(child)
    };

    tauri::Builder::default()
        .setup(|app| {
            WebviewWindowBuilder::new(app, "main", WebviewUrl::default())
                .title("Ridley")
                .inner_size(1280.0, 800.0)
                .resizable(true)
                .fullscreen(false)
                .initialization_script(INIT_SCRIPT)
                .build()?;
            Ok(())
        })
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(move |_app, event| {
            if let tauri::RunEvent::Exit = event {
                // Kill child processes on app exit
                #[cfg(debug_assertions)]
                if let Ok(mut guard) = frontend_child.lock() {
                    if let Some(ref mut child) = *guard {
                        eprintln!("frontend: killing pid {}", child.id());
                        let _ = child.kill();
                    }
                }
            }
        });
}
