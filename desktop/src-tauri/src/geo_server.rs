//! Local HTTP server for synchronous geometry operations.
//! Runs on a background thread, listens on localhost:12321.
//! The frontend calls it via XMLHttpRequest (synchronous for mesh ops,
//! async for file I/O).

use crate::sdf_ops::{self, MeshData};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Mutex, OnceLock};
use std::thread;
use tiny_http::{Header, Method, Response, Server};

const PORT: u16 = 12321;

/// The app's main window, so a native file panel can be attached to it.
///
/// A panel built with no parent is a window of its own: on macOS it can end up
/// BEHIND the app, and bringing the app forward does not bring it with them —
/// which leaves the user looking at an app that ignores every click, because the
/// thing waiting for them is somewhere they cannot see (reported 2026-08-12:
/// "clicco Open e non succede niente"). Attached to the window it becomes a
/// SHEET: physically part of the window, so it cannot be behind it and it always
/// comes forward with it.
static MAIN_WINDOW: OnceLock<Mutex<Option<tauri::WebviewWindow>>> = OnceLock::new();

/// Called from the Tauri setup once the window exists (the server starts before
/// it, so this cannot be an argument to `start`).
pub fn set_main_window(window: tauri::WebviewWindow) {
    let cell = MAIN_WINDOW.get_or_init(|| Mutex::new(None));
    if let Ok(mut slot) = cell.lock() {
        *slot = Some(window);
    }
}

fn main_window() -> Option<tauri::WebviewWindow> {
    MAIN_WINDOW
        .get()
        .and_then(|m| m.lock().ok())
        .and_then(|slot| slot.clone())
}

/// True while a native file panel is up.
///
/// The server is a single accept loop, so a panel blocks EVERY other request
/// until the user answers it — including a second request for a panel, which
/// then opens the moment the first one closes. The guard turns that into an
/// immediate "nothing chosen", which is what a second click on Open should mean
/// when a picker is already waiting.
static PICKER_OPEN: AtomicBool = AtomicBool::new(false);

struct PickerGuard;

impl PickerGuard {
    fn acquire() -> Option<Self> {
        if PICKER_OPEN.swap(true, Ordering::SeqCst) {
            None
        } else {
            Some(PickerGuard)
        }
    }
}

impl Drop for PickerGuard {
    fn drop(&mut self) {
        PICKER_OPEN.store(false, Ordering::SeqCst);
    }
}

/// Return the user's home directory.
fn handle_home_dir() -> Result<String, String> {
    std::env::var("HOME")
        .or_else(|_| std::env::var("USERPROFILE"))
        .map(|p| format!("{{\"path\":\"{}\"}}", p.replace('\\', "\\\\").replace('"', "\\\"")))
        .map_err(|_| "cannot determine home directory".to_string())
}

/// Open a native save dialog, return the chosen path (no data written yet).
///
/// Request body:
///   { "suggested_name": "foo.stl",
///     "title": "Export",                              (optional, default "Save")
///     "filters": [{"name": "STL files",               (optional, default STL+3MF)
///                  "extensions": ["stl"]},
///                 ...] }
fn handle_pick_save_path(request: &mut tiny_http::Request) -> Result<String, String> {
    let mut body = String::new();
    request
        .as_reader()
        .read_to_string(&mut body)
        .map_err(|e| format!("read error: {}", e))?;

    #[derive(serde::Deserialize)]
    struct Filter {
        name: String,
        extensions: Vec<String>,
    }
    #[derive(serde::Deserialize)]
    struct Req {
        suggested_name: String,
        #[serde(default)]
        title: Option<String>,
        #[serde(default)]
        filters: Option<Vec<Filter>>,
    }
    let req: Req =
        serde_json::from_str(&body).map_err(|e| format!("JSON parse error: {}", e))?;

    let _guard = match PickerGuard::acquire() {
        Some(g) => g,
        // a panel is already waiting for the user: "nothing chosen"
        None => return Ok("null".to_string()),
    };

    let mut dialog = rfd::FileDialog::new()
        .set_title(req.title.as_deref().unwrap_or("Save"))
        .set_file_name(&req.suggested_name);
    let parent = main_window();
    if let Some(w) = parent.as_ref() {
        dialog = dialog.set_parent(w);
    }

    match req.filters {
        Some(fs) if !fs.is_empty() => {
            for f in fs {
                let ext_refs: Vec<&str> = f.extensions.iter().map(|s| s.as_str()).collect();
                dialog = dialog.add_filter(&f.name, &ext_refs);
            }
        }
        _ => {
            dialog = dialog
                .add_filter("STL files", &["stl"])
                .add_filter("3MF files", &["3mf"]);
        }
    }

    match dialog.save_file() {
        Some(path) => Ok(format!(
            "{{\"path\":\"{}\"}}",
            path.to_string_lossy().replace('\\', "\\\\").replace('"', "\\\"")
        )),
        None => Ok("null".to_string()),
    }
}

