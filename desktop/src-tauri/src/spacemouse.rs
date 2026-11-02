//! SpaceMouse reader (dev-docs/brief-spacemouse-edit-attach.md, commit 2).
//!
//! One thread owns the device. It is open — EXCLUSIVELY — only while a session
//! wants it (`spacemouse_acquire` / `spacemouse_release`) AND the window has the
//! focus; otherwise it is closed and macOS drives it as a mouse, as it does
//! without 3DxWare (dev-docs/accertamento-spacemouse-risultati.md, point 3).
//!
//! The thread stays thin: raw values in the device frame go to the webview as the
//! `spacemouse` event `{t:[tx,ty,tz], r:[rx,ry,rz], buttons}`; frame conversion
//! and the gesture logic are ClojureScript (ridley.input.spacemouse). The device
//! sends ~125 reports/s while deflected, so emits are coalesced to one per 16 ms
//! with the latest state; reaching all-zero and any button change go out at once.
//! `spacemouse-status {connected}` goes out on every change while wanted, and
//! once right after each acquire.
//!
//! macOS only: on Linux hidapi needs libudev headers to build and a udev rule to
//! read the device, neither of which the Linux build has — there the commands
//! exist and the status is always `connected: false`.

use std::sync::atomic::{AtomicBool, Ordering};
use tauri::{AppHandle, Emitter};

static WANTED: AtomicBool = AtomicBool::new(false);
static FOCUSED: AtomicBool = AtomicBool::new(true);
/// Set by spacemouse_acquire: the thread must emit the status even if unchanged.
static ANNOUNCE: AtomicBool = AtomicBool::new(false);

#[derive(Clone, serde::Serialize)]
struct Status {
    connected: bool,
}

#[tauri::command]
pub fn spacemouse_acquire() {
    WANTED.store(true, Ordering::SeqCst);
    ANNOUNCE.store(true, Ordering::SeqCst);
}

#[tauri::command]
pub fn spacemouse_release() {
    WANTED.store(false, Ordering::SeqCst);
}

/// From the window's Focused event: no focus, no exclusive access.
pub fn set_focused(focused: bool) {
    FOCUSED.store(focused, Ordering::SeqCst);
}

#[cfg(not(target_os = "macos"))]
pub fn start(app: AppHandle) {
    std::thread::spawn(move || stub_loop(app));
}

#[cfg(target_os = "macos")]
pub use imp::start;

#[cfg(target_os = "macos")]
mod imp {
    use super::*;
    use hidapi::{HidApi, HidDevice};
    use std::time::{Duration, Instant};

    const VID: u16 = 0x256F;
    const COALESCE: Duration = Duration::from_millis(16);
    const RETRY: Duration = Duration::from_millis(500);

    #[derive(Clone, Copy, PartialEq, Default, serde::Serialize)]
    struct Raw {
        t: [i16; 3],
        r: [i16; 3],
        buttons: u8,
    }

    impl Raw {
        fn at_rest(&self) -> bool {
            self.t == [0; 3] && self.r == [0; 3]
        }
    }

    enum OpenError {
        Absent,
        /// Present but the exclusive open failed — typically another Ridley has it.
        Busy(String),
    }

    /// The multi-axis interface (usage page 1 / usage 8), opened exclusively.
    fn open(api: &mut HidApi) -> Result<HidDevice, OpenError> {
        let _ = api.refresh_devices();
        api.set_open_exclusive(true);
        let info = api
            .device_list()
            .filter(|d| d.vendor_id() == VID)
            .min_by_key(|d| !(d.usage_page() == 1 && d.usage() == 8))
            .ok_or(OpenError::Absent)?;
        info.open_device(api).map_err(|e| OpenError::Busy(e.to_string()))
    }

    fn int3(b: &[u8]) -> [i16; 3] {
        [
            i16::from_le_bytes([b[0], b[1]]),
            i16::from_le_bytes([b[2], b[3]]),
            i16::from_le_bytes([b[4], b[5]]),
        ]
    }

    pub fn start(app: AppHandle) {
        std::thread::spawn(move || run(app));
    }

    fn run(app: AppHandle) {
        let mut api = match HidApi::new() {
            Ok(api) => api,
            Err(e) => {
                eprintln!("spacemouse: hidapi init failed: {e}");
                return stub_loop(app);
            }
        };
        let status = |connected: bool| {
            let _ = app.emit("spacemouse-status", Status { connected });
        };
        let mut dev: Option<HidDevice> = None;
        let mut connected = false;
        let mut last_try: Option<Instant> = None;
        let mut last_err = String::new();
        let mut state = Raw::default();
        let mut sent = Raw::default();
        let mut last_emit = Instant::now();
        let mut buf = [0u8; 64];

        loop {
            let want = WANTED.load(Ordering::SeqCst) && FOCUSED.load(Ordering::SeqCst);
            let announce = ANNOUNCE.swap(false, Ordering::SeqCst);

            if !want {
                if dev.take().is_some() {
                    eprintln!("spacemouse: released");
                    // Whatever was held is gone for the webview too.
                    state = Raw::default();
                    if sent != state {
                        sent = state;
                        let _ = app.emit("spacemouse", sent);
                    }
                }
                last_try = None;
                if announce {
                    status(connected);
                }
                std::thread::sleep(Duration::from_millis(50));
                continue;
            }

            let Some(d) = dev.as_ref() else {
                if announce || last_try.map_or(true, |t| t.elapsed() >= RETRY) {
                    last_try = Some(Instant::now());
                    match open(&mut api) {
                        Ok(d) => {
                            eprintln!("spacemouse: acquired (exclusive)");
                            dev = Some(d);
                            last_err.clear();
                            connected = true;
                            status(true);
                        }
                        Err(e) => {
                            let msg = match e {
                                OpenError::Absent => "not connected".to_string(),
                                OpenError::Busy(m) => format!("busy ({m})"),
                            };
                            if msg != last_err {
                                eprintln!("spacemouse: {msg}, retrying every 500 ms");
                                last_err = msg;
                            }
                            if connected || announce {
                                connected = false;
                                status(false);
                            }
                        }
                    }
                } else {
                    std::thread::sleep(Duration::from_millis(50));
                }
                continue;
            };

            match d.read_timeout(&mut buf, 8) {
                Ok(n) if n >= 7 && buf[0] == 1 => state.t = int3(&buf[1..7]),
                Ok(n) if n >= 7 && buf[0] == 2 => state.r = int3(&buf[1..7]),
                Ok(n) if n >= 2 && buf[0] == 3 => state.buttons = buf[1],
                Ok(_) => {}
                Err(e) => {
                    eprintln!("spacemouse: read error ({e}) — unplugged?");
                    dev = None;
                    connected = false;
                    status(false);
                    state = Raw::default();
                    if sent != state {
                        sent = state;
                        let _ = app.emit("spacemouse", sent);
                    }
                    continue;
                }
            }
            if announce {
                status(connected);
            }
            if state != sent {
                let urgent = state.buttons != sent.buttons || state.at_rest();
                if urgent || last_emit.elapsed() >= COALESCE {
                    sent = state;
                    last_emit = Instant::now();
                    let _ = app.emit("spacemouse", sent);
                }
            }
        }
    }
}

/// No device support: answer every acquire with `connected: false`. The whole
/// thread off macOS, and the fallback there if hidapi cannot initialize.
fn stub_loop(app: AppHandle) {
    loop {
        if ANNOUNCE.swap(false, Ordering::SeqCst) {
            let _ = app.emit("spacemouse-status", Status { connected: false });
        }
        std::thread::sleep(std::time::Duration::from_millis(100));
    }
}
