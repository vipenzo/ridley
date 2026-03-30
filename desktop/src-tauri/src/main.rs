// Prevents additional console window on Windows in release
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod geo_server;
mod manifold_ops;
mod sdf_ops;

use manifold_ops::MeshData;
use std::process::{Child, Command};
use std::sync::Mutex;

#[tauri::command]
fn ping() -> String {
    "pong from Rust backend".to_string()
}

#[tauri::command]
fn manifold_union(meshes: Vec<MeshData>) -> Result<MeshData, String> {
    manifold_ops::union(&meshes)
}

#[tauri::command]
fn manifold_difference(base: MeshData, cutters: Vec<MeshData>) -> Result<MeshData, String> {
    manifold_ops::difference(&base, &cutters)
}

#[tauri::command]
fn manifold_intersection(meshes: Vec<MeshData>) -> Result<MeshData, String> {
    manifold_ops::intersection(&meshes)
}

#[tauri::command]
fn manifold_hull(meshes: Vec<MeshData>) -> Result<MeshData, String> {
    manifold_ops::hull(&meshes)
}

/// Spawn the JVM sidecar (ridley-jvm) if `clj` is on PATH.
/// Returns the child process handle so we can kill it on exit.
fn spawn_jvm_sidecar() -> Option<Child> {
    // Find the ridley-jvm directory relative to the Tauri src dir
    let jvm_dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR")).join("../ridley-jvm");
    if !jvm_dir.join("deps.edn").exists() {
        eprintln!("jvm-sidecar: ridley-jvm dir not found at {:?}, skipping", jvm_dir);
        return None;
    }

    eprintln!("jvm-sidecar: starting from {:?}", jvm_dir);
    match Command::new("clj")
        .args(["-M", "-m", "ridley.jvm.server"])
        .current_dir(&jvm_dir)
        .spawn()
    {
        Ok(child) => {
            eprintln!("jvm-sidecar: started (pid {})", child.id());
            Some(child)
        }
        Err(e) => {
            eprintln!("jvm-sidecar: failed to start: {}", e);
            None
        }
    }
}

fn main() {
    // Start local HTTP server for synchronous geometry ops
    geo_server::start();

    // Spawn JVM sidecar
    let jvm_child: Mutex<Option<Child>> = Mutex::new(spawn_jvm_sidecar());

    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            ping,
            manifold_union,
            manifold_difference,
            manifold_intersection,
            manifold_hull,
        ])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(move |_app, event| {
            if let tauri::RunEvent::Exit = event {
                // Kill JVM sidecar on app exit
                if let Ok(mut guard) = jvm_child.lock() {
                    if let Some(ref mut child) = *guard {
                        eprintln!("jvm-sidecar: killing pid {}", child.id());
                        let _ = child.kill();
                    }
                }
            }
        });
}
