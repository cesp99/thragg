package to.eyed.seeker.code.ui.workspace

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import to.eyed.seeker.code.ui.editor.EditorAction
import to.eyed.seeker.code.ui.terminal.TerminalAction

/**
 * Workspace-level keyboard commands, and the app's default keymap.
 *
 * The commands are [WorkspaceCommand]: one case per thing the workspace can
 * be asked to do, under Zed's action name. The keys are [DefaultKeymap]: the
 * same table Zed ships as `default-linux.json`, in Zed's own file format, and
 * **the single source of truth** for what a key does by default. Three
 * readers share it and cannot drift: the engine, which is handed it as text
 * and layers the base keymap and the user's `keymap.json` on top
 * (engine/src/keymap.rs); the command palette and the menus, which print the
 * chord beside each command from the resolved result; and `zed: open default
 * keymap`, which opens the same text as a tab.
 *
 * Which keys the workspace matches, and where, is decided by context
 * (docs/src/key-bindings.md:143-160), of which this app has four —
 * `Workspace`, `Pane`, `Editor` and `Terminal` — plus sections with no
 * context. The terminal is the one to know about: every plain
 * `Ctrl+<letter>` means something to a shell — C interrupts, D ends input,
 * A and E jump to the ends of the line, U and K and W kill, R searches
 * history, P and N walk it, L clears, Z suspends — so a focused terminal
 * hears **only** `Terminal` bindings ([KeymapContext.chainFor]), and the
 * defaults give it a short list: chords no terminal uses (`Ctrl+\``,
 * `Ctrl+Tab`, `Ctrl+PageUp/Down`) and `Ctrl+Shift+<letter>` twins of the
 * ordinary commands. Escape, `Alt+key` and the function keys go to the shell
 * untouched, because vi and htop need them.
 *
 * Seeker IDE targets foldables, tablets and DeX, where a keyboard and
 * mouse are ordinary rather than exotic. Anything reachable by touch should
 * be reachable from the keyboard too — see the convention in
 * agent-docs/CONVENTIONS.md and the user-facing list in docs/SHORTCUTS.md.
 * Keep that list in sync with [DefaultKeymap].
 */
