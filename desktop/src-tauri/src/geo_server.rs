//! Local HTTP server for synchronous geometry operations.
//! Runs on a background thread, listens on localhost:12321.
//! The frontend calls it via synchronous XMLHttpRequest.

use crate::manifold_ops::{self, MeshData};
use crate::sdf_ops;
use std::thread;
use tiny_http::{Header, Method, Response, Server};

const PORT: u16 = 12321;

/// Request body for difference (base + cutters).
#[derive(serde::Deserialize)]
struct DifferenceRequest {
    base: MeshData,
    cutters: Vec<MeshData>,
}

/// Request body for smooth (mesh + sharp angle threshold + refine count).
#[derive(serde::Deserialize)]
struct SmoothRequest {
    mesh: MeshData,
    #[serde(default = "default_min_sharp_angle")]
    min_sharp_angle: f64,
    #[serde(default)]
    min_smoothness: f64,
    #[serde(default = "default_refine")]
    refine: i32,
}

fn default_min_sharp_angle() -> f64 {
    100.0
}
fn default_refine() -> i32 {
    3
}

/// Request body for refine (mesh + n).
#[derive(serde::Deserialize)]
struct RefineRequest {
    mesh: MeshData,
    n: i32,
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
                "/union" => {
                    serde_json::from_str::<Vec<MeshData>>(&body)
                        .map_err(|e| format!("JSON parse error: {}", e))
                        .and_then(|meshes| manifold_ops::union(&meshes))
                }
                "/difference" => {
                    serde_json::from_str::<DifferenceRequest>(&body)
                        .map_err(|e| format!("JSON parse error: {}", e))
                        .and_then(|req| manifold_ops::difference(&req.base, &req.cutters))
                }
                "/intersection" => {
                    serde_json::from_str::<Vec<MeshData>>(&body)
                        .map_err(|e| format!("JSON parse error: {}", e))
                        .and_then(|meshes| manifold_ops::intersection(&meshes))
                }
                "/hull" => {
                    serde_json::from_str::<Vec<MeshData>>(&body)
                        .map_err(|e| format!("JSON parse error: {}", e))
                        .and_then(|meshes| manifold_ops::hull(&meshes))
                }
                "/smooth" => {
                    serde_json::from_str::<SmoothRequest>(&body)
                        .map_err(|e| format!("JSON parse error: {}", e))
                        .and_then(|req| {
                            manifold_ops::smooth(
                                &req.mesh,
                                req.min_sharp_angle,
                                req.min_smoothness,
                                req.refine,
                            )
                        })
                }
                "/refine" => {
                    serde_json::from_str::<RefineRequest>(&body)
                        .map_err(|e| format!("JSON parse error: {}", e))
                        .and_then(|req| manifold_ops::refine(&req.mesh, req.n))
                }
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
