//! JNI surface for the Seeker IDE engine.
//!
//! Naming contract: every function here maps to an `external` declaration in
//! `to.eyed.seeker.code.core.CoreBridge` on the Kotlin side. Keep the two
//! files in sync — this is the only place the two worlds meet.
//!
//! Design rule: calls across this boundary are coarse-grained. The Kotlin
//! layer must never loop over per-character JNI calls; batch work on one side
//! or the other.
//!
//! Error convention: functions returning `jlong` use `-1` for "unknown
//! buffer / invalid arguments" and, for undo/redo, also for "nothing to
//! undo/redo". Functions returning strings use `null` for unknown buffers.

use jni::JNIEnv;
use jni::objects::{JClass, JLongArray, JString};
use jni::sys::{JNI_FALSE, JNI_TRUE, jboolean, jint, jintArray, jlong, jlongArray, jstring};
use std::path::Path;
use std::sync::OnceLock;
use std::sync::atomic::{AtomicBool, Ordering};

use engine::Engine;

static ENGINE: OnceLock<Engine> = OnceLock::new();

/// Whether to log the engine's own diagnostics. Set by `initialize` before the
/// engine — and therefore the logger — comes up.
static VERBOSE_LOG: AtomicBool = AtomicBool::new(false);

fn engine() -> &'static Engine {
    ENGINE.get_or_init(|| {
        #[cfg(target_os = "android")]
        android_logger::init_once(
            android_logger::Config::default()
                // Debug in a debug build: the engine's own diagnostics (git
                // runs, scans) are debug-level, and chasing a problem without
                // them means rebuilding to see anything. Release stays at Info.
                //
                // `cfg!(debug_assertions)` cannot answer this: one cargo
                // invocation, always `--release`, serves every Android build
                // type, so it is false even in a debug APK. Kotlin passes
                // `BuildConfig.DEBUG` in instead.
                .with_max_level(if VERBOSE_LOG.load(Ordering::Relaxed) {
                    log::LevelFilter::Debug
                } else {
                    log::LevelFilter::Info
                })
                .with_tag("seeker-core"),
        );
        install_panic_hook();
        log::info!("engine initialized, version {}", engine::ENGINE_VERSION);
        Engine::new()
    })
}

/// Route panics to the log. Android discards a process's stderr, so without
/// this a panic on an engine thread — the runtime thread, a worktree scan —
/// leaves no trace. Release builds set `panic = "abort"`, so the panic then
/// takes the whole process down; this hook runs before the abort, which is
/// what turns a bare SIGABRT in the crash report into a message with a thread
/// name and location in logcat.
fn install_panic_hook() {
    let previous = std::panic::take_hook();
    std::panic::set_hook(Box::new(move |info| {
        let location = info
            .location()
            .map(|location| location.to_string())
            .unwrap_or_else(|| "unknown location".to_owned());
        let thread = std::thread::current();
        let name = thread.name().unwrap_or("<unnamed>");
        log::error!("panic on thread {name} at {location}: {}", info);
        previous(info);
    }));
}

fn get_string(env: &mut JNIEnv, s: &JString) -> String {
    env.get_string(s).map(Into::into).unwrap_or_default()
}

fn to_jstring(env: &JNIEnv, text: String) -> jstring {
    env.new_string(text)
        .expect("failed to allocate Java string")
        .into_raw()
}