enum class WorkspaceCommand(
    /**
     * Zed's own action name for this command, and its stable identity: the
     * palette humanises it into what the user reads and searches, so
     * `terminal_panel::Toggle` shows as "terminal panel: toggle", and
     * keymap.json binds it by this name.
     *
     * **Adding a command is these two lines and nothing else**: a case here
     * with an id, and a branch in `WorkspaceScreen.runCommand`. The palette
     * picks it up from [entries] on its own — there is no second registry to
     * forget. Give it a chord in [DefaultKeymap] if it deserves one.
     */
    val id: String,
    /**
     * Whether this build offers the command at all. Default yes; the one
     * exception is cloning, which needs a Linux userland — an editor should
     * not advertise, even greyed out, something it cannot ever do.
     */
    val isOffered: (CommandContext) -> Boolean = { true },
    /**
     * Whether it can run *right now*. False greys it in the palette rather
     * than hiding it, as Zed does: a command that needs a project should tell
     * you it exists and why it is unavailable, not vanish.
     */
    val isAvailable: (CommandContext) -> Boolean = { true },
) {
    /** Write the active file to disk. */
    Save("workspace::Save", isAvailable = { it.hasActiveBuffer }),

    /** Close the active tab. */
    CloseTab("pane::CloseActiveItem", isAvailable = { it.hasActiveFile }),

    NextTab("pane::ActivateNextItem", isAvailable = { it.tabCount > 1 }),
    PreviousTab("pane::ActivatePreviousItem", isAvailable = { it.tabCount > 1 }),

    /**
     * Back along the navigation history — the tab and place you were before.
     * The tab bar's `←` is the same command with a mouse.
     */
    GoBack("pane::GoBack", isAvailable = { it.canGoBack }),

    /** Forward again, replaying what GoBack stepped out of. */
    GoForward("pane::GoForward", isAvailable = { it.canGoForward }),

    /**
     * Create a file in the project and open it — Zed's `workspace::NewFile`,
     * which the tab bar's `+` leads with (pane.rs:4272). The file lands at
     * the project root unless the name typed is a path.
     */
    NewFile("workspace::NewFile", isAvailable = { it.hasProject }),

    /** Close every other tab. Pinned ones survive, as in Zed. */
    CloseOtherTabs("pane::CloseOtherItems", isAvailable = { it.tabCount > 1 }),

    /** Close the tabs to the right of the active one. */
    CloseTabsToTheRight("pane::CloseItemsToTheRight", isAvailable = { it.tabCount > 1 }),

    /** Close the tabs to the left of it — Zed's `ctrl-k e`. */
    CloseTabsToTheLeft("pane::CloseItemsToTheLeft", isAvailable = { it.tabCount > 1 }),

    /** Close every tab with nothing to lose — Zed's `ctrl-k u`. */
    CloseCleanTabs("pane::CloseCleanItems", isAvailable = { it.tabCount > 0 }),

    /**
     * The most-recently-used tab switcher. Ctrl+Tab holds it open and its
     * release commits, which the keymap cannot express — the workspace's own
     * pre-IME pass matches the chord (see `isTabSwitcher`) before the keymap
     * is asked. The binding is still in [DefaultKeymap] so the chord is
     * Zed's, prints in the palette and appears in the default keymap file;
     * reached that way — the palette, the ☰ menu, the strip's ⇥ — the overlay
     * stays up until a row is tapped, because no Ctrl is being held.
     */
    OpenTabSwitcher("tab_switcher::Toggle", isAvailable = { it.tabCount > 1 }),

    /** A shell in the active file's directory — Zed's `OpenInTerminal`. */
    OpenInTerminal("project_panel::OpenInTerminal", isAvailable = { it.hasProject }),

    /** Close every tab. Pinned ones survive. */
    CloseAllTabs("pane::CloseAllItems", isAvailable = { it.tabCount > 0 }),

    /** Pin the active tab, or unpin it. Pinned tabs sit left. */
    TogglePinTab("pane::TogglePinTab", isAvailable = { it.hasActiveFile }),

    /** Reopen the tab closed most recently. */
    ReopenClosedTab("pane::ReopenClosedItem", isAvailable = { it.hasProject }),

    /** Show the active file in the project panel, and give the panel focus. */
    RevealInProjectPanel("pane::RevealInProjectPanel", isAvailable = { it.hasActiveFile }),

    /**
     * Split the active pane — Zed's `pane::SplitRight` and its three
     * siblings (pane.rs:240-244), on Zed's Atom-derived `ctrl-k` arrow chords
     * (default-linux.json:816-819) and `ctrl-\` for the right
     * (:596). Zed's default mode clones the active item into the new pane
     * (`SplitMode::ClonePane`, pane.rs:216-224): the same buffer, a second
     * caret. A tab that cannot be cloned — a diff, the graph — moves
     * instead when it has company, and with nothing else in the pane an
     * empty pane opens on the *far* side so the item ends up on the side
     * asked for (`Pane::split`, pane.rs:2572-2596).
     */
    SplitRight("pane::SplitRight", isAvailable = { it.hasProject }),
    SplitLeft("pane::SplitLeft", isAvailable = { it.hasProject }),
    SplitUp("pane::SplitUp", isAvailable = { it.hasProject }),
    SplitDown("pane::SplitDown", isAvailable = { it.hasProject }),

    /**
     * Focus the pane past the active one's edge — Zed's
     * `workspace::ActivatePaneLeft` family on `ctrl-k ctrl-<arrow>`
     * (default-linux.json:710-713). Greyed with one pane: there is no edge
     * to cross.
     */
    ActivatePaneLeft("workspace::ActivatePaneLeft", isAvailable = { it.paneCount > 1 }),
    ActivatePaneRight("workspace::ActivatePaneRight", isAvailable = { it.paneCount > 1 }),
    ActivatePaneUp("workspace::ActivatePaneUp", isAvailable = { it.paneCount > 1 }),
    ActivatePaneDown("workspace::ActivatePaneDown", isAvailable = { it.paneCount > 1 }),

    /**
     * The next and previous pane in tree order, wrapping — Zed's
     * `workspace::ActivateNextPane` / `ActivatePreviousPane`
     * (workspace.rs:5459-5475), which Zed leaves unbound on Linux too.
     */
    ActivateNextPane("workspace::ActivateNextPane", isAvailable = { it.paneCount > 1 }),
    ActivatePreviousPane("workspace::ActivatePreviousPane", isAvailable = { it.paneCount > 1 }),

    /**
     * Exchange the active pane with its neighbour — Zed's
     * `workspace::SwapPaneLeft` family on `ctrl-k shift-<arrow>`
     * (default-linux.json:714-717).
     */
    SwapPaneLeft("workspace::SwapPaneLeft", isAvailable = { it.paneCount > 1 }),
    SwapPaneRight("workspace::SwapPaneRight", isAvailable = { it.paneCount > 1 }),
    SwapPaneUp("workspace::SwapPaneUp", isAvailable = { it.paneCount > 1 }),
    SwapPaneDown("workspace::SwapPaneDown", isAvailable = { it.paneCount > 1 }),

    /**
     * Fold the active pane's tabs into its neighbour — Zed's
     * `pane::JoinIntoNext` (workspace.rs:6093-6109: right, then down, left,
     * up) — or every pane's into the active one, `pane::JoinAll`
     * (6082-6091). Unbound in Zed; the tab bar's split menu carries them.
     */
    JoinIntoNext("pane::JoinIntoNext", isAvailable = { it.paneCount > 1 }),
    JoinAll("pane::JoinAll", isAvailable = { it.paneCount > 1 }),

    /**
     * The active pane alone in the work area, or back — Zed's
     * `workspace::ToggleZoom` on `shift-escape` (default-linux.json:26).
     * Zed's zoom also covers docks; here it is the centre's, and the split
     * menu's **Zoom** is the same thing for a finger.
     */
    ToggleZoom("workspace::ToggleZoom", isAvailable = { it.hasActiveFile || it.isZoomed }),

    /**
     * Hand the active file to another app — Zed's `workspace::OpenWithSystem`,
     * the project panel's "Open in Default App" (project_panel.rs:1161,
     * 3936). Zed hands the path to the platform opener; Android has no
     * default-app opener for a private file, so this is an ACTION_VIEW
     * chooser over a FileProvider URI (core/ShareOut.kt). The panel's own
     * row acts on the *selected* entry; this command acts on the active tab.
     */
    OpenWithSystem("workspace::OpenWithSystem", isAvailable = { it.activeFileOnDisk }),

    /**
     * Android's share sheet for the active file. No Zed counterpart — a
     * desktop has no share sheet — so the id is ours, in the panel's
     * namespace because that is where the row lives in Zed's menu. The
     * "Android-only" commands are listed in docs/SHORTCUTS.md.
     */
    Share("project_panel::Share", isAvailable = { it.activeFileOnDisk }),

    /** Show or hide the project panel. */
    ToggleProjectPanel(
        "project_panel::Toggle",
        isAvailable = { "project_panel" !in it.hiddenPanels },
    ),

    /**
     * Show or hide the outline panel — Zed's `outline_panel::ToggleFocus` on
     * Zed's own chord, `ctrl-shift-b` (default-linux.json:700). The panel is
     * the file's symbol tree as a dock; `Ctrl+Shift+O` is still the picker
     * over the same symbols, exactly as Zed keeps both.
     *
     * Available with a buffer open: an outline of a picture, a diff or the
     * graph is an outline of nothing.
     */
    ToggleOutlinePanel(
        "outline_panel::ToggleFocus",
        isAvailable = { it.hasActiveBuffer && "outline_panel" !in it.hiddenPanels },
    ),

    /**
     * Clear the toast stack — Zed's `workspace::ClearAllNotifications`
     * (workspace.rs:275, 7954). No chord in Zed either: it is a palette
     * command, and every toast has its own X.
     */
    ClearAllNotifications(
        "workspace::ClearAllNotifications",
        isAvailable = { it.hasNotifications },
    ),

    /** Open the project picker (switch, create, import, export). */
    OpenProjects("projects::Open"),

    /**
     * The recent-projects picker — Zed's `projects::OpenRecent`
     * (default-linux.json:639), a fuzzy list of the projects you have opened
     * before, newest first, with Zed's "Remove from Recent Projects" on each
     * row.
     */
    OpenRecent("projects::OpenRecent"),

    /**
     * Close the workspace and go back to the picker — Zed's
     * `workspace::CloseWindow` (default-linux.json:25), which on a platform
     * with one window means the window's *contents*: the project is closed,
     * its session written down, and the picker is what is left.
     */
    CloseWindow("workspace::CloseWindow", isAvailable = { it.hasProject }),

    /** Open the fuzzy file finder. */
    FindFile("file_finder::Toggle", isAvailable = { it.hasProject }),

    /**
     * Find within the open file — Zed's buffer search. While a shell has the
     * keyboard it is the *terminal's* search, over the scrollback: Zed binds
     * the same action in its Terminal context (default-linux.json:1281-1282).
     */
    FindInFile("buffer_search::Deploy", isAvailable = { it.hasActiveBuffer || it.terminalCount > 0 }),

    /**
     * The same bar with its replace row open — Zed's `DeployReplace`, which
     * is `Deploy` with `replace_enabled` (buffer_search.rs:881, 896-899).
     */
    FindAndReplaceInFile("buffer_search::DeployReplace", isAvailable = { it.hasActiveBuffer }),

    /**
     * The four commands below act on the open search bar, so they are
     * available only while it is up: the palette greys them otherwise, as
     * Zed's are unreachable without the `BufferSearchBar` context.
     */
    ToggleReplace("search::ToggleReplace", isAvailable = { it.searchBarOpen }),
    ReplaceNext("search::ReplaceNext", isAvailable = { it.searchBarOpen }),
    ReplaceAll("search::ReplaceAll", isAvailable = { it.searchBarOpen }),
    SelectAllMatches("search::SelectAllMatches", isAvailable = { it.searchBarOpen }),

    /**
     * Show or hide the preview of the open file.
     *
     * One command for both previews, as Zed has one button for both: which of
     * them appears is a property of the file, not a choice the user makes.
     * Unavailable — greyed, not hidden — on a file with neither.
     */
    TogglePreview(
        "seeker::TogglePreview",
        isAvailable = { it.canPreview && "preview" !in it.hiddenPanels },
    ),

    /** Open the settings screen. */
    OpenSettings("seeker::OpenSettings"),

    /**
     * The welcome screen again — Zed's `zed::OpenOnboarding`
     * (crates/onboarding). Shown once on a fresh install and then only when
     * asked for, which is what this command and the ☰ entry are for: an
     * onboarding you cannot get back to is one nobody dares dismiss.
     */
    OpenOnboarding("zed::OpenOnboarding"),

    /**
     * About — Zed's `zed::About`: the version, and the system specs a bug
     * report needs, with a button that copies them.
     */
    About("zed::About"),

    /**
     * Open settings.json itself as an editor tab — Zed's
     * `zed::OpenSettingsFile` (zed/src/zed.rs:261). Not a convenience: the
     * file lives in app-private storage no other editor on the device can
     * reach, and it is the only place `agent_servers` and anything else
     * without a settings-screen row can be written at all. Needs a project
     * only because the tab strip does.
     */
    OpenSettingsFile("zed::OpenSettingsFile", isAvailable = { it.hasProject }),

    /**
     * Open the built-in default settings as a read-only tab — Zed's
     * `zed::OpenDefaultSettings` (zed/src/zed.rs:306-316). The commented
     * text is the documentation of every key there is; showing it beside
     * the user's own file is how Zed answers "what can I set here".
     */
    OpenDefaultSettings("zed::OpenDefaultSettings", isAvailable = { it.hasProject }),

    /**
     * Open — creating it on the way — the project's own `.zed/settings.json`,
     * Zed's `zed::OpenProjectSettings` (zed/src/zed.rs `OpenProjectSettings`).
     * What it holds overrides the user file for this project only, and only
     * for the editor, language, lsp and git keys.
     */
    OpenProjectSettings("zed::OpenProjectSettings", isAvailable = { it.hasProject }),

    /**
     * Open keymap.json as a tab — Zed's `zed::OpenKeymap`, on its chord
     * (default-linux.json:687). Saving the tab is what applies it, exactly
     * as with settings.json. Needs a project only because the tab strip
     * does.
     */
    OpenKeymap("zed::OpenKeymap", isAvailable = { it.hasProject }),

    /**
     * Open the built-in default keymap as a tab — Zed's
     * `zed::OpenDefaultKeymap`, which opens its `default-linux.json` to read
     * and copy from. The text is [DefaultKeymap] rendered, so it is the
     * table the keyboard actually uses and cannot drift from it.
     */
    OpenDefaultKeymap("zed::OpenDefaultKeymap", isAvailable = { it.hasProject }),

    /** Pick a theme, previewing each as the selection moves — Zed's own. */
    SelectTheme("theme_selector::Toggle"),

    /**
     * Add another folder to the open project — Zed's
     * `workspace::AddFolderToProject`, which makes the project multi-root
     * (its `Project` holds a `Vec<Worktree>`).
     *
     * No chord in Zed either, and none here: the palette, the project panel's
     * `+` and its context menu are the ways in. The engine needs a real path,
     * so a folder from the device is copied into app storage first — the
     * dialog says so.
     */
    AddFolderToProject("workspace::AddFolderToProject", isAvailable = { it.hasProject }),

    /**
     * Stop showing one of those folders — Zed's
     * `workspace::RemoveWorktreeFromProject`. Greyed until the project has a
     * folder that can go: the one it was opened with cannot.
     *
     * This is [isAvailable] rather than [isOffered] on purpose. Hiding is for
     * what this *build* cannot do; a single-folder project is a state, and a
     * command that disappears in that state is one nobody discovers — you
     * would have to add a folder before the palette would admit that removing
     * one is possible. See the rule on [CommandPalette].
     */
    RemoveFolderFromProject(
        "workspace::RemoveWorktreeFromProject",
        isAvailable = { it.hasExtraFolders },
    ),

    /**
     * Choose the line ending the active file is saved with — Zed's
     * `line_ending_selector::Toggle`, which its status bar's `LF` / `CRLF`
     * item dispatches (line_ending_indicator.rs:44-49) and so does ours. No
     * chord in Zed either; the palette and the bar are the two ways in.
     */
    SelectLineEnding("line_ending_selector::Toggle", isAvailable = { it.hasActiveBuffer }),

    /**
     * Reopen the active file in another encoding, or save it in one — Zed's
     * `encoding_selector::Toggle`, from its status bar's encoding item
     * (active_buffer_encoding.rs:90-100), on Zed's own `ctrl-k n`
     * (default-linux.json:706).
     */
    SelectEncoding("encoding_selector::Toggle", isAvailable = { it.hasActiveBuffer }),

    /**
     * Choose the language the active buffer is parsed as — Zed's
     * `language_selector::Toggle` on `ctrl-k m` (default-linux.json:707),
     * which its status bar's language item dispatches too
     * (active_buffer_language.rs:75-84). A buffer override, like Zed's: it
     * changes nothing on disk and nothing in settings.
     */
    SelectLanguage("language_selector::Toggle", isAvailable = { it.hasActiveBuffer }),

    /**
     * Choose the interpreter the project runs with — Zed's
     * `toolchain::Select` (crates/toolchain_selector), which its status bar's
     * toolchain item dispatches too (active_toolchain.rs). No chord, as in
     * Zed: `ctrl-k ctrl-m` there is `toolchain::AddToolchain`, a different
     * action, and inventing one here would collide the day we add it.
     */
    SelectToolchain("toolchain::Select", isAvailable = { it.hasProject }),

    /**
     * Clone a git repository into a new project.
     *
     * In the table like everything else, and refused at the point of use where
     * there is no Linux userland to run git in — the same way FindFile is
     * refused with no project open. No chord: `Ctrl+Shift+G` is the git panel
     * in Zed and is the git panel here, and cloning is a thing one does once
     * per repository — the palette and the picker's footer are enough.
     */
    CloneRepository("git::Clone", isOffered = { it.canClone }),

    /**
     * Install a language server from apt — Zed asks before installing the
     * extension for a language (extension_suggest.rs:176) and so do we;
     * nothing here ever downloads on its own.
     *
     * No chord, for CloneRepository's reason: it is done once per language,
     * and the two ways in are the palette and the status bar saying a server
     * is missing. Absent, not greyed, where there is no userland to run apt.
     */
    InstallLanguageServer(
        "seeker::InstallLanguageServer",
        isOffered = { it.canInstallLanguageServer },
    ),

    /**
     * Wrap long lines, or stop — Zed's `editor::ToggleSoftWrap`, on Zed's
     * own chords `ctrl-k z` and `ctrl-k ctrl-z` (default-linux.json:138-139).
     * It writes the setting, so it survives a restart the way Zed's does.
     */
    ToggleSoftWrap("editor::ToggleSoftWrap"),

    /**
     * Modal editing on or off — Zed's `workspace::ToggleVimMode`, which
     * toggles `vim_mode` in the user's settings (docs/src/vim.md "Enabling
     * and disabling vim mode"). No chord in Zed, none here: it is the
     * palette, the ☰ menu and the settings screen's row.
     */
    ToggleVimMode("workspace::ToggleVimMode"),

    /**
     * The UI font size, which is Zed's rem: `window.rem_size = ui_font_size`
     * (theme_settings/src/settings.rs:619), so these grow and shrink the whole
     * chrome — rows, bars, gaps and icons — not only the text.
     *
     * **No chord.** Zed binds `ctrl-=` and its neighbours to these only in
     * its Onboarding and Welcome contexts (default-linux.json:1418-1421,
     * 1432-1435); everywhere else that chord is the *buffer* font's, and this
     * app follows. The palette and the Appearance section of the settings
     * screen are the two ways in, and a user who wants the chord back writes
     * three lines of keymap.json.
     */
    IncreaseUiFontSize("zed::IncreaseUiFontSize"),
    DecreaseUiFontSize("zed::DecreaseUiFontSize"),
    ResetUiFontSize("zed::ResetUiFontSize"),

    /**
     * The editor's own text size — Zed's global `ctrl-=` / `ctrl-+` /
     * `ctrl--` / `ctrl-0` (default-linux.json:30-33).
     *
     * Zed passes `{ "persist": false }`: the chords move a delta over
     * `buffer_font_size` rather than rewriting the setting, so the size the
     * settings screen shows stays the one you chose. Pinching the editor
     * surface drives the same delta, which is the touch half of these three.
     */
    IncreaseBufferFontSize("zed::IncreaseBufferFontSize"),
    DecreaseBufferFontSize("zed::DecreaseBufferFontSize"),
    ResetBufferFontSize("zed::ResetBufferFontSize"),

    /**
     * Pick an icon theme — Zed's `icon_theme_selector::Toggle`. No chord in
     * Zed either; it is a palette command and a settings row, because an icon
     * theme is chosen about once.
     */
    SelectIconTheme("icon_theme_selector::Toggle"),

    /**
     * Zed's image viewer actions (image_viewer.rs:39-52), which exist only
     * while a picture is the open tab — Zed scopes them to its `ImageViewer`
     * context, and the palette greys them out everywhere else. Their chords
     * are the buffer font size's, and win over it while a picture is open, as
     * Zed's context does: see the `ImageViewer` section of [DefaultKeymap].
     */
    ImageZoomIn("image_viewer::ZoomIn", isAvailable = { it.hasActiveImage }),
    ImageZoomOut("image_viewer::ZoomOut", isAvailable = { it.hasActiveImage }),
    ImageResetZoom("image_viewer::ResetZoom", isAvailable = { it.hasActiveImage }),
    ImageZoomToActualSize("image_viewer::ZoomToActualSize", isAvailable = { it.hasActiveImage }),
    ImageFitToView("image_viewer::FitToView", isAvailable = { it.hasActiveImage }),

    /**
     * The commit graph — Zed's `git::OpenGraph`, which it opens as a pane item
     * and so does this: it is a view of the repository, read and scrolled.
     */
    OpenGitGraph("git::OpenGraph", isAvailable = { it.hasProject }),

    /**
     * The branch picker — Zed's `git::Switch`, which the git panel's branch
     * button and the title bar's branch chip both dispatch, on the chord Zed
     * gives its `branches::OpenRecent` alias (default-linux.json:644).
     */
    SwitchBranch("git::Switch", isAvailable = { it.hasProject }),

    /**
     * The remote family — Zed's `git::Fetch`, `git::Pull`, `git::PullRebase`,
     * `git::Push` and `git::ForcePush`, registered on the workspace there
     * (git_ui.rs:193-241) and handed to the git panel here, which owns the
     * session, the single-flight busy flag and the strip that says what git
     * answered. Their chords are the panel's ctrl-g leader sequences, scoped
     * to the panel exactly as Zed scopes them (default-linux.json:1060-1066)
     * — a `GitPanel` context the keymap does not have, so no chord prints
     * beside them and the panel's split button menu carries the labels
     * instead.
     *
     * Offered only where there is a userland to run git in, like cloning;
     * greyed while the git panel is switched off, like its toggle — the
     * commands run *in* the panel, so a hidden panel means them too.
     */
    GitFetch(
        "git::Fetch",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),
    GitPull(
        "git::Pull",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),
    GitPullRebase(
        "git::PullRebase",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),
    GitPush(
        "git::Push",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),
    GitForcePush(
        "git::ForcePush",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),

    /**
     * The bulk stages — Zed's `git::StageAll` / `git::UnstageAll`, whose
     * chords (`ctrl-space` / `ctrl-shift-space`, default-linux.json:
     * 1070-1071) live in the panel's own key handler because they are
     * panel-scoped there too: in the editor `ctrl-space` is completions.
     */
    GitStageAll(
        "git::StageAll",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),
    GitUnstageAll(
        "git::UnstageAll",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),

    /**
     * The whole project's diff as a tab — Zed's `git::Diff`, the panel's own
     * "View Diff" button and the `ctrl-g d` chord (default-linux.json:1067).
     * Routed through the panel like the remote family, so the one dispatcher
     * serves every way in.
     */
    GitDiff(
        "git::Diff",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),

    /**
     * The editor's own text and line commands, as palette rows forwarded to
     * the active editor under the same id — the keymap's route, so the two
     * cannot drift. Zed gives `SelectLine`, `Rewrap`, the two syntax-node
     * commands and `MoveToEnclosingBracket` chords (default-linux.json:111,
     * 66-67, 547-548, 573) and leaves the sorts, the filters, the case
     * conversions and `Transpose` to its own palette; both kinds are here,
     * because the palette is this app's touch route to everything.
     */
    SelectLine("editor::SelectLine", isAvailable = { it.hasActiveBuffer }),
    SelectLargerSyntaxNode("editor::SelectLargerSyntaxNode", isAvailable = { it.hasActiveBuffer }),
    SelectSmallerSyntaxNode("editor::SelectSmallerSyntaxNode", isAvailable = { it.hasActiveBuffer }),
    MoveToEnclosingBracket("editor::MoveToEnclosingBracket", isAvailable = { it.hasActiveBuffer }),
    SortLinesCaseSensitive("editor::SortLinesCaseSensitive", isAvailable = { it.hasActiveBuffer }),
    SortLinesCaseInsensitive(
        "editor::SortLinesCaseInsensitive",
        isAvailable = { it.hasActiveBuffer },
    ),
    ReverseLines("editor::ReverseLines", isAvailable = { it.hasActiveBuffer }),
    ShuffleLines("editor::ShuffleLines", isAvailable = { it.hasActiveBuffer }),
    UniqueLinesCaseSensitive(
        "editor::UniqueLinesCaseSensitive",
        isAvailable = { it.hasActiveBuffer },
    ),
    UniqueLinesCaseInsensitive(
        "editor::UniqueLinesCaseInsensitive",
        isAvailable = { it.hasActiveBuffer },
    ),
    Transpose("editor::Transpose", isAvailable = { it.hasActiveBuffer }),
    Rewrap("editor::Rewrap", isAvailable = { it.hasActiveBuffer }),
    ConvertToUpperCase("editor::ConvertToUpperCase", isAvailable = { it.hasActiveBuffer }),
    ConvertToLowerCase("editor::ConvertToLowerCase", isAvailable = { it.hasActiveBuffer }),
    ConvertToTitleCase("editor::ConvertToTitleCase", isAvailable = { it.hasActiveBuffer }),
    ConvertToSnakeCase("editor::ConvertToSnakeCase", isAvailable = { it.hasActiveBuffer }),
    ConvertToKebabCase("editor::ConvertToKebabCase", isAvailable = { it.hasActiveBuffer }),
    ConvertToUpperCamelCase(
        "editor::ConvertToUpperCamelCase",
        isAvailable = { it.hasActiveBuffer },
    ),
    ConvertToLowerCamelCase(
        "editor::ConvertToLowerCamelCase",
        isAvailable = { it.hasActiveBuffer },
    ),
    ConvertToOppositeCase("editor::ConvertToOppositeCase", isAvailable = { it.hasActiveBuffer }),

    /**
     * The editor's display switches — Zed's `editor::ToggleLineNumbers`
     * (`ctrl-;`), `ToggleRelativeLineNumbers`, `ToggleMinimap` and
     * `ToggleInlineDiagnostics`. Each flips the active editor and leaves
     * settings.json alone, as Zed's do; the settings screen is where the
     * default lives.
     */
    ToggleLineNumbers("editor::ToggleLineNumbers", isAvailable = { it.hasActiveBuffer }),
    ToggleRelativeLineNumbers(
        "editor::ToggleRelativeLineNumbers",
        isAvailable = { it.hasActiveBuffer },
    ),
    ToggleMinimap("editor::ToggleMinimap", isAvailable = { it.hasActiveBuffer }),
    ToggleInlineDiagnostics(
        "editor::ToggleInlineDiagnostics",
        isAvailable = { it.hasActiveBuffer },
    ),

    /**
     * Git in the editor — Zed's hunk motions and hunk blocks
     * (`editor::GoToHunk`, `GoToPreviousHunk`, `ExpandAllDiffHunks`,
     * `ToggleSelectedDiffHunks`) and the per-hunk commands its editor
     * registers (`git::ToggleStaged`, `StageAndNext`, `UnstageAndNext`,
     * `Restore`, `git::Blame`). Their chords are in the Editor section, under
     * the same ids; the palette rows here forward to the active editor, which
     * is what makes them touch-reachable from anywhere. The gutter's diff
     * bar and the expanded hunk's header buttons are the other touch routes.
     */
    GoToHunk("editor::GoToHunk", isAvailable = { it.hasActiveBuffer }),
    GoToPreviousHunk("editor::GoToPreviousHunk", isAvailable = { it.hasActiveBuffer }),
    ExpandAllDiffHunks("editor::ExpandAllDiffHunks", isAvailable = { it.hasActiveBuffer }),
    ToggleSelectedDiffHunks("editor::ToggleSelectedDiffHunks", isAvailable = { it.hasActiveBuffer }),
    GitToggleStaged("git::ToggleStaged", isOffered = { it.canClone }, isAvailable = { it.hasActiveBuffer }),
    GitStageAndNext("git::StageAndNext", isOffered = { it.canClone }, isAvailable = { it.hasActiveBuffer }),
    GitUnstageAndNext("git::UnstageAndNext", isOffered = { it.canClone }, isAvailable = { it.hasActiveBuffer }),
    GitRestore("git::Restore", isOffered = { it.canClone }, isAvailable = { it.hasActiveBuffer }),
    GitBlame("git::Blame", isOffered = { it.canClone }, isAvailable = { it.hasActiveBuffer }),

    /**
     * The stash — Zed's `git::StashAll` / `StashTracked` / `StashStaged`
     * (each asks for a message first, as Zed's `StashMessageModal` does),
     * `StashPop`, `StashApply`, and `git::ViewStash`, the stash picker
     * (git_panel.rs:2897-2990, stash_picker.rs). No chords in Zed's Linux
     * keymap either; the panel's **Stash** menu and the palette are the
     * routes. The pushes and pops run in the panel like the remote family,
     * so what git says back has somewhere to be seen; the picker is a
     * modal of the workspace, like the branch picker.
     */
    GitStashAll(
        "git::StashAll",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),
    GitStashTracked(
        "git::StashTracked",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),
    GitStashStaged(
        "git::StashStaged",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),
    GitStashPop(
        "git::StashPop",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),
    GitStashApply(
        "git::StashApply",
        isOffered = { it.canClone },
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),
    GitViewStash("git::ViewStash", isOffered = { it.canClone }, isAvailable = { it.hasProject }),

    /**
     * The merge-conflict family. Zed has no actions for these — its conflict
     * view is buttons on a block above each conflict and nothing else
     * (git_ui/src/conflict_view.rs:334-380), so there is no name to borrow
     * and no chord to copy. These are named in Zed's style, in the namespace
     * its conflict view lives in, and are palette-only: the header's buttons
     * are the touch route to the takes, and the action row's `conflict↑` /
     * `conflict↓` keys the touch route to the motions.
     *
     * Greyed rather than hidden while the open file has no conflicts, which
     * is nearly always: the palette is the honest list of what exists.
     */
    GoToNextConflict("git::GoToNextConflict", isAvailable = { it.hasConflicts }),
    GoToPreviousConflict("git::GoToPreviousConflict", isAvailable = { it.hasConflicts }),
    /** Resolve the conflict under the caret keeping ours — the header's first button. */
    ConflictUseOurs("git::UseOurs", isAvailable = { it.hasConflicts }),
    /** Keeping theirs — the header's second button. */
    ConflictUseTheirs("git::UseTheirs", isAvailable = { it.hasConflicts }),
    /** Keeping both, ours first — the header's third. */
    ConflictUseBoth("git::UseBoth", isAvailable = { it.hasConflicts }),

    /**
     * Show or hide the git panel — Zed's `git_panel::ToggleFocus`, on the
     * chord Zed gives it (default-linux.json:700).
     */
    ToggleGitPanel(
        "git_panel::ToggleFocus",
        isAvailable = { it.hasProject && "git_panel" !in it.hiddenPanels },
    ),

    /**
     * Open the diagnostics tab — Zed's `diagnostics::Deploy`, which deploys
     * project diagnostics into the active pane and which its status-bar
     * summary dispatches (diagnostics/src/items.rs:53-56); so does ours:
     * tapping the error and warning counts is the tab's way in. No chord —
     * Zed's `ctrl-shift-m` went to the preview here first, and the summary is
     * one tap from anywhere.
     */
    OpenDiagnostics(
        "diagnostics::Deploy",
        isAvailable = { it.hasProject },
    ),

    /**
     * The workspace symbol picker — Zed's `project_symbols::Toggle`, on its
     * chord `ctrl-t` (default-linux.json:691): `workspace/symbol` across
     * every running server of the project. A command rather than a
     * [WorkspaceAction] so the palette lists it, which is the touch route.
     */
    ProjectSymbols("project_symbols::Toggle", isAvailable = { it.hasProject }),

    /**
     * Zed's `editor::ToggleInlayHints` (default-linux.json:922, `ctrl-:`):
     * flips `inlay_hints.enabled` in settings.json, so every editor follows.
     */
    ToggleInlayHints("editor::ToggleInlayHints"),

    /**
     * Zed's `editor::RestartLanguageServer`: stop and start again the
     * servers of the active file's language — every server of the project
     * when no file is open. The status bar's menu does the same per server.
     */
    RestartLanguageServer("editor::RestartLanguageServer", isAvailable = { it.hasProject }),

    /**
     * Zed's `editor::StopLanguageServer`: the same servers, stopped and kept
     * stopped until restarted by hand.
     */
    StopLanguageServer("editor::StopLanguageServer", isAvailable = { it.hasProject }),

    /**
     * Zed's `dev::OpenLanguageServerLogs` (language_tools/src/lsp_log_view.rs):
     * the log tab of the active file's server — its stderr, its log messages
     * and the RPC trace.
     */
    OpenLanguageServerLogs("dev::OpenLanguageServerLogs", isAvailable = { it.hasProject }),

    /**
     * Leave a multibuffer for the file the caret's excerpt came from — Zed's
     * `editor::OpenExcerpts` on its own chord, `alt-enter`
     * (assets/keymaps/default-linux.json:915). Available only in a
     * multibuffer; anywhere else the chord falls through to the editor, where
     * Alt+Enter has always been a plain newline.
     */
    OpenExcerpt("editor::OpenExcerpts", isAvailable = { it.isMultibuffer }),

    /**
     * Show or hide the agent panel — Zed's `agent::ToggleFocus`, on the chord
     * Zed gives it (default-linux.json: `ctrl-?`, which a phone keyboard
     * cannot reach, so this takes Zed's *other* agent chord `ctrl-alt-a`).
     *
     * Offered only where an agent could run at all: the `play` edition has no
     * userland, so it has no agent panel and is not shown one greyed out.
     */
    ToggleAgentPanel(
        "agent::ToggleFocus",
        isOffered = { it.canUseAgent },
        isAvailable = { it.hasProject && "agent_panel" !in it.hiddenPanels },
    ),

    /**
     * Start another thread with the agent showing — Zed's `agent::NewThread`
     * on `ctrl-n` in the `AgentPanel` context (default-linux.json:220). The
     * chord is the same key as `workspace::NewFile`, and Zed settles that by
     * context; so does this keymap: the binding is in the
     * [KeymapContext.AgentPanel] section, active only while the composer
     * holds the keyboard, and the panel's `+ New` is the same command for a
     * finger.
     */
    NewAgentThread(
        "agent::NewThread",
        isOffered = { it.canUseAgent },
        isAvailable = { it.hasProject },
    ),

    /**
     * Answer the permission prompt in front of you — Zed's `agent::AllowOnce`
     * (`shift-alt-a`), `AllowAlways` (`shift-alt-q`) and `RejectOnce`
     * (`shift-alt-x`), the chords of its `AcpThread` context
     * (default-linux.json:267-270). Each takes the *first* waiting prompt's
     * option of that kind; the prompt's own buttons are the touch route.
     * Scoped to the composer ([KeymapContext.AgentPanel]) as Zed's are to
     * the thread: an Alt chord in the editor should stay the editor's.
     */
    AgentAllowOnce(
        "agent::AllowOnce",
        isOffered = { it.canUseAgent },
        isAvailable = { it.hasProject },
    ),
    AgentAllowAlways(
        "agent::AllowAlways",
        isOffered = { it.canUseAgent },
        isAvailable = { it.hasProject },
    ),
    AgentRejectOnce(
        "agent::RejectOnce",
        isOffered = { it.canUseAgent },
        isAvailable = { it.hasProject },
    ),

    /**
     * Open the review tab — every file the active thread's agent edited, with
     * Keep and Reject per file — Zed's `agent::OpenAgentDiff` on
     * `shift-ctrl-r` (default-linux.json:204). Zed binds it only in an editor
     * that carries agent edits; here nothing else claims `Ctrl+Shift+R`, so
     * it works from anywhere in the workspace. The panel bar's **Review N
     * files** badge is the same command for a finger.
     */
    OpenAgentDiff(
        "agent::OpenAgentDiff",
        isOffered = { it.canUseAgent },
        isAvailable = { it.hasProject },
    ),

    /**
     * Keep or throw away every edit in the review at once — Zed's
     * `agent::KeepAll` / `RejectAll` (default-linux.json:202-203, in the
     * agent-diff contexts). No chord: Zed's `shift-alt-y` and `shift-alt-z`
     * live only in that editor, and here the review tab's own two buttons
     * and the palette are the routes.
     */
    AgentKeepAll(
        "agent::KeepAll",
        isOffered = { it.canUseAgent },
        isAvailable = { it.hasProject },
    ),
    AgentRejectAll(
        "agent::RejectAll",
        isOffered = { it.canUseAgent },
        isAvailable = { it.hasProject },
    ),

    /**
     * Put the editor's selection on the agent's draft as a mention — Zed's
     * `agent::AddSelectionToThread` on `ctrl->` (default-linux.json:143, in
     * the Editor context), which is `Ctrl+Shift+.` on every layout a phone
     * keyboard has. Opens the panel when it is hidden, as Zed's does
     * (agent_panel.rs:644-700). The `☰` menu row and the composer's `@`
     * picker's Selection section are the touch routes.
     */
    AddSelectionToThread(
        "agent::AddSelectionToThread",
        isOffered = { it.canUseAgent },
        isAvailable = { it.hasActiveBuffer },
    ),

    /**
     * Show or hide the left and right docks — Zed's `workspace::ToggleLeftDock`
     * on `ctrl-b` and `ToggleRightDock` on `ctrl-alt-b`
     * (default-linux.json:668-669).
     *
     * A dock with nothing in it opens the panel that lives on that side, which
     * is what "show the sidebar" means to the person pressing it.
     */
    ToggleLeftDock("workspace::ToggleLeftDock"),
    ToggleRightDock("workspace::ToggleRightDock"),

    /** Show or hide the terminal dock. */
    ToggleTerminal("terminal_panel::Toggle", isAvailable = { it.hasProject }),

    /** Start another shell in the project directory. */
    NewTerminal("workspace::NewTerminal", isAvailable = { it.hasProject }),

    /** Kill the shell showing in the dock. */
    CloseTerminal("terminal::Close", isAvailable = { it.terminalCount > 0 }),

    NextTerminal("terminal::ActivateNextItem", isAvailable = { it.terminalCount > 1 }),
    PreviousTerminal("terminal::ActivatePreviousItem", isAvailable = { it.terminalCount > 1 }),

    /**
     * The task picker — Zed's `task::Spawn` on `alt-shift-t`
     * (default-linux.json:724): every task the project, the user's
     * `tasks.json` and the language offer, recently run ones first, and a
     * field that runs whatever is typed as a oneshot. The terminal tab bar's
     * `▶` is the same command for a finger.
     */
    SpawnTask("task::Spawn", isAvailable = { it.hasProject }),

    /**
     * Run the last task again — Zed's `task::Rerun` on `alt-t`
     * (default-linux.json:723). Greyed until something has run this session.
     */
    RerunTask("task::Rerun", isAvailable = { it.hasProject && it.hasRunTask }),

    /**
     * Run the runnable nearest the caret — Zed's `editor::SpawnNearestTask`,
     * which Zed's Linux keymap leaves unbound too; the gutter's play button is
     * the same thing aimed with a finger. One bound task runs at once; more
     * open the picker narrowed to them.
     */
    SpawnNearestTask("editor::SpawnNearestTask", isAvailable = { it.hasActiveBuffer }),

    /**
     * Open the user's `tasks.json` as a tab, creating it with Zed's
     * commented example when it does not exist — Zed's `zed::OpenTasks`
     * (zed/src/zed.rs:274-283, `initial_tasks_content`). The file sits
     * beside settings.json in app-private storage, so this is the only way
     * to edit it.
     */
    OpenTasks("zed::OpenTasks", isAvailable = { it.hasProject }),

    /**
     * Open — creating it on the way — the project's own `.zed/tasks.json`,
     * Zed's `zed::OpenProjectTasks` (zed/src/zed.rs `OpenProjectTasks`).
     */
    OpenProjectTasks("zed::OpenProjectTasks", isAvailable = { it.hasProject });

    companion object {
        /** The command bound under Zed's action name [id], or null. */
        fun byId(id: String): WorkspaceCommand? = BY_ID[id]

        private val BY_ID: Map<String, WorkspaceCommand> = entries.associateBy { it.id }
    }
}

