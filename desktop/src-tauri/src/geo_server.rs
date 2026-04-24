//! Local HTTP server for synchronous geometry operations.
//! Runs on a background thread, listens on localhost:12321.
//! The frontend calls it via synchronous XMLHttpRequest.

use crate::sdf_ops::{self, MeshData};
use std::thread;
use tiny_http::{Header, Method, Response, Server};

const PORT: u16 = 12321;

/// Return the user's home directory.
fn handle_home_dir() -> Result<String, String> {
    std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .map(|p| format!("{{\"path\":\"{}\"}}", p.replace('\\', "\\\\").replace('"', "\\\"")))
        .map_err(|_| "cannot determine home directory".to_string())
}

/// Open a native save dialog, return the chosen path (no data written yet).
fn handle_pick_save_path(request: &mut tiny_http::Request) -> Result<String, String> {
    let mut body = String::new();
    request
        .as_reader()
        .read_to_string(&mut body)
        .map_err(|e| format!("read error: {}", e))?;

    #[derive(serde::Deserialize)]
    struct Req {
        suggested_name: String,
    }
    let req: Req =
        serde_json::from_str(&body).map_err(|e| format!("JSON parse error: {}", e))?;

    let dialog = rfd::FileDialog::new()
        .set_title("Export")
        .set_file_name(&req.suggested_name)
        .add_filter("STL files", &["stl"])
        .add_filter("3MF files", &["3mf"]);

    match dialog.save_file() {
        Some(path) => Ok(format!(
            "{{\"path\":\"{}\"}}",
            path.to_string_lossy().replace('\\', "\\\\").replace('"', "\\\"")
        )),
        None => Ok("null".to_string()),
    }
}

/// Read a file from disk. Path comes from X-File-Path header.
fn handle_read_file(request: &mut tiny_http::Request) -> Result<Vec<u8>, String> {
    let path = request
        .headers()
        .iter()
        .find(|h| h.field.as_str() == "X-File-Path")
        .map(|h| h.value.as_str().to_string())
        .ok_or_else(|| "missing X-File-Path header".to_string())?;

    // Drain body (unused)
    let mut _buf = Vec::new();
    let _ = request.as_reader().read_to_end(&mut _buf);

    std::fs::read(&path).map_err(|e| format!("read error: {}", e))
}

/// List files in a directory. Path comes from JSON body {"path": "..."}.
fn handle_read_dir(request: &mut tiny_http::Request) -> Result<String, String> {
    let mut body = String::new();
    request
        .as_reader()
        .read_to_string(&mut body)
        .map_err(|e| format!("read error: {}", e))?;

    #[derive(serde::Deserialize)]
    struct Req {
        path: String,
    }
    let req: Req =
        serde_json::from_str(&body).map_err(|e| format!("JSON parse error: {}", e))?;

    // Create directory if it doesn't exist
    std::fs::create_dir_all(&req.path)
        .map_err(|e| format!("mkdir error: {}", e))?;

    let entries: Vec<serde_json::Value> = std::fs::read_dir(&req.path)
        .map_err(|e| format!("readdir error: {}", e))?
        .filter_map(|entry| {
            let entry = entry.ok()?;
            let meta = entry.metadata().ok()?;
            Some(serde_json::json!({
                "name": entry.file_name().to_string_lossy().to_string(),
                "is_dir": meta.is_dir(),
                "size": meta.len(),
            }))
        })
        .collect();

    serde_json::to_string(&entries).map_err(|e| format!("serialize error: {}", e))
}

/// Delete a file. Path comes from X-File-Path header.
fn handle_delete_file(request: &mut tiny_http::Request) -> Result<String, String> {
    let path = request
        .headers()
        .iter()
        .find(|h| h.field.as_str() == "X-File-Path")
        .map(|h| h.value.as_str().to_string())
        .ok_or_else(|| "missing X-File-Path header".to_string())?;

    // Drain body
    let mut _buf = Vec::new();
    let _ = request.as_reader().read_to_end(&mut _buf);

    std::fs::remove_file(&path).map_err(|e| format!("delete error: {}", e))?;
    Ok("{\"deleted\":true}".to_string())
}