/// Hand the engine the app's private files directory, then bring it up.
/// Must be the first call into the bridge — see `engine::initialize`.
///
/// Cheap on the calling thread: paths, logging and the `Engine` struct are
/// set up here, and the expensive part — booting the gpui runtime — is only
/// *kicked off*, onto the runtime's own thread. Work that reaches the runtime
/// before it is up queues on its job channel, so callers gate on the same
/// version counters they always poll, never on this call.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_initialize(
    mut env: JNIEnv,
    _class: JClass,
    files_dir: JString,
    verbose_logging: jboolean,
) {
    let files_dir = get_string(&mut env, &files_dir);
    VERBOSE_LOG.store(verbose_logging != JNI_FALSE, Ordering::Relaxed);
    engine::initialize(Path::new(&files_dir));
    // Warm the gpui runtime now, while the app is still composing its first
    // frame, instead of paying the boot when the first project opens.
    engine().start_runtime();
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_engineVersion(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    engine();
    to_jstring(&env, engine::ENGINE_VERSION.to_owned())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_createBuffer(
    mut env: JNIEnv,
    _class: JClass,
    initial_text: JString,
) -> jlong {
    let text = get_string(&mut env, &initial_text);
    engine().create_buffer(&text) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_closeBuffer(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jboolean {
    if engine().close_buffer(buffer_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_applyEdit(
    mut env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    start: jlong,
    end: jlong,
    text: JString,
) -> jlong {
    if start < 0 || end < 0 {
        return -1;
    }
    let text = get_string(&mut env, &text);
    match engine().edit(buffer_id as u64, start as usize, end as usize, &text) {
        Ok(version) => version as jlong,
        Err(err) => {
            log::warn!("applyEdit failed: {err}");
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_undoBuffer(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    match engine().undo(buffer_id as u64) {
        Ok(Some(version)) => version as jlong,
        Ok(None) => -1,
        Err(err) => {
            log::warn!("undoBuffer failed: {err}");
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_redoBuffer(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    match engine().redo(buffer_id as u64) {
        Ok(Some(version)) => version as jlong,
        Ok(None) => -1,
        Err(err) => {
            log::warn!("redoBuffer failed: {err}");
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferVersion(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    match engine().version(buffer_id as u64) {
        Ok(version) => version as jlong,
        Err(err) => {
            log::warn!("bufferVersion failed: {err}");
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferLineCount(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    match engine().line_count(buffer_id as u64) {
        Ok(count) => count as jlong,
        Err(err) => {
            log::warn!("bufferLineCount failed: {err}");
            -1
        }
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferLines(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    first_line: jlong,
    last_line: jlong,
) -> jstring {
    let first = first_line.max(0).min(u32::MAX as jlong) as u32;
    let last = last_line.max(0).min(u32::MAX as jlong) as u32;
    match engine().lines(buffer_id as u64, first, last) {
        Ok(text) => to_jstring(&env, text),
        Err(err) => {
            log::warn!("bufferLines failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Assign a tree-sitter language (grammar name, e.g. "rust") to a buffer.
/// Returns false for unknown buffers or unknown language names.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferSetLanguage(
    mut env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    language: JString,
) -> jboolean {
    let language = get_string(&mut env, &language);
    match engine().set_language(buffer_id as u64, &language) {
        Ok(true) => JNI_TRUE,
        Ok(false) => {
            log::warn!("bufferSetLanguage: unknown language {language:?}");
            JNI_FALSE
        }
        Err(err) => {
            log::warn!("bufferSetLanguage failed: {err}");
            JNI_FALSE
        }
    }
}

/// Every language the binary can parse, as a JSON array of objects with
/// `grammar` and `name`, sorted by display name — what the language selector
/// lists (Zed's `language_selector::Toggle`).
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_availableLanguages(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let languages = engine::available_languages();
    let json = serde_json::to_string(&languages).unwrap_or_else(|err| {
        log::warn!("availableLanguages failed to serialize: {err}");
        "[]".to_owned()
    });
    to_jstring(&env, json)
}

/// Highlight spans for rows [first_line, last_line), flattened as groups of
/// four ints: row, UTF-16 start column, UTF-16 end column, style id (index
/// into the engine's STYLE_NAMES). Empty array when the buffer has no
/// language; null for unknown buffers.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferHighlights(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    first_line: jlong,
    last_line: jlong,
) -> jintArray {
    let first = first_line.max(0).min(u32::MAX as jlong) as u32;
    let last = last_line.max(0).min(u32::MAX as jlong) as u32;
    match engine().highlights(buffer_id as u64, first, last) {
        Ok(spans) => {
            let mut flat = Vec::with_capacity(spans.len() * 4);
            for span in &spans {
                flat.push(span.row as i32);
                flat.push(span.start_col_utf16 as i32);
                flat.push(span.end_col_utf16 as i32);
                flat.push(span.style as i32);
            }
            let array = env
                .new_int_array(flat.len() as i32)
                .expect("failed to allocate highlight array");
            env.set_int_array_region(&array, 0, &flat)
                .expect("failed to fill highlight array");
            array.into_raw()
        }
        Err(err) => {
            log::warn!("bufferHighlights failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// The symbol path containing the caret — Zed's breadcrumbs after the file
/// name — as a JSON array of strings, outermost first. Empty array when the
/// buffer has no language or no symbol contains the caret; null for an
/// unknown buffer. Columns are UTF-16, like every caret the UI holds.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferOutlinePath(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    col_utf16: jlong,
) -> jstring {
    let row = row.max(0).min(u32::MAX as jlong) as u32;
    let col = col_utf16.max(0).min(u32::MAX as jlong) as u32;
    match engine().outline_path(buffer_id as u64, row, col) {
        Ok(path) => {
            let json = serde_json::to_string(&path).unwrap_or_else(|err| {
                log::warn!("bufferOutlinePath failed to serialize: {err}");
                "[]".to_owned()
            });
            to_jstring(&env, json)
        }
        Err(err) => {
            log::warn!("bufferOutlinePath failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Every foldable block in the buffer, from the syntax tree, as a JSON array
/// of `{start_row, end_row}` — the chip sits on `start_row` and rows
/// `start_row + 1..=end_row` hide, sorted by `start_row`, one per row. Reads
/// the last parsed tree like `bufferOutline`, so re-read it when
/// `bufferHighlightVersion` moves. Empty for a buffer with no language or a
/// grammar without an indents query — keep the indent walk for those; null
/// for unknown buffers.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferFoldRanges(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    match engine().fold_ranges(buffer_id as u64) {
        Ok(ranges) => {
            let json = serde_json::to_string(&ranges).unwrap_or_else(|err| {
                log::warn!("bufferFoldRanges failed to serialize: {err}");
                "[]".to_owned()
            });
            to_jstring(&env, json)
        }
        Err(err) => {
            log::warn!("bufferFoldRanges failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// The smallest syntax node that strictly contains the given range, as a JSON
/// `{start_row, start_col_utf16, end_row, end_col_utf16}` — what
/// `editor::SelectLargerSyntaxNode` grows a selection to. `null` when the
/// range already covers the file, the buffer has no language, or the tree has
/// not been parsed yet, in which case the caller leaves the selection alone.
/// Reads the last parsed tree like `bufferFoldRanges`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferSyntaxNodeRange(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    start_row: jlong,
    start_col_utf16: jlong,
    end_row: jlong,
    end_col_utf16: jlong,
) -> jstring {
    let clamp = |value: jlong| value.max(0).min(u32::MAX as jlong) as u32;
    match engine().syntax_node_range(
        buffer_id as u64,
        clamp(start_row),
        clamp(start_col_utf16),
        clamp(end_row),
        clamp(end_col_utf16),
    ) {
        Ok(Some(range)) => match serde_json::to_string(&range) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("bufferSyntaxNodeRange failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        Ok(None) => std::ptr::null_mut(),
        Err(err) => {
            log::warn!("bufferSyntaxNodeRange failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// The innermost bracket pair around the given range, as a JSON
/// `{"open": {…}, "close": {…}}` of two row/column ranges — what the pane
/// highlights and what `editor::MoveToEnclosingBracket` jumps between. From
/// the grammar's `brackets.scm` where there is one and from a delimiter count
/// where there is not, so a plain-text buffer still matches its braces.
/// `null` when nothing encloses the range.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferEnclosingBrackets(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    start_row: jlong,
    start_col_utf16: jlong,
    end_row: jlong,
    end_col_utf16: jlong,
) -> jstring {
    let clamp = |value: jlong| value.max(0).min(u32::MAX as jlong) as u32;
    match engine().enclosing_brackets(
        buffer_id as u64,
        clamp(start_row),
        clamp(start_col_utf16),
        clamp(end_row),
        clamp(end_col_utf16),
    ) {
        Ok(Some((open, close))) => {
            match serde_json::to_string(&serde_json::json!({"open": open, "close": close})) {
                Ok(json) => to_jstring(&env, json),
                Err(err) => {
                    log::warn!("bufferEnclosingBrackets failed to serialize: {err}");
                    std::ptr::null_mut()
                }
            }
        }
        Ok(None) => std::ptr::null_mut(),
        Err(err) => {
            log::warn!("bufferEnclosingBrackets failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Apply the buffer's `remove_trailing_whitespace_on_save` and
/// `ensure_final_newline_on_save` — the save path's whitespace rules, run
/// against the buffer's resolved settings so a language or a project may turn
/// either off. Returns true when the buffer changed, in which case the editor
/// must resync. **Blocking** (reads settings.json): call it off the main
/// thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_cleanBufferOnSave(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jboolean {
    engine().clean_buffer_on_save(buffer_id as u64) as jboolean
}

/// Every outline item in the buffer, in source order — the rows of Zed's
/// outline picker — as a JSON array of `{label, depth, row, col_utf16}`.
/// Empty array when the buffer has no language; null for unknown buffers.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferOutline(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    match engine().outline(buffer_id as u64) {
        Ok(items) => {
            let json = serde_json::to_string(&items).unwrap_or_else(|err| {
                log::warn!("bufferOutline failed to serialize: {err}");
                "[]".to_owned()
            });
            to_jstring(&env, json)
        }
        Err(err) => {
            log::warn!("bufferOutline failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Byte offset of (row, byte column), clipped to the buffer. -1 for an
/// unknown buffer or negative arguments.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_pointToOffset(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    column: jlong,
) -> jlong {
    if row < 0 || column < 0 {
        return -1;
    }
    let row = row.min(u32::MAX as jlong) as u32;
    let column = column.min(u32::MAX as jlong) as u32;
    match engine().point_to_offset(buffer_id as u64, row, column) {
        Ok(offset) => offset as jlong,
        Err(err) => {
            log::warn!("pointToOffset failed: {err}");
            -1
        }
    }
}

/// (row, byte column) of a byte offset, clipped to the buffer, packed as
/// `(row << 32) | column`. -1 for an unknown buffer or negative offset.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_offsetToPoint(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    offset: jlong,
) -> jlong {
    if offset < 0 {
        return -1;
    }
    match engine().offset_to_point(buffer_id as u64, offset as usize) {
        Ok((row, column)) => ((row as jlong) << 32) | column as jlong,
        Err(err) => {
            log::warn!("offsetToPoint failed: {err}");
            -1
        }
    }
}

// ---------------------------------------------------------------------------
// Projects (P3-2). Opening and expanding are asynchronous — they queue work on
// the engine's gpui runtime and return at once. The UI learns that something
// changed by watching `projectVersion`; every other call reads the mirrored
// snapshot and never blocks on the runtime.
// ---------------------------------------------------------------------------

/// Start scanning a directory as a project. Returns its id (always > 0).
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_openProject(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    let path = get_string(&mut env, &path);
    engine().open_project(Path::new(&path)) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_closeProject(
    _env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jboolean {
    if engine().close_project(project_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Monotonic version of the mirrored worktree snapshot; 0 while there is
/// nothing to show. Poll this to know when to re-read entries.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectVersion(
    _env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jlong {
    engine().project_version(project_id as u64) as jlong
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectScanComplete(
    _env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jboolean {
    if engine().project_scan_complete(project_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Why the project failed to open, or null if it did not fail.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectError(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    match engine().project_error(project_id as u64) {
        Some(error) => to_jstring(&env, error),
        None => std::ptr::null_mut(),
    }
}

/// Display name of the project root; null for an unknown project.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectRootName(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    match engine().project_root_name(project_id as u64) {
        Some(name) => to_jstring(&env, name),
        None => std::ptr::null_mut(),
    }
}

/// Direct children of a project-relative directory ("" for the root), as a
/// JSON array — one coarse call per expanded directory rather than one per
/// entry. Never null: unknown projects and unscanned directories give `[]`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectEntries(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    dir: JString,
) -> jstring {
    let dir = get_string(&mut env, &dir);
    let entries = engine().project_entries(project_id as u64, &dir);
    let json = serde_json::to_string(&entries).unwrap_or_else(|err| {
        log::warn!("projectEntries failed to serialize: {err}");
        "[]".to_owned()
    });
    to_jstring(&env, json)
}

/// Scan a directory the worktree deferred (ignored, hidden, or past
/// `file_scan_depth`). Asynchronous; the results arrive as a version bump.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_expandDirectory(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    dir: JString,
) -> jboolean {
    let dir = get_string(&mut env, &dir);
    if engine().expand_directory(project_id as u64, &dir) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Absolute path of a project-relative entry; null if the project is unknown
/// or the path tries to escape the root.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectEntryPath(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    path: JString,
) -> jstring {
    let path = get_string(&mut env, &path);
    match engine().project_entry_abs_path(project_id as u64, &path) {
        Some(path) => to_jstring(&env, path.to_string_lossy().into_owned()),
        None => std::ptr::null_mut(),
    }
}

/// Move project entries to the app's trash — the project panel's Delete key,
/// which is Zed's `project_panel::Trash`.
///
/// One coarse call for the whole selection, and one JSON answer:
/// `{"trashed":[{"path","id","name","original_parent"}, …]}` on success, or
/// `{"error":"…"}` with a sentence to show. The `trashed` array goes straight
/// back to `restoreTrash` if the user takes the Undo. **Blocking** — it moves
/// files; call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectTrash(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    paths_json: JString,
) -> jstring {
    let paths = path_list(&mut env, &paths_json);
    let json = match engine().trash_project_entries(project_id as u64, &paths) {
        Ok(trashed) => serde_json::json!({ "trashed": trashed }),
        Err(message) => serde_json::json!({ "error": message }),
    };
    to_jstring(&env, json.to_string())
}

/// Put trashed entries back — the panel's Undo, and Zed's
/// `project_panel::Undo`. Takes the `trashed` array `projectTrash` returned.
/// Returns null when it worked and the reason when it did not; a destination
/// whose name is occupied again refuses the whole restore rather than
/// overwriting. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_restoreTrash(
    mut env: JNIEnv,
    _class: JClass,
    entries_json: JString,
) -> jstring {
    let json = get_string(&mut env, &entries_json);
    let entries: Vec<engine::TrashedEntry> = match serde_json::from_str(&json) {
        Ok(entries) => entries,
        Err(err) => return to_jstring(&env, format!("Could not read the trashed entries: {err}")),
    };
    command_result(&env, engine().restore_trashed(&entries))
}

// ---------------------------------------------------------------------------
// Multi-root projects. A project holds an ordered list of folders — Zed's
// `Vec<Worktree>` — with the one it was opened with first. `projectEntries`
// and friends above are that first folder; the calls below name a folder
// explicitly, which is what the panel does once there is more than one.
// ---------------------------------------------------------------------------

/// Every folder of a project, in order, as a JSON array of objects with `id`,
/// `name`, `path`, `scan_complete`, `error` and `is_primary`. `[]` for an
/// unknown project.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectWorktrees(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let worktrees = engine().project_worktrees(project_id as u64);
    let json = serde_json::to_string(&worktrees).unwrap_or_else(|err| {
        log::warn!("projectWorktrees failed to serialize: {err}");
        "[]".to_owned()
    });
    to_jstring(&env, json)
}

/// Add a folder to an open project — `workspace::AddFolderToProject`. The path
/// must already exist on disk (a SAF tree is imported by Kotlin first).
///
/// Returns JSON: `{"id": <folder id>}` on success — the id of the folder that
/// now covers the path, which for a path already inside one of the project's
/// folders is that existing folder, as Zed's `find_or_create_worktree` does —
/// or `{"error": "…"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectAddWorktree(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    path: JString,
) -> jstring {
    let path = get_string(&mut env, &path);
    let json = match engine().add_worktree(project_id as u64, Path::new(&path)) {
        Ok(handle) => serde_json::json!({ "id": handle }),
        Err(message) => serde_json::json!({ "error": message }),
    };
    to_jstring(&env, json.to_string())
}

/// Drop a folder from a project — `workspace::RemoveWorktreeFromProject`.
/// null when it worked, the reason when it did not; the folder the project was
/// opened with cannot be removed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectRemoveWorktree(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    worktree_id: jlong,
) -> jstring {
    command_result(
        &env,
        engine().remove_worktree(project_id as u64, worktree_id as u64),
    )
}

/// Direct children of a directory inside one folder of the project, in the
/// same JSON shape as `projectEntries`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_worktreeEntries(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    worktree_id: jlong,
    dir: JString,
) -> jstring {
    let dir = get_string(&mut env, &dir);
    let entries = engine().worktree_entries(project_id as u64, worktree_id as u64, &dir);
    let json = serde_json::to_string(&entries).unwrap_or_else(|err| {
        log::warn!("worktreeEntries failed to serialize: {err}");
        "[]".to_owned()
    });
    to_jstring(&env, json)
}

/// `expandDirectory` for a named folder of the project.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_expandWorktreeDirectory(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    worktree_id: jlong,
    dir: JString,
) -> jboolean {
    let dir = get_string(&mut env, &dir);
    if engine().expand_worktree_directory(project_id as u64, worktree_id as u64, &dir) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Absolute path of an entry relative to one folder's root; null if the
/// project or folder is unknown, or the path tries to escape it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_worktreeEntryPath(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    worktree_id: jlong,
    path: JString,
) -> jstring {
    let path = get_string(&mut env, &path);
    match engine().worktree_entry_abs_path(project_id as u64, worktree_id as u64, &path) {
        Some(path) => to_jstring(&env, path.to_string_lossy().into_owned()),
        None => std::ptr::null_mut(),
    }
}

// ---------------------------------------------------------------------------
// Git status (P3-8). The engine has no git of its own: it runs the one inside
// the Debian userland, through proot. Kotlin knows where that is — the engine
// must not guess — so `setUserland` is what turns the feature on. The `play`
// flavour never calls it, and every query below then answers "nothing to
// show", which is exactly what a clean repository looks like.
// ---------------------------------------------------------------------------

/// Tell the engine where proot and the Debian rootfs are. Call it once the
/// userland reports itself installed; never in the `play` flavour.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_setUserland(
    mut env: JNIEnv,
    _class: JClass,
    proot: JString,
    rootfs: JString,
    tmp_dir: JString,
    projects_dir: JString,
) {
    let proot = get_string(&mut env, &proot);
    let rootfs = get_string(&mut env, &rootfs);
    let tmp_dir = get_string(&mut env, &tmp_dir);
    let projects_dir = get_string(&mut env, &projects_dir);
    engine().set_userland(
        Path::new(&proot),
        Path::new(&rootfs),
        Path::new(&tmp_dir),
        Path::new(&projects_dir),
    );
}

/// Forget the userland — after the user deletes the rootfs. Git status then
/// degrades to empty, as in a build that never had one.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_clearUserland(
    _env: JNIEnv,
    _class: JClass,
) {
    engine().clear_userland();
}

/// Generation counter for a project's git status; 0 until there is something
/// to show. Poll it exactly like `projectVersion`. Polling is also what
/// schedules refreshes — it never waits for git.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitStatusVersion(
    _env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jlong {
    engine().git_status_version(project_id as u64) as jlong
}

/// The branch record the project is on, from the cached status run, as JSON —
/// `{name, ahead, behind, unborn, upstream, upstream_gone}`, the same object
/// `gitChanges` nests — with no JSON of the changed files and no git run: the title bar's
/// drift arrows and the history views' reload keys ride the same half-second
/// poll the name does. Null when nothing is known: no repository, or no
/// completed run yet. A detached HEAD is a present object whose `name` is
/// null — on no branch, which is not the same answer as no repository.
/// Versioned by `gitStatusVersion`, like every other read of that cache.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitBranchInfo(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    match engine().git_branch(project_id as u64) {
        Some(branch) => {
            let json = serde_json::to_string(&branch).unwrap_or_else(|err| {
                log::warn!("gitBranchInfo failed to serialize: {err}");
                "{}".to_owned()
            });
            to_jstring(&env, json)
        }
        None => std::ptr::null_mut(),
    }
}

/// The commit HEAD points at, from the same cache — the staleness key for the
/// commit graph: history needs reloading when this moves, not on every status
/// change. Null when it is not known, which a caller must read as "assume it
/// moved", never as "nothing changed". Versioned by `gitStatusVersion`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitHead(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    match engine().git_head(project_id as u64) {
        Some(head) => to_jstring(&env, head),
        None => std::ptr::null_mut(),
    }
}

/// The whole status map as a JSON object of project-relative path to status
/// (`modified`, `added`, `deleted`, `renamed`, `conflicted`, `untracked`,
/// `ignored`). Ancestor directories are included, so the panel needs one
/// lookup per row. Reads a cache: never blocks, never null, `{}` when there is
/// nothing to show.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitStatus(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let statuses = engine().git_status(project_id as u64);
    let json = serde_json::to_string(&statuses).unwrap_or_else(|err| {
        log::warn!("gitStatus failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// Everything the git panel draws, as JSON: `scanned`, `has_repo`, `branch`
/// (`{name, ahead, behind, unborn, upstream, upstream_gone}` or null), `head`
/// (the commit id
/// it names, or null when unknown) and `entries`, each
/// `{path, staged, unstaged, conflicted, in_head}` with the two statuses using
/// the same names `gitStatus` does, or null.
///
/// Reads the same cache `gitStatus` does and is versioned by the same
/// `gitStatusVersion`: one `git status` serves the project panel and this.
/// Never blocks, never null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitChanges(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let changes = engine().git_changes(project_id as u64);
    let json = serde_json::to_string(&changes).unwrap_or_else(|err| {
        log::warn!("gitChanges failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// Paths from a JSON array, for the four commands below. An unparseable
/// argument is an empty list, which every command refuses.
fn path_list(env: &mut JNIEnv, paths_json: &JString) -> Vec<String> {
    let json = get_string(env, paths_json);
    serde_json::from_str(&json).unwrap_or_else(|err| {
        log::warn!("git command: {json:?} is not a path list: {err}");
        Vec::new()
    })
}

/// null when it worked, and the reason when it did not — usually git's own
/// sentence, which is the only thing that explains an unconfigured identity or
/// a merge in progress.
fn command_result(env: &JNIEnv, result: Result<(), String>) -> jstring {
    match result {
        Ok(()) => std::ptr::null_mut(),
        Err(message) => to_jstring(env, message),
    }
}

/// Stage every listed path (`git add -A`), deletions included. **Blocking**:
/// it waits for a process inside the guest — call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitStage(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    paths_json: JString,
) -> jstring {
    let paths = path_list(&mut env, &paths_json);
    command_result(&env, engine().git_stage(project_id as u64, &paths))
}

/// Take every listed path back out of the index. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitUnstage(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    paths_json: JString,
) -> jstring {
    let paths = path_list(&mut env, &paths_json);
    command_result(&env, engine().git_unstage(project_id as u64, &paths))
}

/// **Destructive.** Throw away every uncommitted change to the listed paths: a
/// path HEAD knows is restored to what HEAD has, and a path it does not —
/// untracked, or staged-new — is moved to the app's trash rather than deleted.
/// Confirm with the user, naming the files, before calling this. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitDiscard(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    paths_json: JString,
) -> jstring {
    let paths = path_list(&mut env, &paths_json);
    command_result(&env, engine().git_discard(project_id as u64, &paths))
}

/// Commit what is staged. An empty or whitespace-only message is refused here
/// rather than becoming an empty commit. The three flags are Zed's Amend,
/// Signoff and Skip Hooks menu entries, appended to the argv in Zed's order.
/// **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitCommit(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    message: JString,
    amend: jboolean,
    signoff: jboolean,
    no_verify: jboolean,
) -> jstring {
    let message = get_string(&mut env, &message);
    command_result(
        &env,
        engine().git_commit(
            project_id as u64,
            &message,
            amend != 0,
            signoff != 0,
            no_verify != 0,
        ),
    )
}

/// Undo the last commit, keeping its changes staged — exactly `git reset
/// --soft HEAD^`, Zed's Uncommit. Nothing here asks anything: read
/// `gitHeadPushedRemotes` and the old message *first*. Null when it worked.
/// **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitUncommit(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    command_result(&env, engine().git_uncommit(project_id as u64))
}

/// Every `remote/branch` that already holds HEAD, as JSON `{"remotes":[…]}` —
/// the uncommit confirmation's evidence that the commit was pushed. Empty for
/// nothing pushed *and* for a check git could not run, as in Zed, which
/// proceeds silently there; `{"error":…}` only when there is no repository to
/// ask. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitHeadPushedRemotes(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let json = match engine().git_head_pushed_remotes(project_id as u64) {
        Ok(remotes) => serde_json::json!({ "remotes": remotes }).to_string(),
        Err(error) => serde_json::json!({ "error": error }).to_string(),
    };
    to_jstring(&env, json)
}

/// Make the project a repository — the panel's "Initialize Repository". Runs
/// Zed's two commands: the guest's `init.defaultBranch` names the branch when
/// it is set, [fallback_branch] when it is not, then `git init -b <branch>`.
/// Null when it worked. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitInit(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    fallback_branch: JString,
) -> jstring {
    let fallback_branch = get_string(&mut env, &fallback_branch);
    command_result(&env, engine().git_init(project_id as u64, &fallback_branch))
}

/// Every local and remote-tracking branch, as JSON — `{"branches":[{name,
/// is_remote, is_head, sha, subject, committer_date, author, has_parent,
/// upstream, ahead, behind, upstream_gone}], "error":…}`. `error` is null
/// unless git could only list some of them, in which case the partial listing
/// is kept and the message rides beside it, as in Zed's picker banner.
/// `{"error":…}` alone when there is no repository at all. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitBranches(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let json = match engine().git_branches(project_id as u64) {
        Ok(list) => serde_json::to_string(&list)
            .unwrap_or_else(|_| "{\"error\":\"could not encode the branches\"}".to_owned()),
        Err(error) => serde_json::json!({ "error": error }).to_string(),
    };
    to_jstring(&env, json)
}

/// Check out a branch by the name `gitBranches` listed. A remote name
/// (`origin/feature`) grows a local tracking branch named after it first,
/// exactly as Zed does. Null when it worked, git's refusal — a dirty worktree
/// above all — when it did not. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitChangeBranch(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    name: JString,
) -> jstring {
    let name = get_string(&mut env, &name);
    command_result(&env, engine().git_change_branch(project_id as u64, &name))
}

/// Create a branch and switch to it — `git switch -c <name> [<base>]`. An
/// empty [base] branches off HEAD, which is what the picker's plain Create
/// does. Null when it worked. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitCreateBranch(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    name: JString,
    base: JString,
) -> jstring {
    let name = get_string(&mut env, &name);
    let base = get_string(&mut env, &base);
    let base = Some(base.as_str()).filter(|base| !base.is_empty());
    command_result(
        &env,
        engine().git_create_branch(project_id as u64, &name, base),
    )
}

/// Delete a branch — `git branch -d|-D|-dr|-Dr <name>`, Zed's flag table. An
/// unmerged branch comes back with git's "not fully merged", which is the
/// picker's cue to offer force. Null when it worked. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitDeleteBranch(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    name: JString,
    is_remote: jboolean,
    force: jboolean,
) -> jstring {
    let name = get_string(&mut env, &name);
    command_result(
        &env,
        engine().git_delete_branch(project_id as u64, &name, is_remote != 0, force != 0),
    )
}

/// The repository's default branch — Zed's chain: `upstream/HEAD`, then
/// `origin/HEAD`, then `init.defaultBranch` if that local branch exists, then
/// local `main`, then `master`. Null when nothing matches or git could not be
/// asked; the picker simply drops its "Create New From" entry then.
/// **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitDefaultBranch(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    match engine().git_default_branch(project_id as u64) {
        Ok(Some(branch)) => to_jstring(&env, branch),
        Ok(None) | Err(_) => std::ptr::null_mut(),
    }
}

/// What a remote command (fetch, pull, push) said, as JSON — never null:
/// `{"remote":…, "stdout":…, "stderr":…, "error":…}`. `remote` is the remote
/// it ran against (null for fetch-all), the two streams are git's own words —
/// which the UI formats into Zed's toasts, whose rules read the streams
/// *separately* — and `error` is null on success, the one-line reason
/// otherwise, with the streams still alongside for the log view.
fn remote_result(
    env: &JNIEnv,
    result: Result<engine::RemoteOutput, String>,
) -> jstring {
    let json = match result {
        Ok(output) => serde_json::json!({
            "remote": output.remote,
            "stdout": output.stdout,
            "stderr": output.stderr,
            "error": (!output.ok()).then(|| output.message()),
        })
        .to_string(),
        Err(error) => serde_json::json!({ "error": error }).to_string(),
    };
    to_jstring(env, json)
}

/// Every remote with its fetch URL, as JSON — `{"remotes":[{name, url}]}`,
/// in `git remote -v`'s (alphabetical) order, or `{"error":…}`. The Fetch
/// From / Push To pickers' listing, and where github.com detection reads the
/// URL. **Blocking**: it runs git.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitRemotes(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let json = match engine().git_remotes(project_id as u64) {
        Ok(remotes) => serde_json::json!({ "remotes": remotes }).to_string(),
        Err(error) => serde_json::json!({ "error": error }).to_string(),
    };
    to_jstring(&env, json)
}

/// The remote the branch is configured to push to ([is_push]) or pull from,
/// or null when none is — the caller's cue to list `gitRemotes` and ask,
/// Zed's `get_remote` flow. Null also when git could not be asked: a failed
/// *question* falls back to the listing rather than blocking the command.
/// **Blocking**: it runs git.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitBranchRemote(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    branch: JString,
    is_push: jboolean,
) -> jstring {
    let branch = get_string(&mut env, &branch);
    match engine().git_branch_remote(project_id as u64, &branch, is_push != 0) {
        Ok(Some(remote)) => to_jstring(&env, remote),
        Ok(None) | Err(_) => std::ptr::null_mut(),
    }
}

/// Fetch from one remote, or — [remote] empty — from all of them, Zed's
/// `git fetch --all`. Returns the remote-command JSON (see [remote_result]).
/// **Blocking**: it talks to the network.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitFetch(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    remote: JString,
) -> jstring {
    let remote = get_string(&mut env, &remote);
    let remote = Some(remote.as_str()).filter(|remote| !remote.is_empty());
    remote_result(&env, engine().git_fetch(project_id as u64, remote))
}

/// Pull [branch] from [remote], with `--rebase` when asked — Zed's Pull and
/// Pull (Rebase). The branch name joins the argv only when the branch has no
/// upstream. Returns the remote-command JSON. **Blocking**: network.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitPull(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    branch: JString,
    remote: JString,
    rebase: jboolean,
) -> jstring {
    let branch = get_string(&mut env, &branch);
    let remote = get_string(&mut env, &remote);
    remote_result(
        &env,
        engine().git_pull(project_id as u64, &branch, &remote, rebase != 0),
    )
}

/// Push [branch] to [remote] — with `--set-upstream` for Zed's Publish and
/// Republish, or `--force-with-lease` (never plain `--force`) for its Force
/// Push; force wins when both are set, as in Zed. Returns the remote-command
/// JSON. **Blocking**: it talks to the network.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitPush(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    branch: JString,
    remote: JString,
    set_upstream: jboolean,
    force: jboolean,
) -> jstring {
    let branch = get_string(&mut env, &branch);
    let remote = get_string(&mut env, &remote);
    remote_result(
        &env,
        engine().git_push(
            project_id as u64,
            &branch,
            &remote,
            set_upstream != 0,
            force != 0,
        ),
    )
}

/// What a git that may ask for a credential runs with, for the platform
/// layer's own clone: `{"env":["GIT_ASKPASS=…","SSH_ASKPASS=…",
/// "SSH_ASKPASS_REQUIRE=force"],"args":["-c","credential.helper=cache …"]}`.
/// Both lists are empty when no userland is configured. Cheap.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitAskpassSetup(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(&env, engine().git_askpass_setup_json())
}

/// The oldest credential prompt a running git or ssh is waiting on, as JSON
/// — `{id, prompt, kind, subject, masked, suggestion}` with `kind` one of
/// `username`, `password`, `passphrase`, `host_key`, `other` — or null when
/// none is pending. Poll it while a clone, fetch, pull or push is in flight;
/// it takes a lock and a `stat`, nothing more.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitPendingPrompt(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    match engine().git_pending_prompt() {
        Some(prompt) => match serde_json::to_string(&prompt) {
            Ok(json) => to_jstring(&env, json),
            Err(_) => std::ptr::null_mut(),
        },
        None => std::ptr::null_mut(),
    }
}

/// Answer prompt `id`. A username is remembered for the host for the
/// session regardless; a password or passphrase only when `remember`. False
/// when the prompt is no longer pending — answered, cancelled, or its git
/// gone.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitAnswerPrompt(
    mut env: JNIEnv,
    _class: JClass,
    id: jlong,
    answer: JString,
    remember: jboolean,
) -> jboolean {
    let answer = get_string(&mut env, &answer);
    if engine().git_answer_prompt(id as u64, &answer, remember != 0) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Refuse prompt `id`: the helper fails, and git or ssh gives up with its
/// own message. False when the prompt is no longer pending.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitCancelPrompt(
    _env: JNIEnv,
    _class: JClass,
    id: jlong,
) -> jboolean {
    if engine().git_cancel_prompt(id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Drop every username and secret remembered for the session.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitForgetCredentials(
    _env: JNIEnv,
    _class: JClass,
) {
    engine().git_forget_credentials();
}

/// The working tree's diff as a patch, as JSON — `{"files":[…]}` with each
/// file `{path, original, is_binary, hunks}` and each hunk
/// `{old_start, new_start, heading, lines:[{kind, text, old_line, new_line}]}`.
/// An empty `path` means every changed file. `{"error":…}` when git failed.
/// **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitPatch(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    path: JString,
    staged: jboolean,
) -> jstring {
    let path = get_string(&mut env, &path);
    let path = Some(path.as_str()).filter(|path| !path.is_empty());
    let json = match engine().git_patch(project_id as u64, path, staged != 0) {
        Ok(files) => serde_json::json!({ "files": files }).to_string(),
        Err(error) => serde_json::json!({ "error": error }).to_string(),
    };
    to_jstring(&env, json)
}

/// A page of commit history, newest first, as a JSON array of
/// `{sha, parents, author, author_email, author_time, subject, refs}`.
/// `all_refs` walks every branch, remote and tag in `--date-order` — the
/// graph's view; false is the plain HEAD walk the History tab shows.
/// `[]` for a repository with no commits; the error text when git failed.
/// **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitLog(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    limit: jlong,
    skip: jlong,
    all_refs: jboolean,
) -> jstring {
    let json = match engine().git_log(
        project_id as u64,
        limit as u32,
        skip.max(0) as u32,
        all_refs != 0,
    ) {
        Ok(commits) => serde_json::json!({ "commits": commits }).to_string(),
        Err(error) => serde_json::json!({ "error": error }).to_string(),
    };
    to_jstring(&env, json)
}

/// The branch's changes since it left `base` — the merge-base diff behind the
/// panel's "View Branch Diff", in `gitPatch`'s JSON shape: `{"files":[…]}` or
/// `{"error":…}`. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitBranchPatch(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    base: JString,
) -> jstring {
    let base = get_string(&mut env, &base);
    let json = match engine().git_branch_patch(project_id as u64, &base) {
        Ok(files) => serde_json::json!({ "files": files }).to_string(),
        Err(error) => serde_json::json!({ "error": error }).to_string(),
    };
    to_jstring(&env, json)
}

/// What one commit changed against its first parent, in `gitPatch`'s JSON
/// shape — `{"files":[…]}` or `{"error":…}`. An empty `path` is the whole
/// commit; a path narrows it to one file. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitCommitPatch(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    sha: JString,
    path: JString,
) -> jstring {
    let sha = get_string(&mut env, &sha);
    let path = get_string(&mut env, &path);
    let path = Some(path.as_str()).filter(|path| !path.is_empty());
    let json = match engine().git_commit_patch(project_id as u64, &sha, path) {
        Ok(files) => serde_json::json!({ "files": files }).to_string(),
        Err(error) => serde_json::json!({ "error": error }).to_string(),
    };
    to_jstring(&env, json)
}

/// One commit in full: the fields above plus `message` and `files`, each
/// `{status, path, original}`. `{"error":…}` when git could not read it.
/// **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitCommitDetails(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    sha: JString,
) -> jstring {
    let sha = get_string(&mut env, &sha);
    let json = match engine().git_commit_details(project_id as u64, &sha) {
        Ok(details) => serde_json::to_string(&details)
            .unwrap_or_else(|_| "{\"error\":\"could not encode that commit\"}".to_owned()),
        Err(error) => serde_json::json!({ "error": error }).to_string(),
    };
    to_jstring(&env, json)
}

/// Who commits are recorded as, as JSON `{"name":…,"email":…}` — both empty
/// when git has none, which is a fresh Debian's state and the reason every
/// commit in one fails. `{}` when there is no repository. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitIdentity(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let json = match engine().git_identity(project_id as u64) {
        Ok((name, email)) => serde_json::json!({ "name": name, "email": email }).to_string(),
        Err(_) => "{}".to_owned(),
    };
    to_jstring(&env, json)
}

/// Set that identity in the guest's global git config. Null when it worked and
/// the reason when it did not, like the other write commands. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitSetIdentity(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    name: JString,
    email: JString,
) -> jstring {
    let name = get_string(&mut env, &name);
    let email = get_string(&mut env, &email);
    command_result(
        &env,
        engine().git_set_identity(project_id as u64, &name, &email),
    )
}

/// Generation counter for a buffer's diff hunks; 0 until there is something to
/// show. Poll it exactly like `gitStatusVersion` — polling schedules the work
/// and never waits for it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitHunksVersion(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    engine().git_hunks_version(buffer_id as u64) as jlong
}

/// The buffer's diff against HEAD, flattened as groups of five ints: kind
/// (0 added, 1 modified, 2 deleted), first row, end row (exclusive), how
/// many rows HEAD had there, and the row those sat on in HEAD.
///
/// Rows are *buffer* rows and track unsaved edits: the base text comes from
/// git, the diff is computed here against the live buffer. A deletion occupies
/// no rows, so its first and end row are equal and mark the boundary the rows
/// were removed from.
///
/// Reads a cache: never blocks, never null, empty for a buffer with no file,
/// no repository, or no difference.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitHunks(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jintArray {
    let hunks = engine().git_hunks(buffer_id as u64);
    let mut flat = Vec::with_capacity(hunks.len() * 5);
    for hunk in &hunks {
        flat.push(hunk_kind_code(hunk.kind));
        flat.push(hunk.start_row as i32);
        flat.push(hunk.end_row as i32);
        flat.push(hunk.old_rows as i32);
        flat.push(hunk.old_start as i32);
    }
    let array = env
        .new_int_array(flat.len() as i32)
        .expect("failed to allocate hunk array");
    env.set_int_array_region(&array, 0, &flat)
        .expect("failed to fill hunk array");
    array.into_raw()
}

/// The int the Kotlin `GitHunkKind` enum is indexed by, in its order.
fn hunk_kind_code(kind: engine::HunkKind) -> i32 {
    match kind {
        engine::HunkKind::Added => 0,
        engine::HunkKind::Modified => 1,
        engine::HunkKind::Deleted => 2,
    }
}

/// The rows HEAD had where a hunk now is — the deleted lines an expanded hunk
/// draws — as a JSON array of strings, or null while the base text is still
/// being fetched. A cache read: never runs git.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitHunkBaseLines(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    old_start: jlong,
    old_rows: jlong,
) -> jstring {
    match engine().git_hunk_base_lines(buffer_id as u64, old_start as u32, old_rows as u32) {
        Some(lines) => to_jstring(&env, serde_json::json!(lines).to_string()),
        None => std::ptr::null_mut(),
    }
}

