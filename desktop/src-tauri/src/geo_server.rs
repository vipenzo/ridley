//! Local HTTP server for synchronous geometry operations.
//! Runs on a background thread, listens on localhost:12321.
//! The frontend calls it via synchronous XMLHttpRequest.

use crate::manifold_ops::{self, MeshData};
use std::thread;
use tiny_http::{Header, Method, Response, Server};

const PORT: u16 = 12321;

/// Request body for difference (base + cutters).
#[derive(serde::Deserialize)]
struct DifferenceRequest {
    base: MeshData,
    cutters: Vec<MeshData>,
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
