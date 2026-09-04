//! Headless `gpui::Platform` — the runtime the vendored Zed crates need.
//!
//! Zed's `fs`, `worktree`, `language` and `lsp` are coupled to GPUI's
//! *reactive* runtime (entities, tasks, executors), not to its rendering. The
//! P3-1 spike established that supplying a minimal `Platform` is enough to run
//! them on Android; this module is that spike's dispatcher, promoted to
//! production with the sleep-loop pump replaced by a condvar.
//!
//! **This platform cannot draw.** Every window, display and menu method is
//! `unimplemented!()`, so if a vendored crate ever reaches for a window it
//! panics loudly instead of silently misbehaving. All pixels are Compose's.
//!
//! Nothing here is Android-specific — it is plain `std::thread` — which is why
//! host `cargo test` exercises the very same runtime the device does.

use std::collections::BinaryHeap;
use std::path::{Path, PathBuf};
use std::rc::Rc;
use std::sync::Arc;
use std::thread;
use std::time::{Duration, Instant};

use anyhow::Result;
use futures::channel::oneshot;
use gpui::{
    Action, AnyWindowHandle, BackgroundExecutor, ClipboardItem, CursorStyle, DummyKeyboardMapper,
    ForegroundExecutor, Keymap, Menu, MenuItem, NoopTextSystem, PathPromptOptions, Platform,
    PlatformDispatcher, PlatformDisplay, PlatformKeyboardLayout, PlatformKeyboardMapper,
    PlatformTextSystem, PlatformWindow, Priority, PriorityQueueReceiver, PriorityQueueSender,
    RunnableVariant, Task, ThermalState, WindowAppearance, WindowParams, profiler,
};
use parking_lot::{Condvar, Mutex};

/// Android surfaces its keyboard through the IME, not a layout table, so there
/// is exactly one layout as far as gpui is concerned.
struct HeadlessKeyboardLayout;

impl PlatformKeyboardLayout for HeadlessKeyboardLayout {
    fn id(&self) -> &str {
        "headless"
    }

    fn name(&self) -> &str {
        "Headless"
    }
}

struct TimerEntry {
    due: Instant,
    seq: u64,
    runnable: RunnableVariant,
}

impl PartialEq for TimerEntry {
    fn eq(&self, other: &Self) -> bool {
        self.due == other.due && self.seq == other.seq
    }
}

impl Eq for TimerEntry {}

impl PartialOrd for TimerEntry {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

impl Ord for TimerEntry {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        // Reversed, so the earliest due time sits on top of the max-heap.
        other
            .due
            .cmp(&self.due)
            .then_with(|| other.seq.cmp(&self.seq))
    }
}

struct TimerQueue {
    state: Mutex<(BinaryHeap<TimerEntry>, u64)>,
    condvar: Condvar,
}

/// Background work on a thread pool, timers on a dedicated thread, foreground
/// work on a queue drained by the engine thread's pump.
pub struct HeadlessDispatcher {
    background_sender: PriorityQueueSender<RunnableVariant>,
    main_sender: PriorityQueueSender<RunnableVariant>,
    main_receiver: Mutex<PriorityQueueReceiver<RunnableVariant>>,
    /// Signalled whenever foreground work arrives, so the pump can park
    /// instead of polling.
    main_ready: Condvar,
    timers: Arc<TimerQueue>,
    main_thread_id: thread::ThreadId,
}

impl HeadlessDispatcher {
    /// Must be constructed on the thread that will run the pump — that thread
    /// becomes gpui's "main" thread.
    pub fn new() -> Self {
        let (background_sender, background_receiver) = PriorityQueueReceiver::new();
        let (main_sender, main_receiver) = PriorityQueueReceiver::new();

        let thread_count = thread::available_parallelism().map_or(2, |i| i.get().max(2));
        for i in 0..thread_count {
            let mut receiver: PriorityQueueReceiver<RunnableVariant> = background_receiver.clone();
            thread::Builder::new()
                .name(format!("thragg-worker-{i}"))
                .spawn(move || {
                    while let Ok(runnable) = receiver.pop() {
                        let location = runnable.metadata().location;
                        let spawned = runnable.metadata().spawned;
                        profiler::update_running_task(spawned, location);
                        runnable.run();
                        profiler::save_task_timing();
                    }
                })
                .expect("failed to spawn engine worker thread");
        }
        drop(background_receiver);

        let timers = Arc::new(TimerQueue {
            state: Mutex::new((BinaryHeap::new(), 0)),
            condvar: Condvar::new(),
        });
        {
            let timers = timers.clone();
            thread::Builder::new()
                .name("thragg-timer".to_owned())
                .spawn(move || {
                    let mut state = timers.state.lock();
                    loop {
                        let Some(entry) = state.0.peek() else {
                            timers.condvar.wait(&mut state);
                            continue;
                        };
                        let due = entry.due;
                        if due > Instant::now() {
                            timers.condvar.wait_until(&mut state, due);
                            continue;
                        }
                        let Some(entry) = state.0.pop() else { continue };
                        drop(state);
                        entry.runnable.run();
                        state = timers.state.lock();
                    }
                })
                .expect("failed to spawn engine timer thread");
        }

        Self {
            background_sender,
            main_sender,
            main_receiver: Mutex::new(main_receiver),
            main_ready: Condvar::new(),
            timers,
            main_thread_id: thread::current().id(),
        }
    }