/// The hunk states as JSON: `{"hunks": [{kind, start_row, end_row, old_rows,
/// old_start, staged}]}` or `{"error": …}`. Kind is the same int `gitHunks`
/// uses. **Blocking** — one `git show` of the index; ask when hunks are
/// expanded and when the git generation moved, never per frame.
fn hunk_states_json(result: Result<Vec<engine::HunkState>, String>) -> String {
    match result {
        Ok(states) => {
            let hunks: Vec<serde_json::Value> = states
                .iter()
                .map(|state| {
                    serde_json::json!({
                        "kind": hunk_kind_code(state.hunk.kind),
                        "start_row": state.hunk.start_row,
                        "end_row": state.hunk.end_row,
                        "old_rows": state.hunk.old_rows,
                        "old_start": state.hunk.old_start,
                        "staged": state.staged,
                    })
                })
                .collect();
            serde_json::json!({ "hunks": hunks }).to_string()
        }
        Err(error) => serde_json::json!({ "error": error }).to_string(),
    }
}

/// See [`hunk_states_json`]. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitHunkStates(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    to_jstring(&env, hunk_states_json(engine().git_hunk_states(buffer_id as u64)))
}

/// Stage (`stage` true) or unstage every hunk touching buffer rows
/// `[startRow, endRow)` — `git apply --cached` of a one-hunk patch. Null when
/// it worked, git's sentence when not. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitHunkStage(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    start_row: jlong,
    end_row: jlong,
    stage: jboolean,
) -> jstring {
    command_result(
        &env,
        engine().git_hunk_stage(
            buffer_id as u64,
            start_row as u32..end_row as u32,
            stage == JNI_TRUE,
        ),
    )
}

/// Put HEAD's rows back over every hunk touching buffer rows
/// `[startRow, endRow)` — an undoable edit of the buffer; the caller resyncs
/// its editor afterwards. Null when it worked. Runs git only if the base text
/// is not cached yet.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitHunkRestore(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    start_row: jlong,
    end_row: jlong,
) -> jstring {
    command_result(
        &env,
        engine().git_hunk_restore(buffer_id as u64, start_row as u32..end_row as u32),
    )
}

/// `gitHunkStates` for a project-relative path — the project diff's route.
/// **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitPathHunkStates(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    path: JString,
) -> jstring {
    let path = get_string(&mut env, &path);
    to_jstring(
        &env,
        hunk_states_json(engine().git_path_hunk_states(project_id as u64, &path)),
    )
}

/// `gitHunkStage` by path; rows are rows of the file as it is now (the `+`
/// side of the patch), 0-based. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitPathHunkStage(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    path: JString,
    start_row: jlong,
    end_row: jlong,
    stage: jboolean,
) -> jstring {
    let path = get_string(&mut env, &path);
    command_result(
        &env,
        engine().git_path_hunk_stage(
            project_id as u64,
            &path,
            start_row as u32..end_row as u32,
            stage == JNI_TRUE,
        ),
    )
}

/// `gitHunkRestore` by path: an edit of the open buffer when there is one,
/// else the file on disk. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitPathHunkRestore(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    path: JString,
    start_row: jlong,
    end_row: jlong,
) -> jstring {
    let path = get_string(&mut env, &path);
    command_result(
        &env,
        engine().git_path_hunk_restore(
            project_id as u64,
            &path,
            start_row as u32..end_row as u32,
        ),
    )
}

/// `git stash list` as JSON: `{"entries": [{index, sha, message, branch,
/// timestamp}]}`, newest first, or `{"error": …}`. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitStashList(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let json = match engine().git_stash_list(project_id as u64) {
        Ok(entries) => serde_json::json!({ "entries": entries }).to_string(),
        Err(error) => serde_json::json!({ "error": error }).to_string(),
    };
    to_jstring(&env, json)
}

/// `git stash push`: `kind` is 0 all (untracked included), 1 tracked, 2
/// staged; an empty message means none. Null when it worked. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitStashPush(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    kind: jint,
    message: JString,
) -> jstring {
    let message = get_string(&mut env, &message);
    let message = Some(message.as_str()).filter(|message| !message.trim().is_empty());
    command_result(
        &env,
        engine().git_stash_push(project_id as u64, engine::StashKind::from_code(kind), message),
    )
}

/// The `stash@{N}` a negative index means: none, which is git's "the latest".
fn stash_index(index: jlong) -> Option<usize> {
    usize::try_from(index).ok()
}

/// `git stash pop [stash@{N}]`; `index` < 0 pops the latest. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitStashPop(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    index: jlong,
) -> jstring {
    command_result(&env, engine().git_stash_pop(project_id as u64, stash_index(index)))
}

/// `git stash apply [stash@{N}]`. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitStashApply(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    index: jlong,
) -> jstring {
    command_result(&env, engine().git_stash_apply(project_id as u64, stash_index(index)))
}

/// `git stash drop [stash@{N}]`. **Blocking**.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitStashDrop(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    index: jlong,
) -> jstring {
    command_result(&env, engine().git_stash_drop(project_id as u64, stash_index(index)))
}