/** Which surface the keyboard is talking to. */
enum class Focus {
    /** The editor, project panel, dialogs — everything that is not a shell. */
    Workspace,

    /** A terminal session: the shell claims the keyboard. */
    Terminal,
}

/**
 * The actions of the workspace that are *surfaces* rather than
 * [WorkspaceCommand]s — each puts something on screen that then takes the
 * keyboard, answers Escape itself and has to put things back on cancel — so
 * they are not in the palette, which they would otherwise have to open
 * themselves. keymap.json binds them by these names all the same.
 */
object WorkspaceAction {
    /** Zed's `command_palette::Toggle` (default-linux.json:696-697). */
    const val CommandPalette = "command_palette::Toggle"

    /** Project search — Zed's `pane::DeploySearch` (default-linux.json:683). */
    const val ProjectSearch = "pane::DeploySearch"

    /** Zed's `go_to_line::Toggle` (default-linux.json:622). */
    const val GoToLine = "go_to_line::Toggle"

    /** The outline picker — Zed's `outline::Toggle` (default-linux.json:621). */
    const val Outline = "outline::Toggle"

    /** `["pane::ActivateItem", n]`: the tab at zero-based position n. */
    const val ActivateItem = "pane::ActivateItem"

    /** Zed's `pane::ActivateLastItem`: the last tab, as every browser has it. */
    const val ActivateLastItem = "pane::ActivateLastItem"