    /// Run every queued foreground runnable, returning how many ran.
    fn drain_main_thread(&self) -> usize {
        let mut count = 0;
        let mut receiver = self.main_receiver.lock();
        while let Ok(Some(runnable)) = receiver.try_pop() {
            drop(receiver);
            runnable.run();
            count += 1;
            receiver = self.main_receiver.lock();
        }
        count
    }
}

impl Default for HeadlessDispatcher {
    fn default() -> Self {
        Self::new()
    }
}

impl PlatformDispatcher for HeadlessDispatcher {
    fn is_main_thread(&self) -> bool {
        thread::current().id() == self.main_thread_id
    }

    fn dispatch(&self, runnable: RunnableVariant, priority: Priority) {
        self.background_sender.send(priority, runnable).ok();
    }

    fn dispatch_on_main_thread(&self, runnable: RunnableVariant, priority: Priority) {
        self.main_sender.send(priority, runnable).ok();
        self.main_ready.notify_all();
    }

    fn dispatch_after(&self, duration: Duration, runnable: RunnableVariant) {
        let mut state = self.timers.state.lock();
        let seq = state.1;
        state.1 += 1;
        state.0.push(TimerEntry {
            due: Instant::now() + duration,
            seq,
            runnable,
        });
        self.timers.condvar.notify_all();
    }

    fn spawn_realtime(&self, f: Box<dyn FnOnce() + Send>) {
        thread::Builder::new()
            .name("thragg-realtime".to_owned())
            .spawn(f)
            .expect("failed to spawn engine realtime thread");
    }
}

pub struct HeadlessPlatform {
    dispatcher: Arc<HeadlessDispatcher>,
    background_executor: BackgroundExecutor,
    foreground_executor: ForegroundExecutor,
    text_system: Arc<NoopTextSystem>,
    quit: Mutex<bool>,
}

impl HeadlessPlatform {
    /// Must be constructed on the thread that will call `run`.
    pub fn new() -> Self {
        let dispatcher = Arc::new(HeadlessDispatcher::new());
        Self {
            background_executor: BackgroundExecutor::new(dispatcher.clone()),
            foreground_executor: ForegroundExecutor::new(dispatcher.clone()),
            dispatcher,
            text_system: Arc::new(NoopTextSystem::new()),
            quit: Mutex::new(false),
        }
    }
}

impl Default for HeadlessPlatform {
    fn default() -> Self {
        Self::new()
    }
}

impl Platform for HeadlessPlatform {
    fn background_executor(&self) -> BackgroundExecutor {
        self.background_executor.clone()
    }

    fn foreground_executor(&self) -> ForegroundExecutor {
        self.foreground_executor.clone()
    }

    fn text_system(&self) -> Arc<dyn PlatformTextSystem> {
        self.text_system.clone()
    }

    /// The engine thread's main loop: park until foreground work arrives, run
    /// it, repeat. Blocks until [`Platform::quit`].
    fn run(&self, on_finish_launching: Box<dyn 'static + FnOnce()>) {
        on_finish_launching();
        loop {
            if *self.quit.lock() {
                break;
            }
            if self.dispatcher.drain_main_thread() == 0 {
                // A timeout, rather than a plain wait, so a quit() that races
                // the park still gets noticed.
                let mut receiver = self.dispatcher.main_receiver.lock();
                self.dispatcher
                    .main_ready
                    .wait_for(&mut receiver, Duration::from_millis(50));
            }
        }
    }

    fn quit(&self) {
        *self.quit.lock() = true;
        self.dispatcher.main_ready.notify_all();
    }

    fn keyboard_layout(&self) -> Box<dyn PlatformKeyboardLayout> {
        Box::new(HeadlessKeyboardLayout)
    }

    fn keyboard_mapper(&self) -> Rc<dyn PlatformKeyboardMapper> {
        Rc::new(DummyKeyboardMapper)
    }