/// The merge-conflict regions in a buffer, as a JSON array of
/// `{ours_branch_name, theirs_branch_name, range, ours, theirs, base,
/// start_row, end_row, ours_rows, base_rows, theirs_rows}` — offsets are
/// bytes, rows are 0-based and every range is `{start, end}` half-open. Zed's
/// `ConflictSet::parse` over the live text; see engine/src/git_conflict.rs.
///
/// Reads the whole buffer under its lock and scans it once — linear, and
/// worth asking again only when `bufferVersion` has moved. `[]` for a buffer
/// with no markers; null for an unknown buffer.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferConflicts(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    match engine().conflicts(buffer_id as u64) {
        Ok(regions) => {
            let json = serde_json::to_string(&regions).unwrap_or_else(|err| {
                log::warn!("bufferConflicts failed to serialize: {err}");
                "[]".to_owned()
            });
            to_jstring(&env, json)
        }
        Err(err) => {
            log::warn!("bufferConflicts failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Resolve the conflict whose `<<<<<<<` line is `start_row`, keeping ours,
/// theirs or both — Zed's "Use HEAD" / "Use <branch>" / "Use Both". One edit,
/// one undo step. Returns the buffer version it produced, or -1 when the row
/// no longer opens a conflict (the buffer moved under the UI) or the buffer
/// is unknown; nothing changes then.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_resolveConflict(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    start_row: jlong,
    keep_ours: jboolean,
    keep_theirs: jboolean,
) -> jlong {
    if start_row < 0 {
        return -1;
    }
    let keep = engine::ConflictKeep {
        ours: keep_ours != JNI_FALSE,
        theirs: keep_theirs != JNI_FALSE,
    };
    match engine().resolve_conflict(buffer_id as u64, start_row as u32, keep) {
        Ok(version) => version as jlong,
        Err(err) => {
            log::warn!("resolveConflict failed: {err}");
            -1
        }
    }
}

/// Who last touched each run of rows, as JSON: `{"entries": [{sha, start_row,
/// row_count, author, author_time, summary}]}`, or `{"error": "…"}`.
///
/// Rows are the rows of the file **on disk**, not of the buffer: git blames
/// what it can read, and a buffer with unsaved edits has drifted from it.
///
/// **Blocking**, and uncached — it runs git every time. Call it when the user
/// asks for blame, off the main thread, not on a poll loop.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_gitBlame(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    let json = match engine().git_blame(buffer_id as u64) {
        Ok(entries) => serde_json::json!({ "entries": entries }),
        Err(message) => serde_json::json!({ "error": message }),
    };
    to_jstring(&env, json.to_string())
}

// ---------------------------------------------------------------------------
// Settings. The file is JSONC and hand-editable; writes are surgical so
// comments survive. All of these touch the filesystem — call them off the
// main thread.
// ---------------------------------------------------------------------------

/// Resolved settings as JSON. Falls back to defaults if the file is broken;
/// pair it with `settingsAreValid` to tell the user rather than silently
/// showing settings that aren't in effect.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_settings(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let settings = engine().settings();
    let json = serde_json::to_string(&settings).unwrap_or_else(|err| {
        log::warn!("settings failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// The settings file's raw JSONC text, created with documented defaults on
/// first use.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_settingsText(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(&env, engine().settings_text())
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_settingsAreValid(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if engine().settings_are_valid() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Set one setting. `key_path` is dot-separated (`project_panel.show_ignored`)
/// and `value_json` is the new value as JSON (`true`, `18`, `"dark"`).
/// Returns the resolved settings as JSON, or null if the write failed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_setSetting(
    mut env: JNIEnv,
    _class: JClass,
    key_path: JString,
    value_json: JString,
) -> jstring {
    let key_path = get_string(&mut env, &key_path);
    let value_json = get_string(&mut env, &value_json);
    let value: serde_json::Value = match serde_json::from_str(&value_json) {
        Ok(value) => value,
        Err(err) => {
            log::warn!("setSetting: {value_json:?} is not JSON: {err}");
            return std::ptr::null_mut();
        }
    };
    let keys: Vec<&str> = key_path.split('.').collect();
    match engine().set_setting(&keys, value) {
        Ok(settings) => match serde_json::to_string(&settings) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("setSetting failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            log::warn!("setSetting failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Add or replace one `agent_servers` entry. `name` is the entry's key,
/// verbatim — not dot-split like `setSetting`'s path, so a name containing a
/// dot stays one key — and `spec_json` is a `CustomAgent`:
/// `{"command": …, "args": […], "env": {…}}`. Returns the resolved settings
/// as JSON, or null on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_setAgentServer(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    spec_json: JString,
) -> jstring {
    let name = get_string(&mut env, &name);
    let spec_json = get_string(&mut env, &spec_json);
    let agent: engine::CustomAgent = match serde_json::from_str(&spec_json) {
        Ok(agent) => agent,
        Err(err) => {
            log::warn!("setAgentServer: {spec_json:?} is not an agent spec: {err}");
            return std::ptr::null_mut();
        }
    };
    match engine().set_agent_server(&name, agent) {
        Ok(settings) => match serde_json::to_string(&settings) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("setAgentServer failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            log::warn!("setAgentServer failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Remove one `agent_servers` entry by name. Removing a name that is not
/// there succeeds. Returns the resolved settings as JSON, or null on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_removeAgentServer(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jstring {
    let name = get_string(&mut env, &name);
    match engine().remove_agent_server(&name) {
        Ok(settings) => match serde_json::to_string(&settings) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("removeAgentServer failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            log::warn!("removeAgentServer failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Add or replace one `context_servers` entry — Zed's MCP context-server
/// shape: `{"command": …, "args": […], "env": {…}}` for a stdio server, or
/// `{"url": …, "headers": {…}}` for an HTTP one. Returns the resolved
/// settings as JSON, or null when the spec is not one of those shapes or the
/// write failed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_setContextServer(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
    spec_json: JString,
) -> jstring {
    let name = get_string(&mut env, &name);
    let spec_json = get_string(&mut env, &spec_json);
    let server: engine::ContextServer = match serde_json::from_str(&spec_json) {
        Ok(server) => server,
        Err(err) => {
            log::warn!("setContextServer: {spec_json:?} is not a context server: {err}");
            return std::ptr::null_mut();
        }
    };
    match engine().set_context_server(&name, server) {
        Ok(settings) => match serde_json::to_string(&settings) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("setContextServer failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            log::warn!("setContextServer failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Remove one `context_servers` entry by name; a name that is not there
/// succeeds. Returns the resolved settings as JSON, or null on failure.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_removeContextServer(
    mut env: JNIEnv,
    _class: JClass,
    name: JString,
) -> jstring {
    let name = get_string(&mut env, &name);
    match engine().remove_context_server(&name) {
        Ok(settings) => match serde_json::to_string(&settings) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("removeContextServer failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            log::warn!("removeContextServer failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Replace the whole settings file. Returns the resolved settings as JSON, or
/// null if the text doesn't parse — in which case the file is left untouched.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_setSettingsText(
    mut env: JNIEnv,
    _class: JClass,
    text: JString,
) -> jstring {
    let text = get_string(&mut env, &text);
    match engine().set_settings_text(&text) {
        Ok(settings) => match serde_json::to_string(&settings) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("setSettingsText failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            log::warn!("setSettingsText failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// The built-in default settings as documented JSONC text — what
/// `zed::OpenDefaultSettings` shows read-only. Never touches the disk.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_defaultSettingsText(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(&env, engine().default_settings_text().to_owned())
}

/// The settings in force for one buffer, every layer resolved — the user
/// file, the project's `.zed/settings.json`, and the `languages` entry for
/// the buffer's language in each — as JSON: `tab_size`, `hard_tabs`,
/// `soft_wrap`, `preferred_line_length`, `wrap_guides`, `format_on_save`,
/// `formatter`, `code_actions_on_format`, `enable_language_server`,
/// `inline_blame`. Never null; an unknown buffer gets the user's settings.
/// **Blocking** (reads settings.json): call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferLanguageSettings(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    let settings = engine().buffer_language_settings(buffer_id as u64);
    let json = serde_json::to_string(&settings).unwrap_or_else(|err| {
        log::warn!("bufferLanguageSettings failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// Monotonic counter for a project's `.zed/settings.json`: bumped when the
/// project opens and whenever the file changes on disk. Poll it, like
/// `projectVersion`, to know when to re-read `bufferLanguageSettings`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectSettingsVersion(
    _env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jlong {
    engine().project_settings_version(project_id as u64) as jlong
}

/// Why the project's `.zed/settings.json` is not in effect — its parse
/// error — or null when it is, or there is none.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectSettingsError(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    match engine().project_settings_error(project_id as u64) {
        Some(error) => to_jstring(&env, error),
        None => std::ptr::null_mut(),
    }
}

/// Re-read `.zed/settings.json` now — after the editor saved it itself,
/// so the save and its effect arrive together rather than a watcher tick
/// apart. False for an unknown project. **Blocking**: off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_reloadProjectSettings(
    _env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jboolean {
    if engine().reload_project_settings(project_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Run the buffer's `code_actions_on_format` kinds through its server and
/// hold their edits; same polling contract as `lspRequestFormatting`, and
/// `lspApplyPendingEdit` lands them in order. Settles `done` with zero edits
/// when nothing is configured.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestCodeActionsOnFormat(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    engine().lsp_request_code_actions_on_format(buffer_id as u64) as jlong
}

/// Run the buffer's external `formatter` in the userland with the buffer on
/// stdin and replace the buffer with its stdout. JSON `{changed, error}`;
/// `error` is a sentence for the status bar, null on success. **Blocking**
/// for as long as the program runs: call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_formatBufferExternally(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    let outcome = engine().format_buffer_externally(buffer_id as u64);
    let json = serde_json::to_string(&outcome).unwrap_or_else(|err| {
        log::warn!("formatBufferExternally failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

// ---------------------------------------------------------------------------
// Keymap. Zed's keymap.json, next to settings.json. The engine parses and
// layers it; the app decides what the names mean. Both touch the filesystem
// — call them off the main thread.
// ---------------------------------------------------------------------------

/// The keymap file's raw JSONC text, created with a commented starter on
/// first use — so "open keymap" always has a file to open.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_keymapText(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(&env, engine().keymap_text())
}

/// The resolved keymap: the app's defaults (`default_keymap_json`, in
/// keymap-file form — the `WorkspaceCommand` table is the source of truth
/// for the action names), then the base keymap `settings.json` names, then
/// the user's file. Returns `{"bindings": [{context, keystrokes, action,
/// args, source}…], "errors": [sentence…]}`; later bindings outrank earlier
/// ones at the same context depth. Never null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_loadKeymap(
    mut env: JNIEnv,
    _class: JClass,
    default_keymap_json: JString,
) -> jstring {
    let defaults = get_string(&mut env, &default_keymap_json);
    let load = engine().load_keymap(&defaults);
    let json = serde_json::to_string(&load).unwrap_or_else(|err| {
        log::warn!("loadKeymap failed to serialize: {err}");
        "{\"bindings\":[],\"errors\":[]}".to_owned()
    });
    to_jstring(&env, json)
}

/// Fuzzy-match `query` against the project's files, best first, as a JSON
/// array of objects with `path`, `name`, `positions` (UTF-16 offsets into
/// `path`) and `score`. An empty query lists files. Never null: unknown
/// projects give `[]`. **Blocking**: call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectFindFiles(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    query: JString,
    limit: jlong,
) -> jstring {
    let query = get_string(&mut env, &query);
    let limit = limit.clamp(0, 1000) as usize;
    let matches = engine().find_files(project_id as u64, &query, limit);
    let json = serde_json::to_string(&matches).unwrap_or_else(|err| {
        log::warn!("projectFindFiles failed to serialize: {err}");
        "[]".to_owned()
    });
    to_jstring(&env, json)
}

/// Read a file into a new buffer, with the language chosen from its name.
/// Returns the buffer id, or -1 if the file could not be read (missing,
/// unreadable, or not UTF-8). **Blocking**: call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_openFile(
    mut env: JNIEnv,
    _class: JClass,
    path: JString,
) -> jlong {
    let path = get_string(&mut env, &path);
    match engine().open_file(Path::new(&path)) {
        Ok(id) => id as jlong,
        Err(err) => {
            log::warn!("openFile failed: {err}");
            -1
        }
    }
}

/// Bumped when a background reparse lands. The content version doesn't move
/// then, so the UI watches this to know its highlight spans are stale.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferHighlightVersion(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    engine().buffer_highlight_version(buffer_id as u64) as jlong
}

/// The grammar the buffer is highlighted with ("rust", "markdown"), or null
/// if it has no language.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferLanguage(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    match engine().buffer_language(buffer_id as u64) {
        Some(name) => to_jstring(&env, name.to_owned()),
        None => std::ptr::null_mut(),
    }
}

/// A language's whole editing config as JSON, straight from the grammar's own
/// `config.toml` — comment tokens, bracket pairs with their `close`,
/// `surround`, `newline` and `not_in` flags, `autoclose_before`, `hard_tabs`
/// and `increase_indent_pattern`. Null for a grammar we do not carry.
///
/// One call per language, for the life of the process: the answer is the same
/// for every buffer in it, and the UI caches it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_languageConfig(
    mut env: JNIEnv,
    _class: JClass,
    language: JString,
) -> jstring {
    let language = get_string(&mut env, &language);
    match engine::language_config_json(&language) {
        Some(json) => to_jstring(&env, json.to_owned()),
        None => std::ptr::null_mut(),
    }
}

/// For each byte offset, a bitmask of the bracket pairs live there — bit *i*
/// for pair *i* of `languageConfig`'s `brackets`. This is what
/// `not_in = ["string", "comment"]` needs: the tree-sitter scope at the caret,
/// which only the engine has.
///
/// Every bit is set for a buffer with no language, for an unknown buffer, and
/// for a language whose pairs are all unconditional — so the UI must only ask
/// when the pair it is about to insert actually carries a `not_in`, which no
/// plain bracket does.
///
/// It reparses the buffer if the tree is stale, so it takes every caret's
/// offset in one call and belongs on the pair-character path, not the typing
/// path.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferBracketScopes<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    buffer_id: jlong,
    offsets: JLongArray<'local>,
) -> jlongArray {
    let count = env.get_array_length(&offsets).unwrap_or(0).max(0) as usize;
    let mut raw = vec![0 as jlong; count];
    // Never null and never short: the caller indexes this array by caret, and
    // "everything is live" is the answer that leaves autoclose as it was.
    let all_live = || vec![u64::MAX as jlong; count];
    let flat = if count > 0 && env.get_long_array_region(&offsets, 0, &mut raw).is_err() {
        log::warn!("bufferBracketScopes: could not read the offsets");
        all_live()
    } else {
        let offsets: Vec<usize> = raw.iter().map(|&at| at.max(0) as usize).collect();
        match engine().bracket_scopes(buffer_id as u64, &offsets) {
            Ok(masks) => masks.into_iter().map(|mask| mask as jlong).collect(),
            Err(err) => {
                log::warn!("bufferBracketScopes failed: {err}");
                all_live()
            }
        }
    };
    let array = env
        .new_long_array(flat.len() as i32)
        .expect("failed to allocate bracket-scope array");
    env.set_long_array_region(&array, 0, &flat)
        .expect("failed to fill bracket-scope array");
    array.into_raw()
}

/// Absolute path of the file behind a buffer; null for scratch buffers.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferPath(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    match engine().buffer_path(buffer_id as u64) {
        Some(path) => to_jstring(&env, path.to_string_lossy().into_owned()),
        None => std::ptr::null_mut(),
    }
}

/// Whether the buffer has edits not yet written to disk.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferIsDirty(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jboolean {
    if engine().buffer_is_dirty(buffer_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Whether the file changed on disk since the buffer last synced with it.
/// Set by the worktree's watcher; cleared by save or reload.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferHasDiskChange(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jboolean {
    if engine().buffer_has_disk_change(buffer_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Whether the file behind the buffer has been deleted from disk.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferFileDeleted(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jboolean {
    if engine().buffer_file_deleted(buffer_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Write the buffer to its file. Returns the version now on disk, or -1 if
/// the buffer has no file or the write failed. **Blocking**: call it off the
/// main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_saveBuffer(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    match engine().save_buffer(buffer_id as u64) {
        Ok(version) => version as jlong,
        Err(err) => {
            log::warn!("saveBuffer failed: {err}");
            -1
        }
    }
}

/// Re-read the file into the buffer, discarding local edits (undoably).
/// Returns the new version, or -1. **Blocking**: call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_reloadBuffer(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    match engine().reload_buffer(buffer_id as u64) {
        Ok(version) => version as jlong,
        Err(err) => {
            log::warn!("reloadBuffer failed: {err}");
            -1
        }
    }
}

// ---------------------------------------------------------------------------
// The shape of the file behind a buffer: its line ending and its encoding.
// The buffer itself is always UTF-8 with `\n`; these say how it is written
// back (engine/src/encoding.rs). Line endings travel as "lf" / "crlf",
// encodings as their WHATWG names ("UTF-8", "UTF-16LE", "windows-1252") —
// the labels the status bar shows are the UI's business.

/// "lf" or "crlf" — the line ending the buffer's file uses and the next save
/// writes. Null for a buffer with no file.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferLineEnding(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    match engine().buffer_line_ending(buffer_id as u64) {
        Some(line_ending) => to_jstring(&env, line_ending_name(line_ending).to_owned()),
        None => std::ptr::null_mut(),
    }
}

/// Choose the line ending the next save writes ("lf" or "crlf"). A change
/// marks the buffer dirty; the text is untouched. False for a buffer with no
/// file or a name that is neither.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_setBufferLineEnding(
    mut env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    line_ending: JString,
) -> jboolean {
    let name = get_string(&mut env, &line_ending);
    let Some(line_ending) = line_ending_named(&name) else {
        log::warn!("setBufferLineEnding: unknown line ending {name:?}");
        return JNI_FALSE;
    };
    match engine().set_buffer_line_ending(buffer_id as u64, line_ending) {
        Ok(()) => JNI_TRUE,
        Err(err) => {
            log::warn!("setBufferLineEnding failed: {err}");
            JNI_FALSE
        }
    }
}

fn line_ending_name(line_ending: engine::LineEnding) -> &'static str {
    match line_ending {
        engine::LineEnding::Unix => "lf",
        engine::LineEnding::Windows => "crlf",
    }
}

fn line_ending_named(name: &str) -> Option<engine::LineEnding> {
    match name {
        "lf" => Some(engine::LineEnding::Unix),
        "crlf" => Some(engine::LineEnding::Windows),
        _ => None,
    }
}

/// The encoding the buffer's file is read and written in, as JSON:
///
///     {"name": "UTF-8", "bom": true}
///
/// Null for a buffer with no file.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferEncoding(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    match engine().buffer_encoding(buffer_id as u64) {
        Some((encoding, has_bom)) => {
            let json = serde_json::json!({"name": encoding.name(), "bom": has_bom});
            to_jstring(&env, json.to_string())
        }
        None => std::ptr::null_mut(),
    }
}

/// Every encoding the picker may offer, as a JSON array of names sorted the
/// way Zed sorts them: `["Big5", "EUC-JP", …, "windows-874"]`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_availableEncodings(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let names: Vec<&str> = engine::available_encodings()
        .into_iter()
        .map(|encoding| encoding.name())
        .collect();
    to_jstring(&env, serde_json::json!(names).to_string())
}

/// Choose the encoding the next save writes, keeping the text as it is. A
/// change marks the buffer dirty. `bom` only means anything for UTF-8 and
/// UTF-16. False for a buffer with no file or a name not in
/// [availableEncodings].
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_setBufferEncoding(
    mut env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    encoding: JString,
    bom: jboolean,
) -> jboolean {
    let name = get_string(&mut env, &encoding);
    let Some(encoding) = engine::encoding_named(&name) else {
        log::warn!("setBufferEncoding: unknown encoding {name:?}");
        return JNI_FALSE;
    };
    match engine().set_buffer_encoding(buffer_id as u64, encoding, bom == JNI_TRUE) {
        Ok(()) => JNI_TRUE,
        Err(err) => {
            log::warn!("setBufferEncoding failed: {err}");
            JNI_FALSE
        }
    }
}

/// Re-read the file decoded as `encoding` — "reopen with encoding". Local
/// edits are discarded, undoably, as with [reloadBuffer]; the buffer then
/// saves in that encoding. Returns the new version, or -1. **Blocking**:
/// call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_reopenBufferWithEncoding(
    mut env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    encoding: JString,
) -> jlong {
    let name = get_string(&mut env, &encoding);
    let Some(encoding) = engine::encoding_named(&name) else {
        log::warn!("reopenBufferWithEncoding: unknown encoding {name:?}");
        return -1;
    };
    match engine().reload_buffer_with_encoding(buffer_id as u64, encoding) {
        Ok(version) => version as jlong,
        Err(err) => {
            log::warn!("reopenBufferWithEncoding failed: {err}");
            -1
        }
    }
}

// ---------------------------------------------------------------------------
// Search. Both searches take the same options object, as JSON, so one search
// bar can drive either without reshaping its state:
//
//     {"query": "needle", "regex": false, "case_sensitive": false,
//      "whole_word": false, "include_ignored": false,
//      "include_globs": [], "exclude_globs": []}
//
// Every field may be omitted. The last three are project-search only.
//
// `whole_word` means one thing for every kind of query: a hit counts only when
// neither neighbouring character is a word character (`alphanumeric || '_'`).
// A regex is filtered on where its match landed, never rewritten.
//
// Buffer search answers on the calling thread because it is a single pass over
// a rope — milliseconds on a 100k-line file, which is what lets the search bar
// re-run it on every keystroke of the query. Project search cannot answer at
// all: it reads thousands of files, so it runs on a thread of its own and
// publishes a generation counter to poll, the same shape as `gitStatusVersion`.
//
// Project search silently skips four kinds of file: unreadable ones, ones over
// 4 MiB, ones holding a NUL byte anywhere, and ones that are not valid UTF-8.
// They are counted in `files_searched` but can never produce a hit.
// ---------------------------------------------------------------------------

/// Why a query will not compile, or null if it will. The search bar calls this
/// to explain a half-typed regex rather than silently showing nothing.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_searchQueryError(
    mut env: JNIEnv,
    _class: JClass,
    query_json: JString,
) -> jstring {
    let query_json = get_string(&mut env, &query_json);
    match serde_json::from_str(&query_json) {
        Ok(options) => match engine().search_query_error(&options) {
            Some(error) => to_jstring(&env, error),
            None => std::ptr::null_mut(),
        },
        Err(err) => to_jstring(&env, err.to_string()),
    }
}

/// Every match in a buffer, as longs: element 0 is the total number of matches
/// in the buffer, and the rest are groups of four — byte start, byte end, row,
/// byte column. The total can exceed the groups present, which is how the
/// caller knows `limit` bit; the group layout is a primitive array copy rather
/// than JSON because this runs on the keystroke path.
///
/// Null for an unknown buffer or a query that does not compile — ask
/// `searchQueryError` which it was.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferSearch(
    mut env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    query_json: JString,
    limit: jlong,
) -> jlongArray {
    let query_json = get_string(&mut env, &query_json);
    let options = match serde_json::from_str(&query_json) {
        Ok(options) => options,
        Err(err) => {
            log::warn!("bufferSearch: {query_json:?} is not a query: {err}");
            return std::ptr::null_mut();
        }
    };
    let limit = limit.clamp(0, MAX_BUFFER_MATCHES) as usize;
    let found = match engine().search_buffer(buffer_id as u64, &options, limit) {
        Ok(found) => found,
        Err(err) => {
            log::warn!("bufferSearch failed: {err}");
            return std::ptr::null_mut();
        }
    };

    let mut flat = Vec::with_capacity(1 + found.matches.len() * 4);
    flat.push(found.total as jlong);
    for found in &found.matches {
        flat.push(found.start as jlong);
        flat.push(found.end as jlong);
        flat.push(found.row as jlong);
        flat.push(found.column as jlong);
    }
    let array = env
        .new_long_array(flat.len() as i32)
        .expect("failed to allocate search array");
    env.set_long_array_region(&array, 0, &flat)
        .expect("failed to fill search array");
    array.into_raw()
}