/// Open a native open dialog, return the chosen path (no data read yet).
///
/// Request body (all optional):
///   { "title": "Open",
///     "filters": [{"name": "Clojure files", "extensions": ["clj"]}, ...] }
fn handle_pick_open_path(request: &mut tiny_http::Request) -> Result<String, String> {
    let mut body = String::new();
    request
        .as_reader()
        .read_to_string(&mut body)
        .map_err(|e| format!("read error: {}", e))?;

    #[derive(serde::Deserialize)]
    struct Filter {
        name: String,
        extensions: Vec<String>,
    }
    #[derive(serde::Deserialize)]
    struct Req {
        #[serde(default)]
        title: Option<String>,
        #[serde(default)]
        filters: Option<Vec<Filter>>,
    }
    // Empty body is valid (all fields optional).
    let req: Req = if body.trim().is_empty() {
        Req { title: None, filters: None }
    } else {
        serde_json::from_str(&body).map_err(|e| format!("JSON parse error: {}", e))?
    };

    let _guard = match PickerGuard::acquire() {
        Some(g) => g,
        None => return Ok("null".to_string()),
    };

    let mut dialog = rfd::FileDialog::new().set_title(req.title.as_deref().unwrap_or("Open"));
    let parent = main_window();
    if let Some(w) = parent.as_ref() {
        dialog = dialog.set_parent(w);
    }

    match req.filters {
        Some(fs) if !fs.is_empty() => {
            for f in fs {
                let ext_refs: Vec<&str> = f.extensions.iter().map(|s| s.as_str()).collect();
                dialog = dialog.add_filter(&f.name, &ext_refs);
            }
        }
        _ => {
            dialog = dialog.add_filter("Clojure files", &["clj", "cljs", "edn"]);
        }
    }

    match dialog.pick_file() {
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
        .map(|h| expand_tilde(h.value.as_str()))
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
        .map(|h| expand_tilde(h.value.as_str()))
        .ok_or_else(|| "missing X-File-Path header".to_string())?;

    // Drain body
    let mut _buf = Vec::new();
    let _ = request.as_reader().read_to_end(&mut _buf);

    std::fs::remove_file(&path).map_err(|e| format!("delete error: {}", e))?;
    Ok("{\"deleted\":true}".to_string())
}

/// Expand a leading `~` into the user's home directory. The front end already
/// tries to do this (stl/expand-home), but its lookup is a synchronous XHR that
/// can fail quietly inside a WKWebView — and a `~` that reaches the filesystem
/// verbatim asks for `CWD/~/…`, which for a Finder-launched app is `/~`:
/// permission denied, write rejected, and (before 2026-08-24) nobody told the
/// user. The server knows its own $HOME; there is no reason to trust the client
/// to have known it.
fn expand_tilde(path: &str) -> String {
    if path == "~" || path.starts_with("~/") {
        if let Ok(home) = std::env::var("HOME").or_else(|_| std::env::var("USERPROFILE")) {
            return format!("{}{}", home, &path[1..]);
        }
    }
    path.to_string()
}

/// Write raw bytes to a given path (from X-File-Path header).
fn handle_write_file(request: &mut tiny_http::Request) -> Result<String, String> {
    let path = request
        .headers()
        .iter()
        .find(|h| h.field.as_str() == "X-File-Path")
        .map(|h| expand_tilde(h.value.as_str()))
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

/// How many ports above PORT to try when PORT is taken (another Ridley).
const PORT_TRIES: u16 = 10;

/// Bind the server and return the port it listens on, or None if none of
/// PORT..PORT+PORT_TRIES could be bound. The port goes into the webview's
/// initialization script (main.rs) so THIS window talks to ITS OWN server:
/// with a fixed port, a second Ridley silently sent every request — file
/// panels included — to the first one, and the save dialog opened on the
/// other window (2026-09-28).
pub fn start() -> Option<u16> {
    // NOT expect(). Binding used to happen inside the detached thread: a
    // panic there killed it alone, the window opened as usual, and every
    // file operation returned nothing for the rest of the run (2026-08-23).
    let mut bound = None;
    for port in PORT..PORT + PORT_TRIES {
        match Server::http(format!("127.0.0.1:{}", port)) {
            Ok(s) => {
                bound = Some((port, s));
                break;
            }
            Err(e) => eprintln!("geo-server: cannot listen on 127.0.0.1:{}: {}", port, e),
        }
    }
    let (port, server) = match bound {
        Some(b) => b,
        None => {
            eprintln!(
                "geo-server: no free port in {}..{} — THIS window will not be able \
                 to read or write files.",
                PORT,
                PORT + PORT_TRIES
            );
            return None;
        }
    };
    eprintln!("geo-server: listening on http://127.0.0.1:{}", port);
    thread::spawn(move || {

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

            // A native file panel waits for a HUMAN, and everything else in this
            // app goes through this one accept loop — so handling a picker inline
            // freezes reads, writes and CSG for as long as the panel is up, and
            // queues any second picker request to spring open the moment the
            // first one closes. Both were real (2026-08-12: a panel left behind
            // the window made the app look dead). So the pickers answer on their
            // own thread and the loop stays free; PickerGuard then sees a second
            // request WHILE the first is waiting, and declines it.
            if path == "/pick-save-path" || path == "/pick-open-path" {
                let cors = cors.clone();
                let content_type = content_type.clone();
                let open = path == "/pick-open-path";
                thread::spawn(move || {
                    let mut request = request;
                    let r = if open {
                        handle_pick_open_path(&mut request)
                    } else {
                        handle_pick_save_path(&mut request)
                    };
                    let (status, json) = match r {
                        Ok(json) => (200, json),
                        Err(e) => (500, format!("{{\"error\":\"{}\"}}", e)),
                    };
                    let resp = Response::from_string(json)
                        .with_status_code(status)
                        .with_header(cors)
                        .with_header(content_type);
                    let _ = request.respond(resp);
                });
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
    Some(port)
}