    /** `["workspace::ActivatePane", n]`: the pane at zero-based position n (default-linux.json:660-668). */
    const val ActivatePane = "workspace::ActivatePane"
}

/** One default binding: a keystroke sequence and the action it runs. */
data class DefaultBinding(
    val keystrokes: String,
    val action: String,
    /** The action's argument as JSON, where it takes one. */
    val args: String? = null,
)

/** One section of the default keymap — a context and its bindings. */
data class DefaultSection(
    val context: KeymapContext,
    /** Why these keys, in a sentence or two — printed above the section. */
    val note: String,
    val bindings: List<DefaultBinding>,
)

/**
 * The default keymap: Zed's Linux bindings for everything this app has, in
 * Zed's file format.
 *
 * Where Zed binds the same action to two keys both are listed; where this
 * app differs from Zed the binding says why. The order within a section
 * matters only for the label printed beside a command, which is the *last*
 * binding listed — Zed's rule (keymap_file.rs:86-87) — so the chord a user
 * should learn goes last.
 */
object DefaultKeymap {
    private fun bind(keystrokes: String, action: String, args: String? = null) =
        DefaultBinding(keystrokes, action, args)

    private fun bind(keystrokes: String, command: WorkspaceCommand) =
        DefaultBinding(keystrokes, command.id)

