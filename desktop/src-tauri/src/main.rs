// Prevents additional console window on Windows in release
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod manifold_ops;

use manifold_ops::MeshData;

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

fn main() {
    tauri::Builder::default()
        .invoke_handler(tauri::generate_handler![
            ping,
            manifold_union,
            manifold_difference,
            manifold_intersection,
            manifold_hull,
        ])
        .run(tauri::generate_context!())
        .expect("error while running tauri application");
}