/// The most matches `bufferSearch` will hand back at once. Ten thousand is
/// Zed's own cap, and 320 KB of longs is already more than any UI will draw.
const MAX_BUFFER_MATCHES: jlong = 10_000;

/// Replace the first hit at or after byte offset `from` — wrapping to the
/// first in the buffer — with `replacement`, in one call. Answers three longs:
/// the buffer's new version, how many hits were rewritten (0 or 1), and the
/// byte offset just past the rewritten text, where the bar should look for
/// the next hit. Null for an unknown buffer or a query that does not compile.
///
/// The replacement is expanded per hit as Zed expands it: verbatim for a
/// literal query; for a regex, `$1`/`$name`/`${name}` are capture groups and
/// `\n`, `\t`, `\\` are a newline, a tab and a backslash. The case, word and
/// regex options apply exactly as they do to the search.
///
/// Answers on the calling thread — one scan of the buffer and one edit —
/// which is fine for a file the search bar was already scanning per keystroke.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferReplaceNext(
    mut env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    query_json: JString,
    replacement: JString,
    from: jlong,
) -> jlongArray {
    let query_json = get_string(&mut env, &query_json);
    let replacement = get_string(&mut env, &replacement);
    let options = match serde_json::from_str(&query_json) {
        Ok(options) => options,
        Err(err) => {
            log::warn!("bufferReplaceNext: {query_json:?} is not a query: {err}");
            return std::ptr::null_mut();
        }
    };
    let outcome = engine().replace_next(
        buffer_id as u64,
        &options,
        &replacement,
        from.max(0) as usize,
    );
    replace_outcome_array(&env, outcome)
}

/// Replace every hit in the buffer with `replacement`, as one edit and one
/// undo step however many there were. The same three longs and the same null
/// as `bufferReplaceNext`; the middle one is the count.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferReplaceAll(
    mut env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    query_json: JString,
    replacement: JString,
) -> jlongArray {
    let query_json = get_string(&mut env, &query_json);
    let replacement = get_string(&mut env, &replacement);
    let options = match serde_json::from_str(&query_json) {
        Ok(options) => options,
        Err(err) => {
            log::warn!("bufferReplaceAll: {query_json:?} is not a query: {err}");
            return std::ptr::null_mut();
        }
    };
    let outcome = engine().replace_all(buffer_id as u64, &options, &replacement);
    replace_outcome_array(&env, outcome)
}

/// `[version, replaced, resume_at]`, or null for a failure already logged.
fn replace_outcome_array(
    env: &JNIEnv,
    outcome: Result<engine::ReplaceOutcome, engine::EngineError>,
) -> jlongArray {
    let outcome = match outcome {
        Ok(outcome) => outcome,
        Err(err) => {
            log::warn!("buffer replace failed: {err}");
            return std::ptr::null_mut();
        }
    };
    let flat = [
        outcome.version as jlong,
        outcome.replaced as jlong,
        outcome.resume_at as jlong,
    ];
    let array = env
        .new_long_array(flat.len() as i32)
        .expect("failed to allocate replace array");
    env.set_long_array_region(&array, 0, &flat)
        .expect("failed to fill replace array");
    array.into_raw()
}