    val sections: List<DefaultSection> = listOf(
        DefaultSection(
            KeymapContext.Workspace,
            note = "The workspace: these work wherever focus is, except while a " +
                "shell has the keyboard.",
            bindings = listOf(
                // Zed's own two (default-linux.json:696-697). `F1` stays out
                // of the terminal, where it belongs to whatever is running.
                bind("f1", WorkspaceAction.CommandPalette),
                bind("ctrl-shift-p", WorkspaceAction.CommandPalette),
                // Shift is ignored on most of the plain chords, so the twins
                // a user learned in the terminal keep working in the editor.
                bind("ctrl-shift-s", WorkspaceCommand.Save),
                bind("ctrl-s", WorkspaceCommand.Save),
                bind("ctrl-shift-w", WorkspaceCommand.CloseTab),
                bind("ctrl-w", WorkspaceCommand.CloseTab),
                // Zed's `ctrl-b` and `ctrl-alt-b` (default-linux.json:669-670).
                // `ctrl-shift-b` is *not* a shifted twin of these: Zed gives
                // it to the outline panel (:700), and the outline panel is
                // now here, so the chord goes where Zed put it.
                bind("ctrl-b", WorkspaceCommand.ToggleLeftDock),
                bind("ctrl-alt-b", WorkspaceCommand.ToggleRightDock),
                bind("ctrl-shift-b", WorkspaceCommand.ToggleOutlinePanel),
                // Zed's `alt-ctrl-shift-b` for the branch picker
                // (default-linux.json:644, via the `branches::OpenRecent`
                // alias of `git::Branch`).
                bind("ctrl-alt-shift-b", WorkspaceCommand.SwitchBranch),
                // The shifted twin is the outline picker, as in Zed.
                bind("ctrl-o", WorkspaceCommand.OpenProjects),
                // Zed's `alt-ctrl-o` for the recent-projects picker
                // (default-linux.json:639). Zed's other chord for it,
                // `ctrl-r` (:640), is left alone: the workspace matches its
                // keys in a preview pass at the root, and taking `ctrl-r`
                // there would take Vim's redo with it.
                bind("ctrl-alt-o", WorkspaceCommand.OpenRecent),
                bind("ctrl-shift-o", WorkspaceAction.Outline),
                bind("ctrl-p", WorkspaceCommand.FindFile),
                // Zed's `ctrl-shift-enter` (default-linux.json:1002), bound
                // there in the ProjectPanel context; here it works on the
                // active tab from anywhere the workspace has the keys, and
                // the panel's own handler takes it for the selected row while
                // the panel has them — see runCommand. The editor's
                // `ctrl-shift-enter` (NewlineAbove) is deeper and wins there.
                bind("ctrl-shift-enter", WorkspaceCommand.OpenWithSystem),
                // Zed's ctrl-= / ctrl-+ / ctrl-- / ctrl-0
                // (default-linux.json:30-33), which are the *buffer* font's
                // everywhere but its onboarding screens. `ctrl-+` *is* the
                // shifted `=` on most layouts, and Zed binds both to the same
                // action. The UI font size has no chord, for the reason on
                // [WorkspaceCommand.IncreaseUiFontSize].
                bind("ctrl-shift-=", WorkspaceCommand.IncreaseBufferFontSize),
                bind("ctrl-=", WorkspaceCommand.IncreaseBufferFontSize),
                bind("ctrl--", WorkspaceCommand.DecreaseBufferFontSize),
                bind("ctrl-0", WorkspaceCommand.ResetBufferFontSize),
                // Where every editor puts find; the shifted twin is Zed's own
                // project search (default-linux.json:683).
                bind("ctrl-f", WorkspaceCommand.FindInFile),
                bind("ctrl-shift-f", WorkspaceAction.ProjectSearch),
                // Zed's `ctrl-h` for `buffer_search::DeployReplace`
                // (default-linux.json:142). Unshifted only: `ctrl-shift-h` is
                // Zed's project-search ToggleReplace, which the panel answers
                // itself. Not in the terminal, where `ctrl-h` is backspace.
                bind("ctrl-h", WorkspaceCommand.FindAndReplaceInFile),
                // Zed's `go_to_line::Toggle` (default-linux.json:622).
                bind("ctrl-g", WorkspaceAction.GoToLine),
                // Zed's preview is `ctrl-shift-v` and `ctrl-k v`
                // (default-linux.json:606-607); `ctrl-shift-v` is paste
                // twice over here — the editor's and the project panel's —
                // so the one-stroke chord is `ctrl-shift-m`, which nothing in
                // this app or in a shell claims.
                bind("ctrl-k v", WorkspaceCommand.TogglePreview),
                bind("ctrl-shift-m", WorkspaceCommand.TogglePreview),
                bind("ctrl-shift-,", WorkspaceCommand.OpenSettings),
                bind("ctrl-,", WorkspaceCommand.OpenSettings),
                // Zed's `ctrl-k ctrl-s` (default-linux.json:687).
                bind("ctrl-k ctrl-s", WorkspaceCommand.OpenKeymap),
                // Zed's `ctrl-k n` for the encoding picker (default-linux.json:706).
                bind("ctrl-k n", WorkspaceCommand.SelectEncoding),
                // Zed's `ctrl-tab` and `ctrl-shift-tab` are the *tab
                // switcher's*, not positional cycling (default-linux.json:
                // 693-694) — `{ "select_last": true }` on the shifted twin
                // starts at the far end of the most-recently-used list. The
                // gesture is hold-and-release, so the workspace matches these
                // two itself and only falls back to this table when there is
                // nothing to switch between; `ctrl-pageup/pagedown` below is
                // the plain positional cycling it used to mean.
                bind("ctrl-tab", WorkspaceCommand.OpenTabSwitcher),
                bind(
                    "ctrl-shift-tab",
                    WorkspaceCommand.OpenTabSwitcher.id,
                    "{ \"select_last\": true }",
                ),
                // Zed's `editor::OpenExcerpts` is `alt-enter` with no Ctrl
                // (default-linux.json:915). In the workspace section rather
                // than the editor's, so an editor's Alt+Enter still inserts a
                // newline: the command refuses anywhere but a multibuffer and
                // the key falls through. In a shell it belongs to the shell.
                bind("alt-enter", WorkspaceCommand.OpenExcerpt),
                // Zed's `ctrl-k m` for the language picker (default-linux.json:707).
                bind("ctrl-k m", WorkspaceCommand.SelectLanguage),
                bind("ctrl-shift-pagedown", WorkspaceCommand.NextTab),
                bind("ctrl-pagedown", WorkspaceCommand.NextTab),
                bind("ctrl-shift-pageup", WorkspaceCommand.PreviousTab),
                bind("ctrl-pageup", WorkspaceCommand.PreviousTab),
                // Ctrl+1…Ctrl+8 pick a tab by position and Ctrl+9 the last
                // one, as in every browser — Zed's `pane::ActivateItem` and
                // `pane::ActivateLastItem` (default-linux.json:516-525).
                bind("ctrl-1", WorkspaceAction.ActivateItem, "0"),
                bind("ctrl-2", WorkspaceAction.ActivateItem, "1"),
                bind("ctrl-3", WorkspaceAction.ActivateItem, "2"),
                bind("ctrl-4", WorkspaceAction.ActivateItem, "3"),
                bind("ctrl-5", WorkspaceAction.ActivateItem, "4"),
                bind("ctrl-6", WorkspaceAction.ActivateItem, "5"),
                bind("ctrl-7", WorkspaceAction.ActivateItem, "6"),
                bind("ctrl-8", WorkspaceAction.ActivateItem, "7"),
                bind("ctrl-9", WorkspaceAction.ActivateLastItem),
                // Zed's Linux chords for the navigation history, exactly as
                // the keymap writes them: `ctrl-alt--` is GoBack and
                // `ctrl-alt-_` — the same key with Shift — is GoForward
                // (default-linux.json:512-514).
                bind("ctrl-alt--", WorkspaceCommand.GoBack),
                bind("ctrl-alt-shift--", WorkspaceCommand.GoForward),
                // Zed's `ctrl-n` (default-linux.json:654). Unshifted only:
                // `ctrl-shift-n` is the project panel's new-folder chord.
                bind("ctrl-n", WorkspaceCommand.NewFile),
                // Zed's own `ctrl-shift-t` (default-linux.json:530).
                bind("ctrl-shift-t", WorkspaceCommand.ReopenClosedTab),
                // Zed's `ctrl-k` tab chords (default-linux.json:503-531).
                bind("ctrl-alt-t", WorkspaceCommand.CloseOtherTabs),
                bind("ctrl-k t", WorkspaceCommand.CloseTabsToTheRight),
                // Zed's `ctrl-k e` and `ctrl-k u` (default-linux.json:505, 507).
                bind("ctrl-k e", WorkspaceCommand.CloseTabsToTheLeft),
                bind("ctrl-k u", WorkspaceCommand.CloseCleanTabs),
                bind("ctrl-k w", WorkspaceCommand.CloseAllTabs),
                bind("ctrl-k shift-enter", WorkspaceCommand.TogglePinTab),
                // Zed's `ctrl-shift-e` (default-linux.json:665).
                bind("ctrl-shift-e", WorkspaceCommand.RevealInProjectPanel),
                // Zed's own `ctrl-shift-g` (default-linux.json:700).
                bind("ctrl-shift-g", WorkspaceCommand.ToggleGitPanel),
                // `ctrl-alt-a`, Zed's other agent chord — `ctrl-?` needs a
                // Shift a phone keyboard cannot spare.
                bind("ctrl-alt-a", WorkspaceCommand.ToggleAgentPanel),
                // Zed's `shift-ctrl-r` for the agent review (default-linux.json:
                // 204), bound there only in an editor carrying agent edits;
                // nothing else here claims `ctrl-shift-r`, so it works from
                // anywhere in the workspace. Not the terminal: a review needs
                // the work area, not a shell.
                bind("ctrl-shift-r", WorkspaceCommand.OpenAgentDiff),
                // Zed's `ctrl->` (default-linux.json:143): `>` is Shift+`.` on
                // every layout a phone keyboard has, so the chord is spelled
                // that way and prints as `Ctrl Shift .`.
                bind("ctrl-shift-.", WorkspaceCommand.AddSelectionToThread),
                // Zed's `ctrl-k z` / `ctrl-k ctrl-z` (default-linux.json:138-139).
                bind("ctrl-k ctrl-z", WorkspaceCommand.ToggleSoftWrap),
                bind("ctrl-k z", WorkspaceCommand.ToggleSoftWrap),
                // Zed's `project_symbols::Toggle` (default-linux.json:691).
                bind("ctrl-t", WorkspaceCommand.ProjectSymbols),
                // Zed's `ctrl-:` for `editor::ToggleInlayHints`
                // (default-linux.json:922): `:` is Shift+`;` on every layout a
                // phone keyboard has, so it is spelled that way and prints as
                // `Ctrl Shift ;`.
                bind("ctrl-shift-;", WorkspaceCommand.ToggleInlayHints),
                bind("ctrl-`", WorkspaceCommand.ToggleTerminal),
                bind("ctrl-shift-`", WorkspaceCommand.NewTerminal),
                // Zed's task chords (default-linux.json:721-725): `alt-t`
                // and `ctrl-alt-r` rerun, `alt-shift-t` opens the picker.
                // Workspace-only, like every other Alt chord: in a terminal
                // `alt-t` belongs to the shell.
                bind("ctrl-alt-r", WorkspaceCommand.RerunTask),
                bind("alt-t", WorkspaceCommand.RerunTask),
                bind("alt-shift-t", WorkspaceCommand.SpawnTask),
                // Zed's `shift-escape` for the zoom (default-linux.json:26),
                // bound there with no context at all.
                bind("shift-escape", WorkspaceCommand.ToggleZoom),
                // Zed's pane family, verbatim (default-linux.json:660-668,
                // 710-717): `alt-<digit>` picks a pane, `ctrl-k ctrl-<arrow>`
                // moves focus across a split, `ctrl-k shift-<arrow>` swaps.
                bind("alt-1", WorkspaceAction.ActivatePane, "0"),
                bind("alt-2", WorkspaceAction.ActivatePane, "1"),
                bind("alt-3", WorkspaceAction.ActivatePane, "2"),
                bind("alt-4", WorkspaceAction.ActivatePane, "3"),
                bind("alt-5", WorkspaceAction.ActivatePane, "4"),
                bind("alt-6", WorkspaceAction.ActivatePane, "5"),
                bind("alt-7", WorkspaceAction.ActivatePane, "6"),
                bind("alt-8", WorkspaceAction.ActivatePane, "7"),
                bind("alt-9", WorkspaceAction.ActivatePane, "8"),
                bind("ctrl-k ctrl-left", WorkspaceCommand.ActivatePaneLeft),
                bind("ctrl-k ctrl-right", WorkspaceCommand.ActivatePaneRight),
                bind("ctrl-k ctrl-up", WorkspaceCommand.ActivatePaneUp),
                bind("ctrl-k ctrl-down", WorkspaceCommand.ActivatePaneDown),
                bind("ctrl-k shift-left", WorkspaceCommand.SwapPaneLeft),
                bind("ctrl-k shift-right", WorkspaceCommand.SwapPaneRight),
                bind("ctrl-k shift-up", WorkspaceCommand.SwapPaneUp),
                bind("ctrl-k shift-down", WorkspaceCommand.SwapPaneDown),
            ),
        ),
        DefaultSection(
            KeymapContext.Pane,
            note = "A pane: Zed's Atom-derived split chords, which it binds in " +
                "its Pane context (default-linux.json:813-820).",
            bindings = listOf(
                bind("ctrl-k up", WorkspaceCommand.SplitUp),
                bind("ctrl-k down", WorkspaceCommand.SplitDown),
                bind("ctrl-k left", WorkspaceCommand.SplitLeft),
                bind("ctrl-k right", WorkspaceCommand.SplitRight),
            ),
        ),
        DefaultSection(
            KeymapContext.Editor,
            note = "The editor. Zed's Linux chords wherever it has one; the " +
                "one addition is ctrl-alt-up/down as a second way to add a " +
                "cursor, because shift-alt is where many Android keyboards put " +
                "their layout switch.",
            bindings = listOf(
                // Zed's `editor::ShowCompletions` (default-linux.json:591).
                bind("ctrl-space", EditorAction.ShowCompletions),
                // Zed's `ctrl-\` for `pane::SplitRight`, an Editor binding
                // there too (default-linux.json:596).
                bind("ctrl-\\", WorkspaceCommand.SplitRight),
                bind("ctrl-z", EditorAction.Undo),
                bind("ctrl-shift-z", EditorAction.Redo),
                bind("ctrl-y", EditorAction.Redo),
                // Clipboard and select-all take Shift or not, so the twins a
                // shell user knows work here too.
                bind("ctrl-shift-a", EditorAction.SelectAll),
                bind("ctrl-a", EditorAction.SelectAll),
                bind("ctrl-shift-c", EditorAction.Copy),
                bind("ctrl-c", EditorAction.Copy),
                bind("ctrl-shift-x", EditorAction.Cut),
                bind("ctrl-x", EditorAction.Cut),
                bind("ctrl-shift-v", EditorAction.Paste),
                bind("ctrl-v", EditorAction.Paste),
                // Zed's `editor::SelectNext` and `SelectAllMatches`
                // (default-linux.json:553, 562).
                bind("ctrl-d", EditorAction.SelectNext),
                bind("ctrl-shift-l", EditorAction.SelectAllMatches),
                bind("ctrl-shift-k", EditorAction.DeleteLine),
                bind("ctrl-shift-j", EditorAction.JoinLines),
                // Zed's `ctrl-/` and its `ctrl-k ctrl-c` (default-linux.json:559).
                bind("ctrl-k ctrl-c", EditorAction.ToggleComments),
                bind("ctrl-/", EditorAction.ToggleComments),
                // Zed's `editor::ToggleCodeActions` on `ctrl-.`.
                bind("ctrl-.", EditorAction.ToggleCodeActions),
                // Zed's `editor::Format`, on its Linux chord `ctrl-shift-i`.
                bind("ctrl-shift-i", EditorAction.Format),
                // Zed's fold pair, which it writes `ctrl-{` and `ctrl-}`
                // (default-linux.json:575-576): Shift and the bracket keys.
                bind("ctrl-shift-[", EditorAction.Fold),
                bind("ctrl-shift-]", EditorAction.UnfoldLines),
                // Zed's `ctrl-k` chords (default-linux.json:556, 589-590).
                bind("ctrl-k ctrl-0", EditorAction.FoldAll),
                bind("ctrl-k ctrl-j", EditorAction.UnfoldAll),
                bind("ctrl-k ctrl-i", EditorAction.Hover),
                // Ctrl+arrow is word-wise; Ctrl+Home/End is the whole document.
                bind("ctrl-left", EditorAction.MoveToPreviousWordStart),
                bind("ctrl-right", EditorAction.MoveToNextWordEnd),
                bind("ctrl-shift-left", EditorAction.SelectToPreviousWordStart),
                bind("ctrl-shift-right", EditorAction.SelectToNextWordEnd),
                bind("ctrl-home", EditorAction.MoveToBeginning),
                bind("ctrl-end", EditorAction.MoveToEnd),
                bind("ctrl-shift-home", EditorAction.SelectToBeginning),
                bind("ctrl-shift-end", EditorAction.SelectToEnd),
                // The line family: Zed's `alt-up/down`, `ctrl-alt-shift-up/down`
                // and `shift-alt-up/down` (default-linux.json:542-549).
                bind("alt-up", EditorAction.MoveLineUp),
                bind("alt-down", EditorAction.MoveLineDown),
                bind("ctrl-alt-shift-up", EditorAction.DuplicateLineUp),
                bind("ctrl-alt-shift-down", EditorAction.DuplicateLineDown),
                bind("ctrl-alt-up", EditorAction.AddSelectionAbove),
                bind("alt-shift-up", EditorAction.AddSelectionAbove),
                bind("ctrl-alt-down", EditorAction.AddSelectionBelow),
                bind("alt-shift-down", EditorAction.AddSelectionBelow),
                // Zed's syntax-aware selection (default-linux.json:547-548).
                // Note the direction: alt-up/down stays line-moving here
                // *because* it is line-moving in Zed, and the growth chords
                // are the horizontal pair.
                bind("alt-shift-right", EditorAction.SelectLargerSyntaxNode),
                bind("alt-shift-left", EditorAction.SelectSmallerSyntaxNode),
                // Zed's `editor::MoveToEnclosingBracket` (:573-574). Zed's
                // second chord for it is `ctrl-|`, which needs Shift on every
                // layout a phone keyboard offers, so it is spelled that way.
                bind("ctrl-m", EditorAction.MoveToEnclosingBracket),
                bind("ctrl-shift-\\", EditorAction.MoveToEnclosingBracket),
                // Zed's `editor::SelectLine` (:111).
                bind("ctrl-l", EditorAction.SelectLine),
                // Zed's `editor::Rewrap` (:66-67).
                bind("ctrl-k ctrl-q", EditorAction.Rewrap),
                bind("ctrl-k q", EditorAction.Rewrap),
                // Zed's `editor::ToggleLineNumbers` (:117). The other three
                // display toggles have no chord in Zed and are palette rows,
                // reachable by touch from the editor's overflow menu.
                bind("ctrl-;", EditorAction.ToggleLineNumbers),
                // Zed's diagnostic motions (default-linux.json:563-564).
                bind("f8", EditorAction.GoToDiagnostic),
                bind("shift-f8", EditorAction.GoToPreviousDiagnostic),
                // Zed's `editor::GoToDefinition` (:565) and
                // `GoToTypeDefinition` (:568). Zed's `FindAllReferences` is
                // `alt-shift-f12` (:571) and its `GoToImplementation`
                // `shift-f12` (:569); `shift-f12` has meant references here
                // since before the keymap existed, as it does in VS Code, so
                // it keeps that meaning, references gets Zed's chord as a
                // second spelling, and implementation takes `ctrl-shift-f12`.
                // Declaration has no chord in Zed either; the hover card and
                // keymap.json reach it.
                bind("f12", EditorAction.GoToDefinition),
                bind("ctrl-f12", EditorAction.GoToTypeDefinition),
                bind("ctrl-shift-f12", EditorAction.GoToImplementation),
                bind("shift-f12", EditorAction.FindAllReferences),
                bind("alt-shift-f12", EditorAction.FindAllReferences),
                // Zed's `editor::ShowSignatureHelp` (default-linux.json:120).
                bind("ctrl-i", EditorAction.ShowSignatureHelp),
                bind("f2", EditorAction.Rename),
                // Git in the editor, on Zed's chords: the hunk motions
                // `alt-.` / `alt-,` and their `ctrl-f8` twins
                // (default-linux.json:598-599, 919-920); `ctrl-'` toggles the
                // hunks under the carets and `ctrl-"` — Shift and the quote
                // key — expands them all (:118-119); `alt-g b` is the blame
                // column (:121); `ctrl-alt-y` stages or unstages the hunk,
                // `alt-y` / `alt-shift-y` do so and move on, and
                // `ctrl-k ctrl-r` puts the commit's rows back (:190-193).
                bind("ctrl-f8", EditorAction.GoToHunk),
                bind("alt-.", EditorAction.GoToHunk),
                bind("ctrl-shift-f8", EditorAction.GoToPreviousHunk),
                bind("alt-,", EditorAction.GoToPreviousHunk),
                bind("ctrl-'", EditorAction.ToggleSelectedDiffHunks),
                bind("ctrl-shift-'", EditorAction.ExpandAllDiffHunks),
                bind("alt-g b", EditorAction.Blame),
                bind("ctrl-alt-y", EditorAction.ToggleStaged),
                bind("alt-y", EditorAction.StageAndNext),
                bind("alt-shift-y", EditorAction.UnstageAndNext),
                bind("ctrl-k ctrl-r", EditorAction.Restore),
                bind("escape", EditorAction.Cancel),
                bind("shift-backspace", EditorAction.Backspace),
                bind("backspace", EditorAction.Backspace),
                // Zed's `editor::Delete` (default-linux.json:63).
                bind("delete", EditorAction.Delete),
                // Zed's word-wise deletes (default-linux.json:68-69).
                // `alt-backspace` is DeleteToPreviousWordStart in Zed's macOS
                // keymap and unbound on Linux; a Mac keyboard paired with a
                // tablet sends it, so it means the same here.
                bind("ctrl-backspace", EditorAction.DeleteToPreviousWordStart),
                bind("alt-backspace", EditorAction.DeleteToPreviousWordStart),
                bind("ctrl-delete", EditorAction.DeleteToNextWordEnd),
                bind("shift-enter", EditorAction.Newline),
                bind("enter", EditorAction.Newline),
                // Zed's `editor::NewlineBelow` and, shifted, `NewlineAbove`
                // (default-linux.json:136-137).
                bind("ctrl-enter", EditorAction.NewlineBelow),
                bind("ctrl-shift-enter", EditorAction.NewlineAbove),
                // Zed's `editor::Tab` and `editor::Backtab` (default-linux.json:
                // 64-65), and `Indent`/`Outdent` on the bare bracket keys
                // (:538-539) — the fold pair is their shifted twin.
                bind("shift-tab", EditorAction.Backtab),
                bind("tab", EditorAction.Tab),
                bind("ctrl-]", EditorAction.Indent),
                bind("ctrl-[", EditorAction.Outdent),
                bind("left", EditorAction.MoveLeft),
                bind("right", EditorAction.MoveRight),
                bind("up", EditorAction.MoveUp),
                bind("down", EditorAction.MoveDown),
                bind("shift-left", EditorAction.SelectLeft),
                bind("shift-right", EditorAction.SelectRight),
                bind("shift-up", EditorAction.SelectUp),
                bind("shift-down", EditorAction.SelectDown),
                bind("home", EditorAction.MoveToBeginningOfLine),
                bind("end", EditorAction.MoveToEndOfLine),
                bind("shift-home", EditorAction.SelectToBeginningOfLine),
                bind("shift-end", EditorAction.SelectToEndOfLine),
                bind("pageup", EditorAction.MovePageUp),
                bind("pagedown", EditorAction.MovePageDown),
                bind("shift-pageup", EditorAction.SelectPageUp),
                bind("shift-pagedown", EditorAction.SelectPageDown),
            ),
        ),
        DefaultSection(
            KeymapContext.ImageViewer,
            note = "A picture as the open tab, Zed's ImageViewer context: its " +
                "zoom chords sit on the editor font size's keys and the " +
                "first tab's, and win over them only while a picture is up.",
            bindings = listOf(
                // Zed's `ImageViewer` section verbatim (default-linux.json:
                // 1566-1574), less `ctrl-k r` (RevealInFileManager), which
                // has no file manager to reveal in here. `ctrl-+` is the
                // shifted `=` on the layouts a phone keyboard has, so it is
                // spelled that way, as the buffer font size's twin is.
                bind("ctrl-shift-=", WorkspaceCommand.ImageZoomIn),
                bind("ctrl-=", WorkspaceCommand.ImageZoomIn),
                bind("ctrl--", WorkspaceCommand.ImageZoomOut),
                bind("ctrl-0", WorkspaceCommand.ImageResetZoom),
                bind("ctrl-1", WorkspaceCommand.ImageZoomToActualSize),
                bind("ctrl-shift-0", WorkspaceCommand.ImageFitToView),
            ),
        ),
        DefaultSection(
            KeymapContext.AgentPanel,
            note = "The agent panel's composer, Zed's AgentPanel and AcpThread " +
                "contexts: ctrl-n starts a thread here and a file everywhere " +
                "else, and the permission chords answer the prompt in front of " +
                "you. Every workspace chord still works from the composer.",
            bindings = listOf(
                // Zed's `AgentPanel` context (default-linux.json:220); the
                // workspace's `ctrl-n` (NewFile) is the shallower binding and
                // gives way here.
                bind("ctrl-n", WorkspaceCommand.NewAgentThread),
                // Zed's `AcpThread` permission chords, verbatim
                // (default-linux.json:267-270): `shift-alt-q` allow always,
                // `shift-alt-a` allow once, `shift-alt-x` reject once — the
                // only chords in this keymap without Ctrl.
                bind("shift-alt-q", WorkspaceCommand.AgentAllowAlways),
                bind("shift-alt-a", WorkspaceCommand.AgentAllowOnce),
                bind("shift-alt-x", WorkspaceCommand.AgentRejectOnce),
            ),
        ),
        DefaultSection(
            KeymapContext.Terminal,
            note = "A focused shell hears only this section: every plain " +
                "ctrl-<letter>, escape, alt chords and the function keys go to " +
                "the shell. Ctrl+Tab cycles terminals rather than editor tabs, " +
                "because that is the pane you are looking at.",
            bindings = listOf(
                bind("ctrl-shift-p", WorkspaceAction.CommandPalette),
                bind("ctrl-tab", WorkspaceCommand.NextTerminal),
                bind("ctrl-shift-tab", WorkspaceCommand.PreviousTerminal),
                bind("ctrl-shift-pagedown", WorkspaceCommand.NextTab),
                bind("ctrl-pagedown", WorkspaceCommand.NextTab),
                bind("ctrl-shift-pageup", WorkspaceCommand.PreviousTab),
                bind("ctrl-pageup", WorkspaceCommand.PreviousTab),
                bind("ctrl-shift-s", WorkspaceCommand.Save),
                bind("ctrl-shift-w", WorkspaceCommand.CloseTerminal),
                // Zed's `ctrl-shift-f` → `buffer_search::Deploy` in the
                // Terminal context (default-linux.json:1282): the scrollback
                // search. Project search owns the chord in the workspace.
                bind("ctrl-shift-f", WorkspaceCommand.FindInFile),
                // The outline panel keeps Zed's chord here too, so
                // `ctrl-shift-b` means one thing wherever the keyboard is;
                // the project panel takes `ctrl-shift-e`, the shifted twin of
                // the chord Zed gives `project_panel::ToggleFocus` (:699),
                // which in the workspace reveals the active file instead.
                bind("ctrl-shift-b", WorkspaceCommand.ToggleOutlinePanel),
                bind("ctrl-shift-e", WorkspaceCommand.ToggleProjectPanel),
                bind("ctrl-shift-o", WorkspaceCommand.OpenProjects),
                bind("ctrl-shift-,", WorkspaceCommand.OpenSettings),
                bind("ctrl-shift-g", WorkspaceCommand.ToggleGitPanel),
                bind("ctrl-`", WorkspaceCommand.ToggleTerminal),
                bind("ctrl-shift-`", WorkspaceCommand.NewTerminal),
                // The dock's own actions — Zed's `terminal::` family on Zed's
                // chords (default-linux.json:1262, 1265, 1283, 1295-1296).
                bind("ctrl-shift-c", TerminalAction.Copy.id),
                bind("ctrl-shift-v", TerminalAction.Paste.id),
                bind("ctrl-shift-l", TerminalAction.Clear.id),
                bind("ctrl-shift-n", TerminalAction.Rename.id),
                bind("shift-home", TerminalAction.ScrollToTop.id),
                bind("shift-end", TerminalAction.ScrollToBottom.id),
            ),
        ),
    )

