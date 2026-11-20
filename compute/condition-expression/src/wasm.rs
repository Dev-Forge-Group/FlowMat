//! The WebAssembly face, with no binding generator: the host writes UTF-8 into memory it got from `alloc` and reads
//! JSON back. `wasm/check-fixtures.mjs` shows the calls.

use std::alloc::{Layout, alloc as allocate, dealloc as deallocate};

use std::collections::HashSet;

use serde_json::{Value as Json, json};

use crate::{Condition, ConditionError, Values};

/// Memory for the host to write `length` bytes into. The host gives it back with `dealloc`.
#[unsafe(no_mangle)]
pub extern "C" fn alloc(length: usize) -> *mut u8 {
    if length == 0 {
        return std::ptr::NonNull::dangling().as_ptr();
    }
    // SAFETY: the layout has a non-zero size.
    unsafe { allocate(Layout::array::<u8>(length).expect("a byte array layout")) }
}

/// Gives back memory from `alloc` or a result.
///
/// # Safety
/// `pointer` and `length` are exactly what `alloc` was given and returned, or a result's pointer and length.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn dealloc(pointer: *mut u8, length: usize) {
    if length != 0 {
        // SAFETY: the caller passes back what `alloc` or `result` allocated with this layout.
        unsafe { deallocate(pointer, Layout::array::<u8>(length).expect("a byte array layout")) }
    }
}

/// `{"ok":true,"attributes":[…],"declaredError":…}`, or `{"ok":false,"position":N,"reason":"…","message":"…"}` with
/// Java's message. `declared` is a JSON array of the names the port's schema declares; with length 0 there is no schema
/// to check against and `declaredError` is absent.
///
/// # Safety
/// Both pointers point at as many bytes as their lengths, written by the host.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn check(
    source: *const u8,
    source_length: usize,
    declared: *const u8,
    declared_length: usize,
) -> u64 {
    // SAFETY: the caller gives memory it wrote.
    let (source, declared) = unsafe { (read(source, source_length), read(declared, declared_length)) };
    let failure = |error: ConditionError| json!({"ok": false, "position": error.position, "reason": error.reason, "message": error.to_string()});
    let report = match Condition::compile(&source) {
        Err(error) => failure(error),
        Ok(condition) => {
            let mut report = json!({"ok": true, "attributes": condition.attribute_references()});
            if !declared.is_empty() {
                let Ok(names) = serde_json::from_str::<HashSet<String>>(&declared) else {
                    return result(failure(ConditionError {
                        position: 0,
                        reason: "declared is not a JSON array of names".into(),
                    }));
                };
                report["declaredError"] = match condition.require_declared_attributes(&names) {
                    Ok(()) => Json::Null,
                    Err(error) => json!({"position": error.position, "reason": error.reason}),
                };
            }
            report
        }
    };
    result(report)
}

/// 1 when the condition holds for the snapshot (JSON text), 0 when not, -1 when the source is not a condition, -2 when
/// the snapshot is not JSON.
///
/// # Safety
/// Both pointers point at as many bytes as their lengths, written by the host.
#[unsafe(no_mangle)]
pub unsafe extern "C" fn evaluate(
    source: *const u8,
    source_length: usize,
    snapshot: *const u8,
    snapshot_length: usize,
) -> i32 {
    // SAFETY: the caller gives memory it wrote.
    let (source, snapshot) = unsafe { (read(source, source_length), read(snapshot, snapshot_length)) };
    let Ok(condition) = Condition::compile(&source) else {
        return -1;
    };
    let Ok(snapshot) = serde_json::from_str(&snapshot) else {
        return -2;
    };
    i32::from(condition.evaluate(&Values::from_snapshot(&snapshot)))
}

/// # Safety
/// `pointer` points at `length` readable bytes.
unsafe fn read(pointer: *const u8, length: usize) -> String {
    if length == 0 {
        return String::new();
    }
    // SAFETY: as the caller promises.
    String::from_utf8_lossy(unsafe { std::slice::from_raw_parts(pointer, length) }).into_owned()
}

/// The JSON text's pointer in the high 32 bits and its length in the low 32. The host frees it with `dealloc`.
fn result(report: Json) -> u64 {
    let bytes = report.to_string().into_bytes().into_boxed_slice();
    let length = bytes.len();
    let pointer = Box::into_raw(bytes) as *mut u8;
    ((pointer as u64) << 32) | length as u64
}