/// Replace every hit in every file the project's last search found, as JSON:
///
///     {"files": 3, "replacements": 12, "buffers": [4, 9],
///      "errors": ["src/gone.rs: No such file or directory"]}
///
/// `buffers` are the open buffers that were edited — through the ordinary
/// buffer path, so their editors must resync and one undo takes each back.
/// Files not open are rewritten on disk, atomically, with no undo. `errors`
/// are the files that could not be rewritten; the rest were.
///
/// Null when the project is unknown, the query does not compile, or there is
/// no *finished* search to replace over — the panel only offers the button
/// once the search is done, so that last one is a race, not a user error.
///
/// **Blocking**: reads and writes every file in the list. Off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectReplaceAll(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    query_json: JString,
    replacement: JString,
) -> jstring {
    let query_json = get_string(&mut env, &query_json);
    let replacement = get_string(&mut env, &replacement);
    let options = match serde_json::from_str(&query_json) {
        Ok(options) => options,
        Err(err) => {
            log::warn!("projectReplaceAll: {query_json:?} is not a query: {err}");
            return std::ptr::null_mut();
        }
    };
    match engine().project_replace_all(project_id as u64, &options, &replacement) {
        Ok(summary) => match serde_json::to_string(&summary) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("projectReplaceAll failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        Err(err) => {
            log::warn!("projectReplaceAll failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Start searching a project. Returns a search id to poll with, or -1 if the
/// project is unknown or the query does not compile. Returns at once: the
/// search runs on a thread of its own.
///
/// A project still being scanned is neither of those things: the search starts,
/// reports `"scanning"` until the scan lands, and then searches the whole tree.
///
/// Starting a search cancels whatever was already running for that project.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectSearchStart(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    query_json: JString,
) -> jlong {
    let query_json = get_string(&mut env, &query_json);
    let options = match serde_json::from_str(&query_json) {
        Ok(options) => options,
        Err(err) => {
            log::warn!("projectSearchStart: {query_json:?} is not a query: {err}");
            return -1;
        }
    };
    match engine().start_project_search(project_id as u64, &options) {
        Ok(id) => id as jlong,
        Err(err) => {
            log::warn!("projectSearchStart failed: {err}");
            -1
        }
    }
}

/// Generation counter for a search, bumped whenever there is something new to
/// read. Non-zero from the moment `projectSearchStart` returns, so 0 means
/// only one thing: an id the engine has forgotten. Poll it like
/// `projectVersion`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectSearchVersion(
    _env: JNIEnv,
    _class: JClass,
    search_id: jlong,
) -> jlong {
    engine().project_search_version(search_id as u64) as jlong
}

/// Everything a search has found from `from_file` onwards, as JSON. Results
/// only grow, so a caller holding `n` files passes `n` and gets the rest.
/// Never null: a forgotten id reports itself cancelled with nothing in it.
///
/// `state` is `scanning`, `running`, `done` or `cancelled`. Not free: this
/// clones and serializes every file found since the last call, which after a
/// 100 ms publish interval can be megabytes — call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectSearchResults(
    env: JNIEnv,
    _class: JClass,
    search_id: jlong,
    from_file: jlong,
) -> jstring {
    let from_file = from_file.max(0) as usize;
    let results = engine().project_search_results(search_id as u64, from_file);
    let json = serde_json::to_string(&results).unwrap_or_else(|err| {
        log::warn!("projectSearchResults failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// Stop a search and forget it. False if the engine no longer knows the id.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_projectSearchCancel(
    _env: JNIEnv,
    _class: JClass,
    search_id: jlong,
) -> jboolean {
    if engine().cancel_project_search(search_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferText(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    match engine().text(buffer_id as u64) {
        Ok(text) => to_jstring(&env, text),
        Err(err) => {
            log::warn!("bufferText failed: {err}");
            std::ptr::null_mut()
        }
    }
}

// ---------------------------------------------------------------------------
// Language servers.
//
// The engine has no LSP client of its own: it drives Zed's, over the same
// proot the git calls above go through, so a server is whatever `apt` put in
// the Debian rootfs. That makes every call here degrade the way the git ones
// do — no userland, no server installed, or a language nobody packages one for
// all report "nothing to show" rather than an error.
//
// Two shapes, both already used elsewhere on this boundary:
//
// * **Diagnostics are pushed and polled.** The server sends them when it feels
//   like it; the engine caches them and bumps a counter. Poll `lspVersion` per
//   project and `bufferDiagnosticsVersion` per open buffer, exactly as the
//   panel polls `projectVersion` — and read the JSON only when one moves.
//   Polling `lspVersion` is also what *starts* servers for files that were
//   already open when the userland appeared, so a project view must poll it.
// * **Requests are started and polled.** `lspRequestCompletion` and friends
//   return an id at once and never block. Poll `lspRequestVersion` (1 while in
//   flight, 2 once settled, 0 once forgotten) and then read
//   `lspRequestResult`. Starting a request cancels the previous one *of the
//   same kind*, which is what a completion popup re-asking on every keystroke
//   wants; `lspRequestCancel` frees the slot when the popup closes.
//
// Positions are UTF-16 columns in both directions, like every other position
// on this boundary (`bufferHighlights`, `bufferOutlinePath`).
// ---------------------------------------------------------------------------

/// Generation counter for everything a project's language servers have said:
/// diagnostics for any of its files, and the servers' own state. 0 until
/// something has. Poll it exactly like `projectVersion`.
///
/// Polling is also what *starts* servers, and opening the folder is reason
/// enough: the scanned tree's languages start their servers with no tab open
/// at all, so a workspace-wide analysis (rust-analyzer's cargo check) runs
/// from the moment the project is up. The same poll covers files that were
/// open before the userland arrived — `apt install clangd` in the terminal
/// while the editor is running. It never waits for a server.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspVersion(
    _env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jlong {
    engine().lsp_version(project_id as u64) as jlong
}

/// What each of the project's servers is doing, as a JSON array of
/// `{name, state, error, languages, progress}`. `state` is `starting`,
/// `running` or `unavailable`; `error` carries the server's own last line of
/// stderr when it could not be started, which is usually "command not found"
/// and is the user's cue to install it; `progress` is the server's own
/// one-line `$/progress` report ("indexing (45%)") or null while it is quiet.
/// Versioned by `lspVersion`. Never blocks, never null, `[]` when there is
/// nothing running.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspServers(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let servers = engine().lsp_servers(project_id as u64);
    let json = serde_json::to_string(&servers).unwrap_or_else(|err| {
        log::warn!("lspServers failed to serialize: {err}");
        "[]".to_owned()
    });
    to_jstring(&env, json)
}

/// Diagnostic totals for a project, as JSON:
/// `{version, errors, warnings, infos, hints, files: [{path, errors, warnings,
/// infos, hints}]}`. Paths are project-relative and `/`-separated — the same
/// spelling `projectEntries` and `gitChanges` use — except for a file outside
/// the project, which keeps its absolute path. Versioned by `lspVersion`.
/// Never blocks, never null.
///
/// Diagnostics are **project-wide**, as Zed's are: closing a tab does not
/// retract what a server said about that file, because a workspace-wide
/// analysis (rust-analyzer's `cargo check`) is still right about it. Only an
/// empty publish from the server, or `closeProject`, clears them.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspDiagnostics(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let diagnostics = engine().lsp_diagnostics(project_id as u64);
    let json = serde_json::to_string(&diagnostics).unwrap_or_else(|err| {
        log::warn!("lspDiagnostics failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// Every diagnostic in the project, messages included, as JSON:
/// `{version, files: [{path, rows: [{row, col_utf16, end_row, end_col_utf16,
/// severity, message, source, code}]}]}`. Paths are spelled as
/// `lspDiagnostics` spells them; rows as `bufferDiagnostics` spells them,
/// sorted by position. Versioned by `lspVersion`, like the counts.
///
/// This is the diagnostics *panel's* read and it serializes every message in
/// the project — poll `lspVersion` and call this only when the counter moves
/// and the panel is actually showing. The status bar keeps to
/// `lspDiagnostics`. Never blocks, never null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspDiagnosticRows(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let diagnostics = engine().lsp_diagnostic_rows(project_id as u64);
    let json = serde_json::to_string(&diagnostics).unwrap_or_else(|err| {
        log::warn!("lspDiagnosticRows failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// Generation counter for one buffer's diagnostics; 0 until a server has
/// published for its file. Poll this per open tab — it is a hash lookup, where
/// `bufferDiagnostics` clones and serializes every row.
///
/// It does **not** move when the buffer is edited: a UI must not be woken by
/// its own typing. `bufferDiagnostics().stale` is what says the rows have
/// drifted.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferDiagnosticsVersion(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    engine().buffer_diagnostics_version(buffer_id as u64) as jlong
}

/// Everything a server has said about this buffer's file, as JSON:
/// `{version, buffer_version, stale, rows: [{row, col_utf16, end_row,
/// end_col_utf16, severity, message, source, code}]}`.
///
/// `severity` is `error`, `warning`, `info` or `hint` — never absent, because a
/// diagnostic the server left unrated is treated as a warning. `source` and
/// `code` may be null. Rows are sorted by position, so painting a visible
/// window is one walk.
///
/// `buffer_version` is the buffer version the rows describe, or null when the
/// server dated them against text we no longer have; `stale` is true when the
/// buffer has moved since — dim the underlines rather than moving them.
///
/// Reads a cache: never blocks, never null, empty for a buffer with no file, no
/// server, or nothing wrong with it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferDiagnostics(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    let diagnostics = engine().buffer_diagnostics(buffer_id as u64);
    let json = serde_json::to_string(&diagnostics).unwrap_or_else(|err| {
        log::warn!("bufferDiagnostics failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// A caret, clamped the way `bufferOutlinePath` clamps one.
fn caret(row: jlong, col_utf16: jlong) -> (u32, u32) {
    (
        row.max(0).min(u32::MAX as jlong) as u32,
        col_utf16.max(0).min(u32::MAX as jlong) as u32,
    )
}

/// Ask for completions at a caret. Returns a request id to poll with — never
/// blocks and never fails: a buffer with no server behind it gets an id that
/// reports `unavailable` immediately, so the UI has one code path.
///
/// Cancels whatever completion request was already in flight, including at the
/// server, so a popup may call this on every keystroke.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestCompletion(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    col_utf16: jlong,
) -> jlong {
    let (row, col) = caret(row, col_utf16);
    engine().lsp_request_completion(buffer_id as u64, row, col) as jlong
}

/// Hover documentation at a caret. Same contract as `lspRequestCompletion`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestHover(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    col_utf16: jlong,
) -> jlong {
    let (row, col) = caret(row, col_utf16);
    engine().lsp_request_hover(buffer_id as u64, row, col) as jlong
}

/// Where the symbol under the caret is defined. Same contract as
/// `lspRequestCompletion`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestDefinition(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    col_utf16: jlong,
) -> jlong {
    let (row, col) = caret(row, col_utf16);
    engine().lsp_request_definition(buffer_id as u64, row, col) as jlong
}

/// Everywhere the symbol under the caret is used, declaration included. Same
/// contract as `lspRequestCompletion`; the payload is
/// `{targets: [{path, row, col_utf16, end_row, end_col_utf16, line_text}]}` —
/// definition's targets, each with the trimmed text of its line when it could
/// be read.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestReferences(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    col_utf16: jlong,
) -> jlong {
    let (row, col) = caret(row, col_utf16);
    engine().lsp_request_references(buffer_id as u64, row, col) as jlong
}

/// The code actions available at a caret — quick fixes for the diagnostics
/// under it, refactorings otherwise. Same polling contract as
/// `lspRequestCompletion`; the payload is
/// `{actions: [{index, title, kind, is_preferred, disabled}]}`, where
/// `disabled` is null for an action that can run and a sentence when it
/// cannot. The actions themselves stay in the engine, keyed by `index`, for
/// `lspRequestCodeActionApply` — so keep this request alive (do not cancel
/// it) until the pick is made.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestCodeActions(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    col_utf16: jlong,
) -> jlong {
    let (row, col) = caret(row, col_utf16);
    engine().lsp_request_code_actions(buffer_id as u64, row, col) as jlong
}

/// Pick one action out of a settled `lspRequestCodeActions` answer by its
/// `index` and ready its edit, resolving through `codeAction/resolve` when
/// the server sent it lazy. Returns a new request id on the same polling
/// contract; it settles `done` with `{files, edits, resource_ops}` — or
/// `{error}` when the server offered no edit — and the edit itself waits for
/// `lspApplyPendingEdit`. A stale list id or bad index settles `unavailable`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestCodeActionApply(
    _env: JNIEnv,
    _class: JClass,
    request_id: jlong,
    index: jlong,
) -> jlong {
    engine().lsp_request_code_action_apply(request_id as u64, index.max(0) as usize) as jlong
}

/// Rename the symbol under the caret to `newName`, everywhere the server
/// knows about. Same polling contract as `lspRequestCompletion`; settles
/// `done` with `{files, edits, resource_ops}` — or `{error}` when there is
/// nothing to rename — and **changes nothing** until `lspApplyPendingEdit`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestRename(
    mut env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    col_utf16: jlong,
    new_name: JString,
) -> jlong {
    let new_name = get_string(&mut env, &new_name);
    let (row, col) = caret(row, col_utf16);
    engine().lsp_request_rename(buffer_id as u64, row, col, &new_name) as jlong
}

/// Format the whole document with the workspace's tab size. Same polling
/// contract as `lspRequestCompletion`; settles `done` with
/// `{files, edits, resource_ops}` — zero edits is a well-formatted file —
/// and applies nothing until `lspApplyPendingEdit`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestFormatting(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    engine().lsp_request_formatting(buffer_id as u64) as jlong
}

/// Where the *type* of the symbol under the caret is defined — Zed's
/// `editor::GoToTypeDefinition`. `lspRequestDefinition`'s contract and
/// payload shape.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestTypeDefinition(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    col_utf16: jlong,
) -> jlong {
    let (row, col) = caret(row, col_utf16);
    engine().lsp_request_type_definition(buffer_id as u64, row, col) as jlong
}

/// The implementations of the symbol under the caret — Zed's
/// `editor::GoToImplementation`. `lspRequestDefinition`'s contract and shape.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestImplementation(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    col_utf16: jlong,
) -> jlong {
    let (row, col) = caret(row, col_utf16);
    engine().lsp_request_implementation(buffer_id as u64, row, col) as jlong
}

/// The declaration of the symbol under the caret — Zed's
/// `editor::GoToDeclaration`. `lspRequestDefinition`'s contract and shape.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestDeclaration(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    col_utf16: jlong,
) -> jlong {
    let (row, col) = caret(row, col_utf16);
    engine().lsp_request_declaration(buffer_id as u64, row, col) as jlong
}

/// Inlay hints for rows `firstRow..=lastRow` — the visible range. Same
/// polling contract as `lspRequestCompletion`; the payload is `{hints:
/// [{row, col_utf16, label, kind, padding_left, padding_right}]}`, `kind`
/// being `type`, `parameter` or null. `row` echoes `firstRow` and
/// `buffer_version` the version asked at: drop an answer whose version is
/// not the buffer's current one, its columns describe text that moved.
/// Supersedes the previous hint request, so a scroll may ask freely.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestInlayHints(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    first_row: jlong,
    last_row: jlong,
) -> jlong {
    let (first, last) = caret(first_row, last_row);
    engine().lsp_request_inlay_hints(buffer_id as u64, first, last) as jlong
}

/// The signature of the call the caret sits in — Zed's
/// `editor::ShowSignatureHelp`. Same polling contract as
/// `lspRequestCompletion`; the payload is `{signatures: [{label,
/// documentation, parameters: [{start, end, documentation}],
/// active_parameter}], active_signature}` with `start`/`end` UTF-16 offsets
/// into the label. An empty `signatures` list is "not in a call".
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestSignatureHelp(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
    row: jlong,
    col_utf16: jlong,
) -> jlong {
    let (row, col) = caret(row, col_utf16);
    engine().lsp_request_signature_help(buffer_id as u64, row, col) as jlong
}

/// The server's folding ranges for a whole buffer, as `{ranges:
/// [{start_row, end_row}]}` in `bufferFoldRanges`'s shape. Settles
/// `unavailable` at once for a server that does not fold — use the syntax
/// tree then. Same polling contract as `lspRequestCompletion`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestFoldingRanges(
    _env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jlong {
    engine().lsp_request_folding_ranges(buffer_id as u64) as jlong
}

/// Resolve one item of a settled `lspRequestCompletion` answer by its
/// `index` — the documentation and `additionalTextEdits` a server leaves out
/// of the list. Settles `done` with `{documentation, detail,
/// additional_edits}`; when `additional_edits` is more than zero the edits
/// (an import to add) wait for `lspApplyPendingEdit`, to be landed *after*
/// the completion itself is inserted. The list request must still be alive.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestCompletionResolve(
    _env: JNIEnv,
    _class: JClass,
    request_id: jlong,
    index: jlong,
) -> jlong {
    engine().lsp_request_completion_resolve(request_id as u64, index.max(0) as usize) as jlong
}

/// `workspace/symbol` across every running server of a project — Zed's
/// `project_symbols::Toggle`. Same polling contract as `lspRequestCompletion`
/// (the request's `buffer_id` is 0); the payload is `{symbols: [{name, kind,
/// container, path, absolute_path, row, col_utf16, end_row, end_col_utf16,
/// server}]}`, `path` project-relative where it can be. `unavailable` when
/// no server is running. Supersedes the previous query.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestWorkspaceSymbols(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    query: JString,
) -> jlong {
    let query = get_string(&mut env, &query);
    engine().lsp_request_workspace_symbols(project_id as u64, &query) as jlong
}