/// Write raw bytes to a given path (from X-File-Path header).
fn handle_write_file(request: &mut tiny_http::Request) -> Result<String, String> {
    let path = request
        .headers()
        .iter()
        .find(|h| h.field.as_str() == "X-File-Path")
        .map(|h| h.value.as_str().to_string())
        .ok_or_else(|| "missing X-File-Path header".to_string())?;

    let mut bytes = Vec::new();
    request
        .as_reader()
        .read_to_end(&mut bytes)
        .map_err(|e| format!("read error: {}", e))?;

    // Create parent directories if they don't exist
    if let Some(parent) = std::path::Path::new(&path).parent() {
        std::fs::create_dir_all(parent)
            .map_err(|e| format!("mkdir error: {}", e))?;
    }

    std::fs::write(&path, &bytes).map_err(|e| format!("write error: {}", e))?;
    Ok(format!("{{\"written\":{}}}", bytes.len()))
}

pub fn start() {
    thread::spawn(|| {
        let server =
            Server::http(format!("127.0.0.1:{}", PORT)).expect("Failed to start geo server");
        eprintln!("geo-server: listening on http://127.0.0.1:{}", PORT);

        let cors = Header::from_bytes("Access-Control-Allow-Origin", "*").unwrap();
        let cors_headers =
            Header::from_bytes("Access-Control-Allow-Headers", "Content-Type, X-File-Path").unwrap();
        let content_type = Header::from_bytes("Content-Type", "application/json").unwrap();

        for mut request in server.incoming_requests() {
            // Handle CORS preflight
            if *request.method() == Method::Options {
                let response = Response::empty(200)
                    .with_header(cors.clone())
                    .with_header(cors_headers.clone());
                let _ = request.respond(response);
                continue;
            }

            let path = request.url().to_string();

            // /pick-save-path — open native dialog, return chosen path (JSON body)
            if path == "/pick-save-path" {
                let (status, json) = match handle_pick_save_path(&mut request) {
                    Ok(json) => (200, json),
                    Err(e) => (500, format!("{{\"error\":\"{}\"}}", e)),
                };
                let resp = Response::from_string(json)
                    .with_status_code(status)
                    .with_header(cors.clone())
                    .with_header(content_type.clone());
                let _ = request.respond(resp);
                continue;
            }

            // /home-dir — return user's home directory
            if path == "/home-dir" {
                // Drain body
                let mut _buf = Vec::new();
                let _ = request.as_reader().read_to_end(&mut _buf);
                let (status, json) = match handle_home_dir() {
                    Ok(json) => (200, json),
                    Err(e) => (500, format!("{{\"error\":\"{}\"}}", e)),
                };
                let resp = Response::from_string(json)
                    .with_status_code(status)
                    .with_header(cors.clone())
                    .with_header(content_type.clone());
                let _ = request.respond(resp);
                continue;
            }

            // /read-file — read file from disk, return raw bytes
            if path == "/read-file" {
                match handle_read_file(&mut request) {
                    Ok(bytes) => {
                        let ct = Header::from_bytes("Content-Type", "application/octet-stream").unwrap();
                        let resp = Response::from_data(bytes)
                            .with_status_code(200)
                            .with_header(cors.clone())
                            .with_header(ct);
                        let _ = request.respond(resp);
                    }
                    Err(e) => {
                        let resp = Response::from_string(format!("{{\"error\":\"{}\"}}", e))
                            .with_status_code(500)
                            .with_header(cors.clone())
                            .with_header(content_type.clone());
                        let _ = request.respond(resp);
                    }
                }
                continue;
            }

            // /read-dir — list files in a directory
            if path == "/read-dir" {
                let (status, json) = match handle_read_dir(&mut request) {
                    Ok(json) => (200, json),
                    Err(e) => (500, format!("{{\"error\":\"{}\"}}", e)),
                };
                let resp = Response::from_string(json)
                    .with_status_code(status)
                    .with_header(cors.clone())
                    .with_header(content_type.clone());
                let _ = request.respond(resp);
                continue;
            }

            // /delete-file — delete a file from disk
            if path == "/delete-file" {
                let (status, json) = match handle_delete_file(&mut request) {
                    Ok(json) => (200, json),
                    Err(e) => (500, format!("{{\"error\":\"{}\"}}", e)),
                };
                let resp = Response::from_string(json)
                    .with_status_code(status)
                    .with_header(cors.clone())
                    .with_header(content_type.clone());
                let _ = request.respond(resp);
                continue;
            }

            // /write-file — write raw binary body to path from header
            if path == "/write-file" {
                let (status, json) = match handle_write_file(&mut request) {
                    Ok(json) => (200, json),
                    Err(e) => (500, format!("{{\"error\":\"{}\"}}", e)),
                };
                let resp = Response::from_string(json)
                    .with_status_code(status)
                    .with_header(cors.clone())
                    .with_header(content_type.clone());
                let _ = request.respond(resp);
                continue;
            }

            // Read body as UTF-8 string for JSON endpoints
            let mut body = String::new();
            if let Err(e) = request.as_reader().read_to_string(&mut body) {
                let resp = Response::from_string(format!("{{\"error\":\"{}\"}}", e))
                    .with_status_code(400)
                    .with_header(cors.clone())
                    .with_header(content_type.clone());
                let _ = request.respond(resp);
                continue;
            }

            let result: Result<MeshData, String> = match path.as_str() {
                "/sdf-mesh" => {
                    serde_json::from_str::<sdf_ops::SdfMeshRequest>(&body)
                        .map_err(|e| format!("JSON parse error: {}", e))
                        .and_then(|req| sdf_ops::sdf_to_mesh(&req))
                }
                "/sdf-mesh-bin" => {
                    // Binary SDF endpoint: returns raw float32/uint32 arrays
                    // Format: [nv:u32 LE][nf:u32 LE][verts: nv*3 f32 LE][faces: nf*3 u32 LE]
                    match serde_json::from_str::<sdf_ops::SdfMeshRequest>(&body)
                        .map_err(|e| format!("JSON parse error: {}", e))
                        .and_then(|req| sdf_ops::sdf_to_mesh(&req))
                    {
                        Ok(mesh) => {
                            let nv = mesh.vertices.len() as u32;
                            let nf = mesh.faces.len() as u32;
                            let mut buf = Vec::with_capacity(8 + (nv as usize) * 12 + (nf as usize) * 12);
                            buf.extend_from_slice(&nv.to_le_bytes());
                            buf.extend_from_slice(&nf.to_le_bytes());
                            for v in &mesh.vertices {
                                buf.extend_from_slice(&(v[0] as f32).to_le_bytes());
                                buf.extend_from_slice(&(v[1] as f32).to_le_bytes());
                                buf.extend_from_slice(&(v[2] as f32).to_le_bytes());
                            }
                            for f in &mesh.faces {
                                buf.extend_from_slice(&f[0].to_le_bytes());
                                buf.extend_from_slice(&f[1].to_le_bytes());
                                buf.extend_from_slice(&f[2].to_le_bytes());
                            }
                            let bin_ct = Header::from_bytes("Content-Type", "application/octet-stream").unwrap();
                            let resp = Response::from_data(buf)
                                .with_status_code(200)
                                .with_header(cors.clone())
                                .with_header(bin_ct);
                            let _ = request.respond(resp);
                            continue;
                        }
                        Err(e) => {
                            let resp = Response::from_string(format!("{{\"error\":\"{}\"}}", e))
                                .with_status_code(500)
                                .with_header(cors.clone())
                                .with_header(content_type.clone());
                            let _ = request.respond(resp);
                            continue;
                        }
                    }
                }
                "/ping" => Ok(MeshData {
                    vertices: vec![],
                    faces: vec![],
                }),
                _ => Err(format!("Unknown endpoint: {}", path)),
            };

            let (status, json) = match result {
                Ok(mesh) => (200, serde_json::to_string(&mesh).unwrap()),
                Err(e) => (500, format!("{{\"error\":\"{}\"}}", e)),
            };

            let resp = Response::from_string(json)
                .with_status_code(status)
                .with_header(cors.clone())
                .with_header(content_type.clone());
            let _ = request.respond(resp);
        }
    });
}
