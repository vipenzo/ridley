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

pub fn start() {
    thread::spawn(|| {
        let server =
            Server::http(format!("127.0.0.1:{}", PORT)).expect("Failed to start geo server");
        eprintln!("geo-server: listening on http://127.0.0.1:{}", PORT);

        let cors = Header::from_bytes("Access-Control-Allow-Origin", "*").unwrap();
        let cors_headers =
            Header::from_bytes("Access-Control-Allow-Headers", "Content-Type").unwrap();
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

            // Read body
            let mut body = String::new();
            if let Err(e) = request.as_reader().read_to_string(&mut body) {
                let resp = Response::from_string(format!("{{\"error\":\"{}\"}}", e))
                    .with_status_code(400)
                    .with_header(cors.clone())
                    .with_header(content_type.clone());
                let _ = request.respond(resp);
                continue;
            }

            let path = request.url().to_string();
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