/// The characters a buffer's server opens its menus on, from its declared
/// capabilities: `{completion: [...], signature_help: [...],
/// signature_help_retrigger: [...], folding_ranges, inlay_hints}`. Every list
/// is empty for a buffer with no running server — keep the defaults then.
/// Reads a cache; never blocks.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspBufferTriggers(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    let triggers = engine().lsp_buffer_triggers(buffer_id as u64);
    let json = serde_json::to_string(&triggers).unwrap_or_else(|err| {
        log::warn!("lspBufferTriggers failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// Stop a project's server by name and start it again — Zed's
/// `editor::RestartLanguageServer`. False when there is no such server.
/// Never blocks; watch `lspVersion` and `lspServers` for it coming back.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRestartServer(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    name: JString,
) -> jboolean {
    let name = get_string(&mut env, &name);
    if engine().lsp_restart_server(project_id as u64, &name) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Stop a project's server by name and keep it stopped — Zed's
/// `editor::StopLanguageServer`. It then reports `unavailable` with the
/// error "stopped" until `lspRestartServer` or the project closes; typing in
/// its files does not wake it. False when there is no such server.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspStopServer(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    name: JString,
) -> jboolean {
    let name = get_string(&mut env, &name);
    if engine().lsp_stop_server(project_id as u64, &name) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// The log's counter alone — what `lspServerLogs` reports as `version`,
/// without the lines: poll this, read the lines when it moves. Zero for a
/// server that never started. Never blocks.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspServerLogsVersion(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    name: JString,
) -> jlong {
    let name = get_string(&mut env, &name);
    engine().lsp_server_logs_version(project_id as u64, &name) as jlong
}

/// A server's log — the last two thousand lines of its stderr, its
/// `window/logMessage`s, the RPC trace and the engine's own lifecycle
/// notes — as `{version, lines}`. `version` moves per line; poll it and read
/// only when it does. Empty for a server that never started. Never blocks.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspServerLogs(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    name: JString,
) -> jstring {
    let name = get_string(&mut env, &name);
    let snapshot = engine().lsp_server_logs(project_id as u64, &name);
    let json = serde_json::to_string(&snapshot).unwrap_or_else(|err| {
        log::warn!("lspServerLogs failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// Land the edit a settled rename, formatting or code-action-apply request is
/// holding, and say what was touched:
/// `{applied, error, files: [{path, buffer_id, edits}]}`.
///
/// Open buffers take the edits through the normal edit path — `didChange`,
/// undo history and version bumps included — and files nobody has open are
/// rewritten atomically on disk. `path` is absolute and canonical: match it
/// against open tabs, and **refresh the editor of every file whose
/// `buffer_id` is not null** — the engine changed those buffers underneath
/// the UI. The edit is taken: a second call reports "nothing to apply".
/// Blocking, like `saveBuffer`; call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspApplyPendingEdit(
    env: JNIEnv,
    _class: JClass,
    request_id: jlong,
) -> jstring {
    let receipt = engine().lsp_apply_pending_edit(request_id as u64);
    let json = serde_json::to_string(&receipt).unwrap_or_else(|err| {
        log::warn!("lspApplyPendingEdit failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// Generation counter for a request: 1 while it is in flight, 2 once it has
/// settled, 0 for an id the engine has forgotten (superseded, cancelled, or its
/// buffer closed). Poll it like `projectSearchVersion`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestVersion(
    _env: JNIEnv,
    _class: JClass,
    request_id: jlong,
) -> jlong {
    engine().lsp_request_version(request_id as u64) as jlong
}

/// A request's answer, as JSON:
/// `{id, kind, state, version, buffer_id, row, col_utf16, buffer_version,
/// payload}`.
///
/// `kind` is `completion`, `hover`, `definition`, `references`,
/// `code_action`, `code_action_apply`, `rename`, `formatting`,
/// `type_definition`, `implementation`, `declaration`, `inlay_hint`,
/// `signature_help`, `workspace_symbol`, `folding_range` or
/// `completion_resolve`. `state` is
/// `pending`,
/// `done`, `timeout`, `unavailable` or `cancelled` — `done` with an empty
/// payload is a real answer ("no completions here"), the other three are not,
/// and a UI should not cache them. `row`, `col_utf16` and `buffer_version` echo
/// where and when it was asked, so a late answer can be dropped by a caller
/// whose caret has moved.
///
/// `payload` is null until it settles, and then depends on `kind`:
///
/// * `completion` — `{is_incomplete, items: [{label, detail, kind, insert_text,
///   is_snippet, filter_text, sort_text, documentation, deprecated, preselect,
///   edit}]}`. `insert_text`, `filter_text` and `sort_text` are never null
///   (they fall back to the label); `is_snippet` means `insert_text` carries
///   `${1:placeholder}` syntax; `edit` is `{row, col_utf16, end_row,
///   end_col_utf16}` — the range to replace — or null, meaning the UI picks the
///   word around the caret itself.
/// * `hover` — `{contents, range}`. `contents` is markdown and is `""` when the
///   server had nothing to say; `range` is the same shape as `edit`, or null.
/// * `definition` — `{targets: [{path, row, col_utf16, end_row,
///   end_col_utf16}]}`. `path` is absolute and openable with `openFile`;
///   targets in URIs that are not files are dropped rather than handed over.
/// * `references` — definition's shape plus `line_text` per target.
/// * `code_action` — `{actions: [{index, title, kind, is_preferred,
///   disabled}]}`; see `lspRequestCodeActions`.
/// * `code_action_apply`, `rename`, `formatting` — `{files, edits,
///   resource_ops}` when an edit is waiting for `lspApplyPendingEdit`, or
///   `{error}` when the server had nothing to offer. A code action that ran
///   a server command and changed nothing answers `{files: 0, edits: 0,
///   resource_ops: false, ran: <title>}`.
/// * `type_definition`, `implementation`, `declaration` — definition's shape.
/// * `inlay_hint` — see `lspRequestInlayHints`.
/// * `signature_help` — see `lspRequestSignatureHelp`.
/// * `workspace_symbol` — see `lspRequestWorkspaceSymbols`.
/// * `folding_range` — `{ranges: [{start_row, end_row}]}`.
/// * `completion_resolve` — see `lspRequestCompletionResolve`.
///
/// Never null. A forgotten id reports itself `cancelled` with a null payload;
/// every other field of that answer is a placeholder, `kind` included — the
/// caller is the one that knows what it asked for.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestResult(
    env: JNIEnv,
    _class: JClass,
    request_id: jlong,
) -> jstring {
    let result = engine().lsp_request_result(request_id as u64);
    let json = serde_json::to_string(&result).unwrap_or_else(|err| {
        log::warn!("lspRequestResult failed to serialize: {err}");
        "{}".to_owned()
    });
    to_jstring(&env, json)
}

/// Stop a request and forget it — how a closed completion popup frees its slot,
/// and how the server is told to stop working on an answer nobody will read.
/// False if the id was already gone.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_lspRequestCancel(
    _env: JNIEnv,
    _class: JClass,
    request_id: jlong,
) -> jboolean {
    if engine().lsp_cancel_request(request_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

// ---------------------------------------------------------------------------
// ACP agents (phase 6)
//
// The engine runs an agent inside the Debian userland and keeps one state
// machine per session; this is the coarse read/write surface over it. Two
// shapes, both already on this boundary:
//
//  * **The conversation is pushed and polled.** The agent streams whenever it
//    likes; the engine folds each update into the session and bumps a
//    revision. Poll `acpSessionVersion`, then read `acpSessionState` for the
//    chrome and `acpEntriesSince` for the rows that actually moved — the same
//    counter-then-payload contract as `lspVersion`, and for the same reason.
//  * **Everything the user does returns at once.** Prompting, cancelling and
//    answering a permission request all hand work to the connection thread and
//    come straight back; what happened shows up behind the counter.
//
// Positions inside a tool call's diff are 1-based rows in the shape `gitPatch`
// already speaks, so an agent's edit renders with the diff view the git panel
// uses. Nothing else here carries a position.
//
// The `play` flavour has no userland, so it has no agent: `acpStartSession`
// answers with a session that reports itself unavailable, and the panel is
// absent rather than broken.
// ---------------------------------------------------------------------------

/// Start (or join) the agent described by `specJson` and open a session on
/// `projectId`.
///
/// `specJson` is `{"name": …, "argv": [program, …], "env": {…}}` — the argv is
/// the *guest* command line, so the program must be on the userland's PATH.
///
/// Returns a session id to poll, or -1 when the request itself was malformed
/// (bad JSON, no command, unknown project) — a caller's bug, with nothing to
/// show a user. Everything a user can act on arrives as a session instead: no
/// userland and an agent that will not start both come back as a real id whose
/// state is `unavailable` with a sentence.
///
/// **Blocking** — it spawns a process. Call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpStartSession(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    spec_json: JString,
) -> jlong {
    let spec = get_string(&mut env, &spec_json);
    match engine().acp_start_session(project_id as u64, &spec) {
        Ok(id) => id as jlong,
        Err(err) => {
            log::warn!("acpStartSession refused: {err}");
            -1
        }
    }
}

/// Generation counter for a session: it moves whenever anything about the
/// conversation does. 0 means one thing only — an id the engine has forgotten.
/// Poll it exactly like `projectSearchVersion`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpSessionVersion(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jlong {
    engine().acp_session_version(session_id as u64) as jlong
}

/// Everything about a session except its rows, as JSON — see the Kotlin
/// declaration for the shape. `"null"` for a forgotten id. Reads a cache;
/// never blocks.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpSessionState(
    env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jstring {
    to_jstring(&env, engine().acp_session_state(session_id as u64))
}

/// The conversation rows whose revision is newer than `since`, as JSON:
/// `{revision, total, entries: [{index, rev, kind, …}]}`.
///
/// Only what moved comes back, with the index it sits at, so a caller merges
/// in place and pays for the whole transcript once. `total` is how many rows
/// there are now — when it is smaller than what the caller holds, a refusal
/// has removed some and the caller re-reads from 0.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpEntriesSince(
    env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    since: jlong,
) -> jstring {
    let entries = engine().acp_entries_since(session_id as u64, since.max(0) as u64);
    to_jstring(&env, entries)
}

/// Send a prompt. Returns at once; the turn arrives behind the counter. False
/// for a forgotten id or a session that is over.
///
/// `images_json` is a JSON array of `{"mime_type", "data"}`, the data
/// base64-encoded — attachments the platform layer has already decoded and
/// shrunk, since the engine has no image codec. `[]` for a prompt with no
/// picture in it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpPrompt(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    text: JString,
    mentions_json: JString,
    images_json: JString,
) -> jboolean {
    let text = get_string(&mut env, &text);
    let mentions_json = get_string(&mut env, &mentions_json);
    let images_json = get_string(&mut env, &images_json);
    if engine().acp_prompt(session_id as u64, &text, &mentions_json, &images_json) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Change one of the agent's session configuration options —
/// `session/set_config_option`, the request behind model/effort selectors.
/// `value_json` is `true`/`false` for a boolean option or a JSON string for
/// a select's value id. False for a forgotten id or a value that is neither.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpSetConfigOption(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    config_id: JString,
    value_json: JString,
) -> jboolean {
    let config_id = get_string(&mut env, &config_id);
    let value_json = get_string(&mut env, &value_json);
    if engine().acp_set_config_option(session_id as u64, &config_id, &value_json) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Stop the running turn. False for a forgotten id.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpCancel(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jboolean {
    if engine().acp_cancel(session_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Answer a permission request: `optionId` is one of the ids the tool call's
/// `options` offered. False when nothing was waiting under that tool call, or
/// the option is not one it offered.
///
/// `answerMetaJson` (`""` for none) becomes the response's `_meta` — how a
/// question walked through the permission channel says *what* was answered,
/// as against which button was pressed. Malformed JSON is dropped and the
/// choice still travels.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpRespondPermission(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    tool_call_id: JString,
    option_id: JString,
    answer_meta_json: JString,
) -> jboolean {
    let tool_call = get_string(&mut env, &tool_call_id);
    let option = get_string(&mut env, &option_id);
    let answer_meta = get_string(&mut env, &answer_meta_json);
    if engine().acp_respond_permission(session_id as u64, &tool_call, &option, &answer_meta) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Switch the session's mode. The change lands when the agent confirms it, so
/// watch the counter rather than assuming. False when the session has no modes.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpSetMode(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    mode_id: JString,
) -> jboolean {
    let mode = get_string(&mut env, &mode_id);
    if engine().acp_set_mode(session_id as u64, &mode) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Run one of the agent's advertised auth methods, then retry the sessions
/// that were waiting on it. False when there is no agent to authenticate with.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpAuthenticate(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    method_id: JString,
) -> jboolean {
    let method = get_string(&mut env, &method_id);
    if engine().acp_authenticate(session_id as u64, &method) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Close a session and forget it. Closing the last one stops the agent, the
/// careful way proot needs. False if the id was already gone.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpCloseSession(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jboolean {
    if engine().acp_close_session(session_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Files the agent has written through the client, from `since` onwards:
/// `{"total": n, "paths": [absolute, …]}`.
///
/// The engine flags any open buffer among them the way it flags any other
/// external change; this is how the UI learns *which* ones, so it can reload
/// them through `reloadBuffer` — undoably, and with highlighting and the
/// language server kept in step. Pass the `total` you were last given.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpWrittenFiles(
    env: JNIEnv,
    _class: JClass,
    since: jlong,
) -> jstring {
    to_jstring(&env, engine().acp_written_files(since.max(0) as u64))
}

/// Reopen one of the agent's own past conversations in a new thread —
/// `session/load` when the agent can replay the history, `session/resume`
/// when it can only continue. `session_id` comes from `acpSessionList`.
/// Errors exactly as `acpStartSession` does.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpResumeSession(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    spec_json: JString,
    session_id: JString,
) -> jlong {
    let spec_json = get_string(&mut env, &spec_json);
    let session_id = get_string(&mut env, &session_id);
    match engine().acp_resume_session(project_id as u64, &spec_json, &session_id) {
        Ok(id) => id as jlong,
        Err(err) => {
            log::warn!("acpResumeSession refused: {err}");
            -1
        }
    }
}

/// The agent's own past conversations — `session/list`, which not every agent
/// has (`agent.capabilities.list` in `acpSessionState` says). Pass `refresh`
/// when the user asked for the list; `false` while polling.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpSessionList(
    env: JNIEnv,
    _class: JClass,
    refresh: jboolean,
) -> jstring {
    to_jstring(&env, engine().acp_session_list(refresh != JNI_FALSE))
}

/// Version counter for `acpSessionList` — the cached list's own `version`
/// field, without serializing the list to learn it. Poll this single load and
/// make the full read only when it moves; it covers `loading` flipping as
/// well as the answer landing. 0 means no agent is running, and a replaced
/// agent never repeats a value already seen.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpSessionListVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    engine().acp_session_list_version() as jlong
}

/// Forget one of the agent's past conversations — `session/delete`. False
/// when the agent has no such method or there is no agent.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpDeleteSession(
    mut env: JNIEnv,
    _class: JClass,
    session_id: JString,
) -> jboolean {
    let session_id = get_string(&mut env, &session_id);
    if engine().acp_delete_session(&session_id) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Sign out of whatever `acpAuthenticate` signed into — `logout`. False when
/// the agent has no such method or there is no agent.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpLogout(
    _env: JNIEnv,
    _class: JClass,
) -> jboolean {
    if engine().acp_logout() {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Interrupt the running turn and send this prompt as soon as it stops — the
/// deliberate version of a follow-up. `acpPrompt` queues instead, which is
/// what a follow-up typed mid-turn should do.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpPromptImmediately(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    text: JString,
    mentions_json: JString,
    images_json: JString,
) -> jboolean {
    let text = get_string(&mut env, &text);
    let mentions_json = get_string(&mut env, &mentions_json);
    let images_json = get_string(&mut env, &images_json);
    if engine().acp_prompt_immediately(session_id as u64, &text, &mentions_json, &images_json) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Drop one queued prompt, by the `id` its row carries in `queue`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpRemoveQueuedPrompt(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    queued_id: jlong,
) -> jboolean {
    if engine().acp_remove_queued_prompt(session_id as u64, queued_id.max(0) as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Version counter for `acpPendingElicitations` — poll this single load and
/// read the list only when it moves, the `acpSessionVersion` contract. It
/// moves whenever any of the agent's questions changes, session-scoped ones
/// included. 0 means no agent is running, and a replaced agent never repeats
/// a value already seen.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpElicitationsVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    engine().acp_elicitations_version() as jlong
}

/// The agent's questions that belong to no session — a JSON array in the same
/// shape `elicitations` takes in `acpSessionState`. Read it when
/// `acpElicitationsVersion` moves: one of these can be raised before any
/// session exists, and an unanswered one blocks the agent.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpPendingElicitations(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(&env, engine().acp_pending_elicitations())
}

/// Version counter for `acpPendingQuestions` — the `acpSessionVersion`
/// contract again: poll this single load, read the list only when it moves.
///
/// A question that names a session already rides that session's own version
/// (it is folded into `acpSessionState` as `questions`), so this counter is
/// only needed for one raised before any session exists. 0 means no agent is
/// running, and a replaced agent never repeats a value already seen.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpQuestionsVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    engine().acp_questions_version() as jlong
}

/// Every open Spettro question — `_spettro/question/ask`, the whole ask-user
/// form in one request — as `[{"id","session","payload"}]` with the payload
/// exactly as the agent sent it. Read it when `acpQuestionsVersion` moves.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpPendingQuestions(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(&env, engine().acp_pending_questions())
}

/// Answer one of them. `answerJson` is the JSON-RPC *result* the agent gets,
/// built on the Kotlin side — `{"answers":[…]}` or `{"kind":"declined"}` —
/// because the shape belongs to the extension, not to the engine. False for a
/// question that is gone, and for malformed JSON, in which case nothing is
/// sent at all.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpRespondQuestion(
    mut env: JNIEnv,
    _class: JClass,
    question_id: JString,
    answer_json: JString,
) -> jboolean {
    let question_id = get_string(&mut env, &question_id);
    let answer_json = get_string(&mut env, &answer_json);
    if engine().acp_respond_question(&question_id, &answer_json) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// The last `_spettro/account/update` the agent pushed, verbatim, or
/// `"null"`. The agent owns the device-flow poller and pushes what it learns:
/// this is how a login progresses on screen, and the phone must never poll
/// the backend itself. Read it when `acpAccountVersion` moves.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpAccountStatus(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    to_jstring(&env, engine().acp_account_status())
}

/// Version counter for `acpAccountStatus`. 0 means no agent is running.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpAccountVersion(
    _env: JNIEnv,
    _class: JClass,
) -> jlong {
    engine().acp_account_version() as jlong
}

/// Call one of the agent's `_spettro/*` methods and wait for the answer — the
/// single seam every one of them goes through; none is modelled in Rust.
///
/// **Blocking, up to 45 seconds** (`_spettro/providers/connect` verifies the
/// key against the provider's own API before answering). Call it on
/// `Dispatchers.IO`, never the main thread.
///
/// The answer is an envelope, because there is no exception channel here:
/// `{"ok":true,"result":…}` or
/// `{"ok":false,"code":…,"message":…,"data":…}`. Code `-32601` is an older
/// CLI rather than a failure — say "update Spettro" — and code 0 means the
/// call never reached the wire.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpCallExtension(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    method: JString,
    params_json: JString,
) -> jstring {
    let method = get_string(&mut env, &method);
    let params_json = get_string(&mut env, &params_json);
    let json = engine().acp_call_extension(project_id as u64, &method, &params_json);
    to_jstring(&env, json)
}