    fn on_keyboard_layout_change(&self, _callback: Box<dyn FnMut()>) {
        // Keyboard changes reach us through Compose's IME, not through gpui.
    }

    fn restart(&self, _binary_path: Option<PathBuf>) {}

    fn activate(&self, _ignoring_other_apps: bool) {}

    fn hide(&self) {}

    fn hide_other_apps(&self) {}

    fn unhide_other_apps(&self) {}

    fn displays(&self) -> Vec<Rc<dyn PlatformDisplay>> {
        Vec::new()
    }

    fn primary_display(&self) -> Option<Rc<dyn PlatformDisplay>> {
        None
    }

    fn active_window(&self) -> Option<AnyWindowHandle> {
        None
    }

    fn open_window(
        &self,
        _handle: AnyWindowHandle,
        _options: WindowParams,
    ) -> Result<Box<dyn PlatformWindow>> {
        unimplemented!("the engine platform is headless; all windows are Compose")
    }

    fn window_appearance(&self) -> WindowAppearance {
        Default::default()
    }

    fn open_url(&self, _url: &str) {}

    fn on_open_urls(&self, _callback: Box<dyn FnMut(Vec<String>)>) {}

    fn register_url_scheme(&self, _url: &str) -> Task<Result<()>> {
        Task::ready(Ok(()))
    }

    fn prompt_for_paths(
        &self,
        _options: PathPromptOptions,
    ) -> oneshot::Receiver<Result<Option<Vec<PathBuf>>>> {
        unimplemented!("file pickers are Android's (SAF), not gpui's")
    }

    fn prompt_for_new_path(
        &self,
        _directory: &Path,
        _suggested_name: Option<&str>,
    ) -> oneshot::Receiver<Result<Option<PathBuf>>> {
        unimplemented!("file pickers are Android's (SAF), not gpui's")
    }

    fn can_select_mixed_files_and_dirs(&self) -> bool {
        false
    }

    fn reveal_path(&self, _path: &Path) {}

    fn open_with_system(&self, _path: &Path) {}

    fn on_quit(&self, _callback: Box<dyn FnMut()>) {}

    fn on_reopen(&self, _callback: Box<dyn FnMut()>) {}

    fn on_system_wake(&self, _callback: Box<dyn FnMut()>) {}

    fn set_menus(&self, _menus: Vec<Menu>, _keymap: &Keymap) {}

    fn set_dock_menu(&self, _menu: Vec<MenuItem>, _keymap: &Keymap) {}

    fn on_app_menu_action(&self, _callback: Box<dyn FnMut(&dyn Action)>) {}

    fn on_will_open_app_menu(&self, _callback: Box<dyn FnMut()>) {}

    fn on_validate_app_menu_command(&self, _callback: Box<dyn FnMut(&dyn Action) -> bool>) {}

    fn thermal_state(&self) -> ThermalState {
        ThermalState::Nominal
    }

    fn on_thermal_state_change(&self, _callback: Box<dyn FnMut()>) {}

    fn app_path(&self) -> Result<PathBuf> {
        anyhow::bail!("the engine is a library, not an executable")
    }

    fn path_for_auxiliary_executable(&self, _name: &str) -> Result<PathBuf> {
        anyhow::bail!("auxiliary executables live in Android's nativeLibraryDir")
    }

    fn set_cursor_style(&self, _style: CursorStyle) {}

    fn hide_cursor_until_mouse_moves(&self) {}

    fn is_cursor_visible(&self) -> bool {
        false
    }

    fn should_auto_hide_scrollbars(&self) -> bool {
        false
    }

    fn read_from_clipboard(&self) -> Option<ClipboardItem> {
        // The clipboard is Android's, reached from Kotlin.
        None
    }

    fn write_to_clipboard(&self, _item: ClipboardItem) {}

    /// X11/Wayland's primary selection, which only exists in host builds.
    #[cfg(any(target_os = "linux", target_os = "freebsd"))]
    fn read_from_primary(&self) -> Option<ClipboardItem> {
        None
    }

    #[cfg(any(target_os = "linux", target_os = "freebsd"))]
    fn write_to_primary(&self, _item: ClipboardItem) {}

    fn write_credentials(&self, _url: &str, _username: &str, _password: &[u8]) -> Task<Result<()>> {
        Task::ready(Ok(()))
    }

    fn read_credentials(&self, _url: &str) -> Task<Result<Option<(String, Vec<u8>)>>> {
        Task::ready(Ok(None))
    }

    fn delete_credentials(&self, _url: &str) -> Task<Result<()>> {
        Task::ready(Ok(()))
    }
}