    /**
     * The table as a keymap file — what the engine is handed and what
     * `zed: open default keymap` opens. Commented, because the file is read
     * by people as much as by the parser; the engine's reader takes comments
     * (settings_json::parse_json_with_comments) exactly as Zed's does.
     */
    fun text(): String = buildString {
        appendLine("// Seeker IDE's default keymap — Zed's Linux bindings for")
        appendLine("// everything this app has, in Zed's own format.")
        appendLine("//")
        appendLine("// This file is generated from the app's command table and is not")
        appendLine("// read back: to change a binding, put the line in keymap.json,")
        appendLine("// which \"zed: open keymap\" opens. Bind a key to null there to")
        appendLine("// switch one of these off.")
        appendLine("[")
        for (section in sections) {
            appendLine("  {")
            wrapNote(section.note).forEach { appendLine("    // $it") }
            appendLine("    \"context\": \"${section.context.zedName}\",")
            appendLine("    \"bindings\": {")
            for (binding in section.bindings) {
                val action = if (binding.args != null) {
                    "[\"${binding.action}\", ${binding.args}]"
                } else {
                    "\"${binding.action}\""
                }
                // A backslash key — `ctrl-\` — is written as JSON writes a
                // backslash, which is how Zed's own file spells it (:596).
                val keystrokes = binding.keystrokes.replace("\\", "\\\\")
                appendLine("      \"$keystrokes\": $action,")
            }
            appendLine("    }")
            appendLine("  },")
        }
        appendLine("]")
    }