/// Send a message *into* the turn already running — steering.
///
/// Not a new turn and not a cancel: the agent queues the text into the turn
/// it is in the middle of and says so, and the running turn carries on. False
/// unless a turn is actually running and the agent advertised Spettro's
/// extension — a second concurrent prompt to a generic ACP agent is two turns
/// at once, which the protocol says nothing about. Same `mentionsJson` and
/// `imagesJson` as `acpPrompt`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpSteer(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    text: JString,
    mentions_json: JString,
    images_json: JString,
) -> jboolean {
    let text = get_string(&mut env, &text);
    let mentions_json = get_string(&mut env, &mentions_json);
    let images_json = get_string(&mut env, &images_json);
    if engine().acp_steer(session_id as u64, &text, &mentions_json, &images_json) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Put away the notice saying why the last mode or config change did not
/// take. False for a session the engine has forgotten.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpClearNotice(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jboolean {
    if engine().acp_clear_notice(session_id as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Answer one of the agent's questions — `elicitation/create`, the shape
/// every ask that is not a permission arrives in. `action_json` is
/// `{"action":"accept","content":{…}}`, `{"action":"decline"}` or
/// `{"action":"cancel"}`, and the content's JSON types are the protocol's, so
/// a switch comes back as a bool and a number field as a number. False for a
/// question that is already gone.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpRespondElicitation(
    mut env: JNIEnv,
    _class: JClass,
    elicitation_id: JString,
    action_json: JString,
) -> jboolean {
    let elicitation_id = get_string(&mut env, &elicitation_id);
    let action_json = get_string(&mut env, &action_json);
    if engine().acp_respond_elicitation(&elicitation_id, &action_json) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// One agent terminal, for the card that draws it. Poll it with the
/// `revision` you were last given: an unchanged terminal answers
/// `{"revision": n}` and nothing else, which is what makes polling a
/// megabyte-capable buffer cheap. `{"revision": 0}` means the engine no
/// longer has it — the agent released it, or its session closed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpTerminalOutput(
    mut env: JNIEnv,
    _class: JClass,
    terminal_id: JString,
    since: jlong,
) -> jstring {
    let terminal_id = get_string(&mut env, &terminal_id);
    let json = engine().acp_terminal_output(&terminal_id, since.max(0) as u64);
    to_jstring(&env, json)
}

/// Put back every file the agent edited from the user message at
/// `entry_index` on — "Restore checkpoint". Each goes back to what it held
/// before the agent's first touch of it in those turns, through the engine's
/// write path so open buffers reload; the rows after the message are marked
/// `reverted` in `acpEntriesSince`. False when that message has no
/// checkpoint. Only edits the engine saw — `fs/write_text_file` and
/// completed tool-call diffs — are checkpointed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpRestoreCheckpoint(
    _env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    entry_index: jlong,
) -> jboolean {
    if engine().acp_restore_checkpoint(session_id as u64, entry_index.max(0) as u64) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// The review tab: every file the agent edited in this thread, diffed from
/// its earliest pre-edit text to what it holds now, as
/// `{"version", "files": [{"path", "status", "created", "deleted", "diff"}]}`
/// — `diff` in the `gitPatch` shape, `status` `"pending"` or `"kept"`,
/// `path` project-relative. `"null"` for a forgotten session. It reads the
/// files, so call it when `acpSessionVersion` moves, not per frame.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpEditedFiles(
    env: JNIEnv,
    _class: JClass,
    session_id: jlong,
) -> jstring {
    to_jstring(&env, engine().acp_edited_files(session_id as u64))
}

/// Keep the agent's edits to the files in `paths_json` (a JSON array of the
/// project-relative paths `acpEditedFiles` reports; empty means every file).
/// They leave the review; their checkpoints stay. False when nothing changed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpKeepEdits(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    paths_json: JString,
) -> jboolean {
    let paths_json = get_string(&mut env, &paths_json);
    if engine().acp_keep_edits(session_id as u64, &paths_json) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Put the files in `paths_json` (as `acpKeepEdits` takes them) back to what
/// they held before the agent's first touch — Zed's Reject. False when there
/// was nothing to reject.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpRejectEdits(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    paths_json: JString,
) -> jboolean {
    let paths_json = get_string(&mut env, &paths_json);
    if engine().acp_reject_edits(session_id as u64, &paths_json) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// Answer the first waiting permission prompt with the option of `kind` —
/// `allow_once`, `allow_always`, `reject_once` or `reject_always`, the
/// `agent::AllowOnce` family of chords. False when nothing is waiting or
/// the prompt offers no option of that kind.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_acpAnswerWaiting(
    mut env: JNIEnv,
    _class: JClass,
    session_id: jlong,
    kind: JString,
) -> jboolean {
    let kind = get_string(&mut env, &kind);
    if engine().acp_answer_waiting(session_id as u64, &kind) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

// ---------------------------------------------------------------------------
// Tasks and runnables (see engine/src/tasks.rs)
// ---------------------------------------------------------------------------

/// Every task that resolves for the project and the editor context, as a
/// JSON array of `{id, label, full_label, command, args, command_label, cwd,
/// env, use_new_terminal, allow_concurrent_runs, reveal, hide, save, show_command,
/// show_summary, source, tags}` — Zed's `SpawnInTerminal`, ready for a
/// terminal tab. `context_json` is `{buffer_id?, row?, column?,
/// selected_text?, runnable?: {tags, captures, run_text}}`; with a
/// `runnable` the answer is the tasks bound to its tags and nothing else.
/// Empty array with no project. **Blocking**: reads the two `tasks.json`
/// files. Call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_tasksList(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    context_json: JString,
) -> jstring {
    let context = task_context(&mut env, &context_json);
    let tasks = engine()
        .tasks_list(project_id as u64, &context)
        .unwrap_or_else(|err| {
            log::warn!("tasksList failed: {err}");
            Vec::new()
        });
    let json = serde_json::to_string(&tasks).unwrap_or_else(|err| {
        log::warn!("tasksList failed to serialize: {err}");
        "[]".to_owned()
    });
    to_jstring(&env, json)
}

/// Resolve one template the UI made itself — the picker's oneshot, a
/// `{"label": …, "command": …}` object in `tasks.json`'s own format —
/// against the same context `tasksList` uses. One task object as JSON, or
/// null when it names a variable the context lacks. **Blocking**, as
/// `tasksList` is.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_taskResolve(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    context_json: JString,
    template_json: JString,
) -> jstring {
    let context = task_context(&mut env, &context_json);
    let template_json = get_string(&mut env, &template_json);
    let template = match serde_json::from_str::<engine::TaskTemplate>(&template_json) {
        Ok(template) => template,
        Err(err) => {
            log::warn!("taskResolve: template is malformed: {err}");
            return std::ptr::null_mut();
        }
    };
    match engine().task_resolve(project_id as u64, &context, &template) {
        Ok(Some(task)) => match serde_json::to_string(&task) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("taskResolve failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        Ok(None) => std::ptr::null_mut(),
        Err(err) => {
            log::warn!("taskResolve failed: {err}");
            std::ptr::null_mut()
        }
    }
}

/// Every toolchain this project could use, as a JSON array of
/// `{name, path, language, source}` — what the toolchain picker lists
/// (Zed's `toolchain::Select`). **Blocking**: stats the project and runs a
/// few short programs in the userland.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_toolchains(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let toolchains = engine().toolchains(project_id as u64).unwrap_or_else(|err| {
        log::warn!("toolchains failed: {err}");
        Vec::new()
    });
    let json = serde_json::to_string(&toolchains).unwrap_or_else(|err| {
        log::warn!("toolchains failed to serialize: {err}");
        "[]".to_owned()
    });
    to_jstring(&env, json)
}

/// The toolchains in force for this project, one per language, in the same
/// shape — what the status bar shows. Never blocks on the userland.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_activeToolchains(
    env: JNIEnv,
    _class: JClass,
    project_id: jlong,
) -> jstring {
    let toolchains = engine().active_toolchains(project_id as u64);
    let json = serde_json::to_string(&toolchains).unwrap_or_else(|err| {
        log::warn!("activeToolchains failed to serialize: {err}");
        "[]".to_owned()
    });
    to_jstring(&env, json)
}

/// Choose a toolchain for `language` in this project, or clear it when
/// `toolchain_json` is null. Restarts the project's language servers, which
/// is what makes the new interpreter take effect. **Blocking**: writes a
/// small file.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_setToolchain(
    mut env: JNIEnv,
    _class: JClass,
    project_id: jlong,
    language: JString,
    toolchain_json: JString,
) -> jboolean {
    let language = get_string(&mut env, &language);
    let toolchain = if toolchain_json.is_null() {
        None
    } else {
        let json = get_string(&mut env, &toolchain_json);
        match serde_json::from_str::<engine::Toolchain>(&json) {
            Ok(toolchain) => Some(toolchain),
            Err(err) => {
                log::warn!("setToolchain: {json:?} is not a toolchain: {err}");
                return JNI_FALSE;
            }
        }
    };
    match engine().set_toolchain(project_id as u64, &language, toolchain) {
        Ok(()) => JNI_TRUE,
        Err(err) => {
            log::warn!("setToolchain failed: {err}");
            JNI_FALSE
        }
    }
}

fn task_context(env: &mut JNIEnv, context_json: &JString) -> engine::TaskEditorContext {
    let json = get_string(env, context_json);
    serde_json::from_str(&json).unwrap_or_else(|err| {
        log::warn!("tasks: {json:?} is not a task context: {err}");
        engine::TaskEditorContext::default()
    })
}

/// The buffer's runnables — the rows the grammar's `runnables.scm` marks —
/// as a JSON array of `{row, col_utf16, tags, captures, run_text, end_row}`
/// in row order, one per row. Empty for a language with no runnables query;
/// null for an unknown buffer. Reads the last parsed tree, so it is
/// versioned by `bufferHighlightVersion` like the outline.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_bufferRunnables(
    env: JNIEnv,
    _class: JClass,
    buffer_id: jlong,
) -> jstring {
    match engine().buffer_runnables(buffer_id as u64) {
        Ok(rows) => {
            let json = serde_json::to_string(&rows).unwrap_or_else(|err| {
                log::warn!("bufferRunnables failed to serialize: {err}");
                "[]".to_owned()
            });
            to_jstring(&env, json)
        }
        Err(err) => {
            log::warn!("bufferRunnables failed: {err}");
            std::ptr::null_mut()
        }
    }
}

// ---- multibuffers ------------------------------------------------------
//
// The engine composes the excerpts into a *mirror* buffer whose id comes back
// in `multibufferInfo`; the Kotlin side renders that with the ordinary editor
// pane, and the ordinary `applyEdit`/`undoBuffer` calls on it are routed to
// the underlying files by the engine. Only the calls the UI cannot infer live
// here — see engine/src/multibuffer.rs.

/// Open a multibuffer over `excerpts_json`: an array of `{"path", "abs",
/// "row", "endRow"}` with 0-based rows, `abs` defaulting to `root/path` and
/// `endRow` to `row`. Returns its id, or -1 when not one file could be read.
///
/// **Blocking** (it opens every file it excerpts): call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_multibufferCreate(
    mut env: JNIEnv,
    _class: JClass,
    title: JString,
    kind: JString,
    root: JString,
    excerpts_json: JString,
) -> jlong {
    let title = get_string(&mut env, &title);
    let kind = get_string(&mut env, &kind);
    let root = get_string(&mut env, &root);
    let excerpts_json = get_string(&mut env, &excerpts_json);
    let root = (!root.is_empty()).then(|| std::path::PathBuf::from(root));
    let specs = engine::parse_excerpt_specs(&excerpts_json, root.as_deref());
    match engine().create_multibuffer(&title, &kind, &specs) {
        Ok(id) => id as jlong,
        Err(err) => {
            log::warn!("multibufferCreate failed: {err}");
            -1
        }
    }
}

/// The mirror buffer, the headers and the dirty count, as JSON. Null for an id
/// the engine no longer knows.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_multibufferInfo(
    env: JNIEnv,
    _class: JClass,
    multibuffer_id: jlong,
) -> jstring {
    match engine().multibuffer_info(multibuffer_id as u64) {
        Some(info) => match serde_json::to_string(&info) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("multibufferInfo failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        None => std::ptr::null_mut(),
    }
}

/// Which file and row a display row of the mirror shows, as `{"path",
/// "absPath", "row", "header"}`. Null for a row outside every excerpt.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_multibufferLocate(
    env: JNIEnv,
    _class: JClass,
    multibuffer_id: jlong,
    row: jlong,
) -> jstring {
    let row = row.max(0) as u32;
    match engine().multibuffer_locate(multibuffer_id as u64, row) {
        Some(at) => match serde_json::to_string(&at) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("multibufferLocate failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        None => std::ptr::null_mut(),
    }
}

/// Recompose the mirror if a file behind it moved — because its own tab was
/// edited, or it was reloaded from disk. Returns the mirror's content version,
/// which the pane polls, or -1 for an unknown id.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_multibufferSync(
    _env: JNIEnv,
    _class: JClass,
    multibuffer_id: jlong,
) -> jlong {
    engine()
        .multibuffer_sync(multibuffer_id as u64)
        .map(|version| version as jlong)
        .unwrap_or(-1)
}

/// Write every dirty file in the multibuffer — Zed's SaveAll. Returns
/// `{"saved": [path], "failed": ["path: reason"]}`, or null for an unknown id.
///
/// **Blocking**: call it off the main thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_multibufferSaveAll(
    env: JNIEnv,
    _class: JClass,
    multibuffer_id: jlong,
) -> jstring {
    match engine().multibuffer_save_all(multibuffer_id as u64) {
        Some(report) => match serde_json::to_string(&report) {
            Ok(json) => to_jstring(&env, json),
            Err(err) => {
                log::warn!("multibufferSaveAll failed to serialize: {err}");
                std::ptr::null_mut()
            }
        },
        None => std::ptr::null_mut(),
    }
}

/// Close a multibuffer. `keep` names the buffers the caller still has tabs on,
/// which the engine cannot know: a file this multibuffer opened on demand and
/// nobody else holds is released with it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_multibufferClose<'local>(
    env: JNIEnv<'local>,
    _class: JClass<'local>,
    multibuffer_id: jlong,
    keep: JLongArray<'local>,
) -> jboolean {
    let count = env.get_array_length(&keep).unwrap_or(0).max(0) as usize;
    let mut raw = vec![0 as jlong; count];
    if count > 0 && env.get_long_array_region(&keep, 0, &mut raw).is_err() {
        // Keeping nothing would close buffers the caller is still drawing, so
        // a read that failed refuses the whole close instead.
        log::warn!("multibufferClose: could not read the buffers to keep");
        return JNI_FALSE;
    }
    let keep: Vec<u64> = raw.iter().map(|&id| id.max(0) as u64).collect();
    if engine().close_multibuffer(multibuffer_id as u64, &keep) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

// ---------------------------------------------------------------------------
// Workspace sessions. One JSON document per project — the pane tree, the tabs
// with their carets and scroll, the docks, the terminal tabs — plus the list
// of recently opened projects. The engine owns the format and every rule
// about restoring it (see engine/src/session.rs); the app builds the document
// from its view state and applies what it gets back. All of these touch the
// filesystem: call them off the main thread.
// ---------------------------------------------------------------------------

/// Write `document_json` as `root`'s session. Returns false when the JSON is
/// not a session document, or when the write failed.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_sessionSave(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
    document_json: JString,
) -> jboolean {
    let root = get_string(&mut env, &root);
    let document = get_string(&mut env, &document_json);
    if engine().save_session(&root, &document) {
        JNI_TRUE
    } else {
        JNI_FALSE
    }
}

/// `root`'s saved session, validated against the disk as it is now: files
/// that have gone are dropped, carets past the end of a file are clamped,
/// empty panes collapse. Null when there is none, or when the file was
/// corrupt — in which case it has been discarded.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_sessionLoad(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
) -> jstring {
    let root = get_string(&mut env, &root);
    match engine().load_session(&root) {
        Some(json) => to_jstring(&env, json),
        None => std::ptr::null_mut(),
    }
}

/// Forget a project's session — what deleting the project does.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_sessionClear(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
) {
    let root = get_string(&mut env, &root);
    engine().clear_session(&root);
}

/// Note that `root` has just been opened. Returns the recent list as it now
/// stands: a JSON array of `{path, name, last_opened}`, newest first.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_noteProjectOpened(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
) -> jstring {
    let root = get_string(&mut env, &root);
    let recent = engine().note_project_opened(&root);
    to_jstring(&env, recent_json(&recent))
}

/// Every project opened before, newest first, minus the ones no longer on
/// disk. Never null.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_recentProjects(
    env: JNIEnv,
    _class: JClass,
) -> jstring {
    let recent = engine().recent_projects();
    to_jstring(&env, recent_json(&recent))
}

/// Take `root` off the recent list — Zed's "Remove from Recent Projects".
/// The project stays on disk; its session goes with it. Returns the new list.
#[unsafe(no_mangle)]
pub extern "system" fn Java_to_eyed_seeker_code_core_CoreBridge_removeRecentProject(
    mut env: JNIEnv,
    _class: JClass,
    root: JString,
) -> jstring {
    let root = get_string(&mut env, &root);
    let recent = engine().remove_recent_project(&root);
    to_jstring(&env, recent_json(&recent))
}

fn recent_json(recent: &[engine::RecentProject]) -> String {
    serde_json::to_string(recent).unwrap_or_else(|err| {
        log::warn!("recent projects failed to serialize: {err}");
        "[]".to_owned()
    })
}
