//! Per-frame video timing trace for latency investigations on Android.
//!
//! Off unless `adb shell setprop debug.rustdesk.xr.latency 1` (the same switch as the XR
//! renderer's flip probe). Lines go to logcat with tag `RustDeskXRTrace` and carry
//! `CLOCK_REALTIME` seconds so they can be aligned with host-side stimulus logs.
//! `pts` is the host's per-frame timestamp (ms since its video service started).

use std::{
    ffi::CString,
    os::raw::{c_char, c_int},
    sync::atomic::{AtomicBool, AtomicU64, Ordering},
    time::{SystemTime, UNIX_EPOCH},
};

extern "C" {
    fn __system_property_get(name: *const c_char, value: *mut c_char) -> c_int;
    fn __android_log_write(prio: c_int, tag: *const c_char, text: *const c_char) -> c_int;
}

static ENABLED: AtomicBool = AtomicBool::new(false);
static CHECKED_MS: AtomicU64 = AtomicU64::new(0);

/// Wall-clock seconds.
pub fn now() -> f64 {
    SystemTime::now()
        .duration_since(UNIX_EPOCH)
        .map(|d| d.as_secs_f64())
        .unwrap_or_default()
}

/// Whether tracing is on; re-reads the system property at most once a second.
pub fn enabled() -> bool {
    let now_ms = (now() * 1000.0) as u64;
    if now_ms.saturating_sub(CHECKED_MS.load(Ordering::Relaxed)) >= 1000 {
        CHECKED_MS.store(now_ms, Ordering::Relaxed);
        let mut value = [0 as c_char; 92];
        let len = unsafe {
            __system_property_get(
                b"debug.rustdesk.xr.latency\0".as_ptr() as *const c_char,
                value.as_mut_ptr(),
            )
        };
        ENABLED.store(len > 0 && value[0] == b'1' as c_char, Ordering::Relaxed);
    }
    ENABLED.load(Ordering::Relaxed)
}

pub fn log(line: String) {
    if let Ok(text) = CString::new(line) {
        unsafe {
            __android_log_write(4, b"RustDeskXRTrace\0".as_ptr() as *const c_char, text.as_ptr());
        }
    }
}