    /**
     * The table resolved on this side alone — the keymap the first frame
     * runs on, before the engine has answered with the base keymap and the
     * user's file layered on.
     */
    fun keymap(): Keymap {
        val bindings = mutableListOf<KeyBinding>()
        for (section in sections) {
            for (binding in section.bindings) {
                val strokes = Keystroke.parseSequence(binding.keystrokes)
                    ?: error("default keymap: ${binding.keystrokes} is not a keystroke")
                bindings += KeyBinding(
                    context = section.context,
                    keystrokes = strokes,
                    action = binding.action,
                    args = binding.args,
                    source = KeybindSource.Default,
                    index = bindings.size,
                )
            }
        }
        return Keymap(bindings)
    }

    private fun wrapNote(note: String, width: Int = 72): List<String> {
        val lines = mutableListOf<String>()
        var line = StringBuilder()
        for (word in note.split(' ')) {
            if (line.isNotEmpty() && line.length + 1 + word.length > width) {
                lines += line.toString()
                line = StringBuilder()
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }
}

/**
 * The context chains a command's chords should be read against, best first.
 *
 * A label is only ever resolved against a *chain* of active contexts — that
 * is what lets a deeper binding shadow a shallower one, and it is what stops
 * the palette printing a chord that in practice does something else. Reading
 * every command against the editor's chain, which is what this used to do,
 * therefore printed no chord at all for the ones bound where the editor never
 * is: `AgentPanel` sits beside `Pane` rather than under it, so every
 * agent-panel command came out blank in the palette and in the ☰ menu.
 *
 * So the keymap is asked where the command actually lives ([homes]) and each
 * home's own chain is read. The editor's chain leads when the command has a
 * home inside it, because that is where a user is when they press the key;
 * the deep non-editor contexts follow, and a terminal-only binding last.
 *
 * A focused terminal is the exception it always was: it hears `Terminal`
 * bindings and nothing else, so that is the only chain there is to read.
 */
internal fun chainsFor(
    homes: Collection<KeymapContext>,
    focus: Focus = Focus.Workspace,
): List<List<KeymapContext>> {
    if (focus == Focus.Terminal) return listOf(KeymapContext.chainTo(KeymapContext.Terminal))
    val editorChain = KeymapContext.chainTo(KeymapContext.Editor)
    val chains = mutableListOf<List<KeymapContext>>()
    // An unbound command has no homes at all; reading the editor's chain then
    // costs one miss and keeps the answer null, which is what it should be.
    if (homes.isEmpty() || homes.any { it in editorChain }) chains += editorChain
    for (home in homes.filter { it !in editorChain && it != KeymapContext.Terminal }
        .sortedByDescending { it.depth }) {
        chains += KeymapContext.chainTo(home)
    }
    if (KeymapContext.Terminal in homes) chains += KeymapContext.chainTo(KeymapContext.Terminal)
    return chains
}

/**
 * Every chord that runs [command] while [focus] has the keyboard, strongest
 * first — so the first is the one to show and the rest are the
 * alternatives. Read from the keymap in force, so a rebinding in
 * keymap.json is what the palette prints.
 */
fun shortcutLabels(
    command: WorkspaceCommand,
    focus: Focus = Focus.Workspace,
): List<String> = shortcutLabels(command.id, focus)

/** [shortcutLabels], by action id — the form a [WorkspaceAction] takes. */
fun shortcutLabels(action: String, focus: Focus = Focus.Workspace): List<String> {
    val keymap = KeymapStore.keymap
    return keymap.labelsAcross(action, chainsFor(keymap.contextsFor(action), focus))
}

/** The chord to print beside [command], or null when it has none. */
fun shortcutLabel(command: WorkspaceCommand, focus: Focus = Focus.Workspace): String? =
    shortcutLabels(command, focus).firstOrNull()

/** The chord to print beside a [WorkspaceAction], or null when it has none. */
fun shortcutLabel(action: String, focus: Focus = Focus.Workspace): String? =
    shortcutLabels(action, focus).firstOrNull()

/**
 * True when this event should open or advance the tab switcher — Zed's
 * `tab_switcher::Toggle` on `ctrl-tab`, and `{ "select_last": true }` on
 * `ctrl-shift-tab` (default-linux.json:693-694).
 *
 * Matched here rather than left to the keymap because the gesture is
 * hold-and-release: Ctrl+Tab opens an overlay and each further press walks
 * the most-recently-used list; letting Ctrl *go* is what switches. The keymap
 * matches key-down events only and has no vocabulary for a modifier release,
 * so the workspace matches this pair itself and [isCtrlRelease] closes it.
 * The binding is in [DefaultKeymap] all the same, so the chord is printed and
 * written to the default keymap file, and so a tap on the palette row does
 * the untimed half of the same thing.
 *
 * Never while a shell has the keyboard: there `Ctrl+Tab` cycles terminal
 * sessions, which is the pane you are looking at.
 */
fun isTabSwitcher(event: KeyEvent, focus: Focus = Focus.Workspace): Boolean {
    if (focus != Focus.Workspace) return false
    if (event.type != KeyEventType.KeyDown) return false
    val stroke = Keystroke.of(event.nativeKeyEvent) ?: return false
    // Shift is the direction, not a different chord; Alt is somebody else's.
    return stroke.ctrl && !stroke.alt && stroke.key == "tab"
}

/**
 * True when Ctrl has just been let go — what commits the switcher.
 *
 * Both physical keys, because either one may be the one being held, and the
 * event's own `isCtrlPressed` still reads true on the release of the last
 * Ctrl on some devices; the key code is the reliable half.
 */
fun isCtrlRelease(event: KeyEvent): Boolean {
    if (event.type != KeyEventType.KeyUp) return false
    return event.key == Key.CtrlLeft || event.key == Key.CtrlRight
}

/**
 * True when this event, on its own, opens the command palette — the chord
 * that opened it closes it, as in Zed. Only single strokes count: a modal
 * has no chord state to continue.
 */
fun isCommandPalette(event: KeyEvent, focus: Focus = Focus.Workspace): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val stroke = Keystroke.of(event.nativeKeyEvent) ?: return false
    val contexts = KeymapContext.chainFor(focus, editorFocused = false)
    val resolution = KeymapStore.keymap.resolve(listOf(stroke), contexts)
    return resolution is Resolution.Matched &&
        resolution.binding.action == WorkspaceAction.CommandPalette
}
