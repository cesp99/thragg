# Keyboard shortcuts

Seeker IDE targets foldables, tablets and **Samsung DeX**, where a
keyboard and mouse are ordinary rather than exotic — and where, in DeX,
touch isn't available at all. Everything you can do by tapping should
also have a binding here.

Every binding below is a default, and every one can be changed: the keymap
is Zed's `keymap.json`, in Zed's syntax — see [Your own keymap](#your-own-keymap)
at the end. The chords printed in the command palette and the menus are
read from the keymap in force, so they follow your changes.

## Command palette

You do not have to memorise the tables below. `Ctrl` `Shift` `P` (or `F1`, or
**Command palette…** in the `☰` menu) opens the palette: every command in
the workspace, searchable by name, with the chord that also runs it shown
beside it.

| Shortcut | Action |
|---|---|
| `Ctrl` `Shift` `P` / `F1` | Open the command palette |
| `↑` `↓`, `Ctrl` `P` / `Ctrl` `N`, `Tab` / `Shift` `Tab` | Move the selection |
| `Enter` | Run the selected command |
| `Esc`, or `Ctrl` `Shift` `P` again | Close it |

Commands are named the way Zed names them — `terminal panel: toggle`,
`pane: close active item` — so typing `term` finds everything the terminal
can do. Matching is fuzzy and the matched letters are highlighted, exactly
as in the file finder; typing an uppercase letter makes the search
case-sensitive. The commands you ran most recently come first.

A command that cannot run right now — saving with no file open, opening a
terminal with no project — is listed greyed rather than hidden, so the
palette is also the honest list of what exists. A command this build cannot
run at all is not listed rather than greyed.

The palette is a touch surface as much as a keyboard one: tap a row to run
it, and the list stays above the soft keyboard while you type.

### Aliases

`command_aliases` in settings.json gives a command a name of your own. The
*whole* query has to match a key, and it is replaced by the action:

```json
"command_aliases": {
  "W": "workspace::Save",
  "term": "terminal_panel::Toggle"
}
```

Type `W` and the palette shows `workspace: save`, with `W` printed beside
it so the alias is discoverable rather than a secret. Typing an action's
real name works too — `workspace::Save` finds `workspace: save`.

### The chord beside a row

The chord is read from the keymap in force, so rebinding a key in
`keymap.json` changes what the palette prints. It is read in the context
the command is *bound* in, not the one you happened to open the palette
from — an agent-panel command shows its chord even though the agent panel
is not where you are. The one exception is a shell: a palette opened from
a focused terminal prints only chords a terminal can hear, because every
plain `Ctrl`+letter belongs to the shell there.

## Pending chords

Some bindings are two keystrokes — `Ctrl` `K` then `Ctrl` `0`. Press the
first and a small panel appears above the status bar listing every way to
finish it and what each would do, with the keys typed so far printed in
the status bar beside it. It is Zed's `which_key`, and it disappears when
you finish the chord, press something that continues nothing, or wait a
second and a half.

Nothing there is tappable: a chord is finished on the keyboard or
abandoned. Every command it lists is in the command palette too, which is
the touch route to the same thing.

## Workspace

These work wherever focus is, including while you're typing in the
editor.

| Shortcut | Action |
|---|---|
| `Ctrl` `Shift` `P` / `F1` | Open the command palette |
| `Ctrl` `S` | Save the active file |
| `Ctrl` `W` | Close the active tab |
| `Ctrl` `Tab` | The tab switcher — hold `Ctrl`, see [Tabs](#tabs) |
| `Ctrl` `Shift` `Tab` | The same, walked backwards |
| `Ctrl` `PageDown` / `PageUp` | Next / previous tab, by position |
| `Ctrl` `1`…`8` | Jump to tab by position |
| `Ctrl` `9` | Jump to the last tab |
| `Ctrl` `Alt` `-` | Go back — the tab and place you were before |
| `Ctrl` `Alt` `Shift` `-` | Go forward again |
| `Ctrl` `N` | New file — type a name, or a path like `src/lib.rs`, and it opens |
| `Ctrl` `B` | Show/hide the left dock |
| `Ctrl` `Shift` `B` | Show/hide the outline panel — see [Outline](#outline) |
| — | Clear the notifications (`workspace: clear all notifications` in the palette) — see [Notifications](#notifications) |
| `Ctrl` `O` | Open the project picker (switch, create, import, export) |
| — | Add another folder to the project (`workspace: add folder to project` in the palette, the `+` on the project panel's top row, or its menu) — see [Several folders in one project](#several-folders-in-one-project) |
| — | Stop showing one of them (`workspace: remove worktree from project` in the palette, or **Remove Folder from Project** on that folder's row) |
| `Ctrl` `Alt` `O` | Open a recent project — the fuzzy list of what you've opened before, newest first |
| — | Close the project and go back to the picker (`workspace: close window` in the palette) |
| `Ctrl` `T` | Search the project's symbols — every running language server is asked |
| `Ctrl` `Shift` `;` | Show or hide inlay hints (Zed's `Ctrl` `:`) |
| `Ctrl` `P` | Find a file by name (fuzzy) |
| `Ctrl` `F` | Find in the open file |
| `Ctrl` `H` | Find and replace in the open file |
| `Ctrl` `Shift` `F` | Search every file in the project |
| `Ctrl` `G` | Go to a line (and column) |
| `Ctrl` `Shift` `M` | Show/hide the preview of the open file |
| `Alt` `Enter` | In a multibuffer, open the file the cursor's excerpt came from |
| `Ctrl` `Shift` `T` | Reopen the tab you closed last |
| `Ctrl` `Shift` `E` | Reveal the open file in the project panel |
| `Ctrl` `Shift` `Enter` | Open the file with another app — Zed's `workspace: open with system`; on the selected row while the project panel has focus, on the active tab otherwise |
| — | Share the file (`project panel: share` in the palette — Android-only, there is no share sheet in Zed; also in the tab and project-panel menus) |
| `Ctrl` `Shift` `G` | Show/hide the git panel |
| `Ctrl` `Alt` `Shift` `B` | Switch git branch (the branch picker) |
| `Ctrl` `Alt` `A` | Show/hide the agent panel |
| `Ctrl` `Shift` `R` | Open the agent's **Review changes** tab |
| `Ctrl` `Shift` `.` | Add the editor's selection to the agent thread |
| `Ctrl` `,` | Open settings |
| — | Edit settings.json as a tab (`zed: open settings file` in the palette, or the ☰ menu) |
| `Ctrl` `K`, then `Ctrl` `S` | Edit keymap.json as a tab (`zed: open keymap` in the palette, the ☰ menu, or the Settings screen's link) — see [Your own keymap](#your-own-keymap) |
| — | Read the built-in default keymap, every binding in keymap.json form (`zed: open default keymap` in the palette) |
| — | Edit the project's `.zed/settings.json` (`zed: open project settings` in the palette, the ☰ menu, or the Settings screen's link) |
| — | Read the built-in defaults, every key documented (`zed: open default settings` in the palette, the ☰ menu, or the Settings screen's link) |
| `Ctrl` `=` / `Ctrl` `-` | Make the **editor text** bigger / smaller (zoom the picture, while one is the open tab — see [Pictures, sound and video](#pictures-sound-and-video)) |
| `Ctrl` `0` | Back to the size `buffer_font_size` says (or the picture's 100%) |
| — | Make the **interface** bigger / smaller (`zed: increase ui font size` / `decrease` / `reset` in the palette, or Settings → Interface size) |
| — | Pick a theme (`theme selector: toggle` in the palette, or the ☰ menu) |
| — | Pick an icon theme (`icon theme selector: toggle` in the palette, or the ☰ menu) |
| — | Choose the line ending the file is saved with — `LF` or `CRLF` (`line ending selector: toggle` in the palette, or tap the `LF`/`CRLF` item in the status bar) |
| `Ctrl` `K`, then `N` | Reopen the file in another encoding, or save it in one (`encoding selector: toggle` in the palette, or tap the encoding item in the status bar) |
| `Ctrl` `K`, then `M` | Choose the language the buffer is parsed as (`language selector: toggle` in the palette, or tap the language name in the status bar) |
| `Ctrl` `K`, then `V` | Show/hide the preview — Zed's other preview chord |
| `Ctrl` `Alt` `T` | Close every other tab (pinned ones stay) |
| `Ctrl` `K`, then `W` | Close every tab (pinned ones stay) |
| `Ctrl` `K`, then `T` | Close the tabs to the right |
| `Ctrl` `K`, then `Shift` `Enter` | Pin or unpin the active tab |
| `Ctrl` `K`, then `Z` / `Ctrl` `Z` | Wrap long lines, or stop |
| — | Install a language server (`seeker: install language server` in the palette) |
| — | Choose the project's toolchain — a Python virtualenv or a rustup toolchain (`toolchain: select` in the palette, or tap the toolchain name in the status bar) |
| ``Ctrl` ` `` | Show/hide the terminal |
| ``Ctrl` `Shift` ` `` | Open another terminal |

In the file finder and project picker: `↑` `↓` move, `Enter` opens,
`Esc` closes.

## Tabs

**`Ctrl` `Tab` is held, not tapped.** Holding `Ctrl` and pressing `Tab` raises
a list of the open tabs in the order you last looked at them, most recent
first; each further `Tab` moves one down it, `Shift` `Tab` moves back up, and
letting `Ctrl` go switches to whatever is highlighted. So one press always
means "the file I was just in", however many times you press it — which is
what Zed's `tab_switcher::Toggle` does. `Ctrl` `PageUp` / `PageDown` still walk
the strip by position.

For a finger there is no `Ctrl` to hold, so the `⇥` button at the right end of
the tab strip opens the same list and a tap on a row switches to it.

| Shortcut | Action |
|---|---|
| `Ctrl` `Tab` (held) | The most-recently-used tab switcher |
| `Ctrl` `Shift` `Tab` (held) | The same list, walked backwards |
| `Ctrl` `PageDown` / `PageUp` | Next / previous tab by position |
| `Ctrl` `1`…`8` / `Ctrl` `9` | Jump to a tab by position / to the last one |
| `Ctrl` `W` | Close the active tab |
| `Ctrl` `Shift` `T` | Reopen the tab you closed last |
| — | Close others / left / right / clean / all, and pin — the tab's own menu, or the palette |

Right-click or long-press a tab for that menu. Long-press and *drag* reorders
the tab instead; pinned tabs stay at the left, and a tab dragged to either edge
scrolls the strip. Double-click a tab to make it permanent (see below).

**Preview tabs.** A single click in the project panel opens the file in a
*preview* tab: its title is in italics, and the next single click reuses that
same tab rather than adding another — so reading through a directory costs one
tab, not twenty. Editing the file, double-clicking its tab, or opening it with
`Enter` from the panel makes it permanent. `preview_tabs` in settings.json
turns this off, or turns it on for the file finder and for go-to-definition:

```jsonc
"preview_tabs": {
  "enabled": true,
  "enable_preview_from_project_panel": true,
  "enable_preview_from_file_finder": false,
  "enable_preview_from_code_navigation": true
}
```

**What a tab shows** is Zed's `tabs` block, and every key is off or `right` by
default, as in Zed:

```jsonc
"tabs": {
  "close_position": "right",     // or "left"
  "file_icons": false,           // the file's icon in its tab
  "git_status": false,           // tint the title by git status
  "show_diagnostics": "off",     // "errors" or "all" adds a dot
  "activate_on_close": "history" // "neighbour" or "left_neighbour"
}
```

`"max_tabs": 12` caps the pane: opening one past the cap closes the tab you
have gone longest without looking at. Pinned tabs and tabs with unsaved edits
are never the one that goes.

Every one of these except `max_tabs` has a row in **Settings** → *Tabs*, so
none of it needs the file.

## Project panel

The tree is the file manager. Every row answers to a tap, a right-click, a
long-press and the keyboard.

| Shortcut | Action |
|---|---|
| `↑` `↓` | Move the selection |
| `Shift` `↑` / `Shift` `↓` | Extend the selection to the next row |
| `→` / `←` | Expand / collapse, or step out to the parent |
| `Enter` | Open the file — permanently |
| `Space` | Open it as a preview tab |
| `Ctrl` `→` / `Ctrl` `←` | Expand all / collapse all |
| `Home` / `End` | First / last row |
| `Ctrl` `N` / `Ctrl` `Shift` `N` | New file / new folder (a path like `src/ui/Panel.kt` works) |
| `F2` | Rename |
| `Delete` | Move to the app's trash — undoable |
| `Shift` `Delete` | Delete permanently |
| `Ctrl` `Z` | Undo the last trash |
| `Ctrl` `X` / `Ctrl` `C` / `Ctrl` `V` | Cut / copy / paste |
| `Ctrl` `D` | Duplicate |
| `Ctrl` `Alt` `C` | Copy the absolute path |
| `Ctrl` `Alt` `Shift` `C` | Copy the project-relative path |
| `Ctrl` `Alt` `Shift` `F` | Search inside this directory |
| `Menu` / `Shift` `F10` | Open the context menu on the selected row |
| `Esc` | Back to a single selection |

**Selecting several.** `Ctrl`-click toggles a row in and out of the selection
and `Shift`-click takes everything between it and the last one clicked; cut,
copy, drag and both deletes then act on the whole set. A finger has neither
modifier, so the context menu's **Select…** turns on selection mode: every row
grows a checkbox and a tap ticks it, until **Done selecting**.

**Moving things.** Long-press a row and drag it onto a folder — the folder
lights up as you pass over it, and letting go moves the selection into it. A
drop that would replace something asks first. Cut and paste does the same job
from the keyboard.

**Deleting.** `Delete` is Zed's `project_panel::Trash`: entries move into a
trash directory inside the app's own storage, and a strip at the top of the
panel offers **Undo** until you take it or delete something else. `Shift`
`Delete` is `project_panel::Delete` and does not come back.

**The context menu** carries New File, New Folder, Open, Open in Terminal
(a shell in that directory), Search in Directory (project search with the
folder already in the include field), Select, Cut, Copy, Duplicate, Paste,
Copy Path, Copy Relative Path, Rename, Move to Trash, Delete Permanently,
Undo Trash, Reveal Active File, Expand All and Collapse All.

**Settings** — Zed's `project_panel` block:

```jsonc
"project_panel": {
  "sort_mode": "directories_first", // or "mixed", "files_first"
  "hide_root": false,               // drop the project's own name row
  "auto_fold_dirs": true,           // draw "a/b/c" as one row
  "entry_spacing": "comfortable",   // or the tighter "standard"
  "indent_size": 20,
  "show_diagnostics": "all"         // "off", "errors" or "all"
}
```

With `show_diagnostics` on, a file its language server has complained about is
coloured — and so is every folder above it, so a collapsed `src/` still tells
you something under it is broken.

Every one of these except `indent_size` has a row in **Settings** →
*Project panel*.

In the project picker's forms — new project, and clone — `Enter` confirms,
`Tab` and `Shift` `Tab` move between fields, and `Esc` goes back to the
project list.

**Clone** has no chord: `Ctrl` `Shift` `G` is the git panel in Zed and is the
git panel here, and cloning is something one does once per repository. It is in
the command palette, the `☰` menu and the project picker's own footer. Cloning
runs the git inside the Linux userland, so a build with no userland would leave
the command and its menu entry out entirely rather than showing them greyed —
see [USERLAND.md](USERLAND.md).

**Install a language server** has no chord either, and for the same reason: it
is done once per language. The two ways in are the command palette and the
status bar, which says `clangd is not installed` when a project needs a server
the userland has not got — click that. In the prompt, `Enter` installs (and,
once it has, closes), `Esc` closes without stopping an install that is already
running, and `↑` `↓` `Tab` `Shift` `Tab` move through the list of languages.
Nothing is ever installed without being asked for, and the question says what
the download will cost. Like cloning it needs the Linux userland, because that
is where apt lives, and it follows the same rule: absent rather than greyed
where there is nowhere to install into.

**Select toolchain** has no chord either, and neither does Zed: `ctrl-k ctrl-m`
there is `toolchain::AddToolchain`, a different action, so inventing a chord
here would collide the day that one arrives. It is reached from the command
palette and from the status bar, which shows the active toolchain's name once
one is chosen. The list is the project's own virtualenvs — anything with a
`pyvenv.cfg` in the root or one directory down, so `backend/.venv` is found —
plus `poetry env info`, `rustup toolchain list`, and the `python3` and `cargo`
on the userland's own PATH. Choosing one exports `VIRTUAL_ENV` and puts its
`bin/` at the front of `PATH` for the language servers and for tasks, and
restarts the project's servers, since a server already running holds the old
environment. The first row, **None**, clears every language's choice; picking
a toolchain replaces the choice for *its* language only, so a rustup toolchain
does not disturb a virtualenv. The choice is remembered per project and per
language — in the app's own storage, not in the
project's `.zed/settings.json`, because an absolute interpreter path is this
device's and does not belong in somebody's repository.

**`Ctrl` `=` is the editor's text, not the interface's.** That is Zed's own
arrangement: its global keymap binds `ctrl-=` / `ctrl-+` / `ctrl--` / `ctrl-0`
to `zed::IncreaseBufferFontSize` and its siblings, and gives the interface's
size those keys only on its onboarding screens. **Pinching the editor with two
fingers does the same thing**, which is the route that matters when there is no
keyboard. Like Zed's, the chords carry `persist: false`: they move a zoom over
`buffer_font_size` rather than rewriting it, so the size the settings screen
shows stays the one you chose. (Ours does remember the zoom across launches,
where Zed's forgets it when the window closes — Android ends processes for its
own reasons mid-session, and a pinch that undid itself in your pocket would
read as a bug.)

**The interface size is Zed's `ui_font_size`, and it is the unit the whole
chrome is measured in** — rows, bars, gaps and icons grow with it, not just
the text. It lives in settings.json like everything else; the routes to it are
**Settings → Interface size** and the three `zed: … ui font size` commands in
the palette. It has no chord, because those keys are the editor's.

**Themes, fonts and icon themes can be added.** Beside settings.json in the
app's private storage are three folders — `themes`, `fonts` and `icon_themes`
— each watched, so a file written from the terminal or copied in with
**Import theme…** in the theme picker shows up without a restart. A theme file
is Zed's own format (a family with a `themes` array); one that is not is named
in the picker with the reason rather than silently ignored. `theme` takes
Zed's object form, `{"mode": "system", "light": …, "dark": …}`, and the
picker's Appearance row is that `mode`; `theme_overrides` lays a partial style
object over whichever theme is in force. `buffer_font_family` and
`ui_font_family` can name a bundled face, one from the `fonts` folder, or one
already on the device, with `buffer_font_fallbacks`, `buffer_font_weight`,
`buffer_line_height` and a ligatures switch beside them — all with rows under
**Settings → Appearance**. `icon_theme` chooses between the bundled
`Zed (Default)` set and any icon theme JSON in `icon_themes`.

**Line endings and encodings are read from the file and written back to it.**
A file opens with its line ending detected from its first line break — `CRLF`
or `LF` — and its encoding from its bytes: a byte-order mark decides, UTF-16
without one is told by its null bytes, anything that reads as UTF-8 is UTF-8,
and what is none of those is taken as Windows-1252, so no text file refuses to
open. The buffer itself is plain `LF` UTF-8, as Zed's are; the save puts the
file's own shape back. Both show in the status bar, to the right of the caret
position, as Zed lays them out: `1:1  CRLF  Rust  UTF-8 BOM`. Tapping the line
ending opens a two-row picker; choosing the other one marks the file dirty and
the next `Ctrl` `S` writes it. Tapping the encoding opens Zed's "Reopen with
encoding…" list: `Enter` or a tap on a row re-reads the file in that encoding
(a file that came up as mojibake is put right this way, undoably), and **Save
with encoding** — `Ctrl` `S` in the picker — writes the file in the chosen one
instead, with or without a byte-order mark for UTF-8 and UTF-16. Reopening is
off while the file has unsaved edits, since it would drop them; saving with an
encoding is not.

**Every item in that row is a button, as in Zed.** Tapping the caret position
opens go-to-line (`Ctrl` `G` with a keyboard); tapping the language name opens
the language selector — the same list as `Ctrl` `K`, then `M` — which parses
the buffer as whatever you choose, without touching the file or your settings,
and puts a check beside the one in use. To the left of them the diagnostics
summary counts the project's errors and warnings, and tapping it opens the
Diagnostics tab. Right of them, when the project has a toolchain chosen, its
name is a button too, and it opens the toolchain selector.

## Several folders in one project

A project can show more than one folder, exactly as Zed's projects hold more
than one worktree. **Add Folder to Project** is in the command palette, on the
`+` at the right of the project panel's top row, and in the panel's menu; each
folder that arrives gets a root row of its own, with its own expand state, its
own **New File…**/**Rename…**/**Delete…** and a **Remove Folder from Project**
on its menu. The folder the project was opened with cannot be removed —
closing the project is what letting go of that one means.

Everything that looks across a project looks across all of them: the file
finder (which puts the folder's name at the end of a row once there is more
than one to tell apart), project search, and `.zed/settings.json`, where the
project's own folder wins if two of them have one. Paths outside the project's
own folder are written `folder/path/to/file`, which is what tabs, search
results and **Copy Relative Path** all use.

Adding a folder means the same thing here as opening one: the engine scans
real paths and the only place this app can put one is its own storage, so a
folder that is already there — another project — is added where it is, and
anything else is copied in first. The dialog says which is about to happen.

Two things stay with the project's own folder for now: the git status colours
in the panel, and the sticky headers, which switch off while more than one
folder is open.

## Panes

The work area splits into panes, as Zed's does: each pane has its own
tabs, its own active tab, its own reopen stack and its own back/forward
history, and every command that acts on "the active tab" — save, close,
pin, `Ctrl` `1`…`9`, go back — acts on the active pane's. The active pane
is the one you last touched or typed in; with more than one on screen it is
outlined. Opening a file from the project panel, the file finder or a search
hit opens it in the active pane, and a file that is already open in another
pane opens *again* — a second view of the same buffer, with its own caret,
which is what Zed does. Closing a pane's last tab removes the pane, unless
it is the last one.

| Shortcut | Action |
|---|---|
| `Ctrl` `K`, then `→` / `Ctrl` `\` | Split right — a second view of the active tab in a new pane to the right |
| `Ctrl` `K`, then `←` | Split left |
| `Ctrl` `K`, then `↑` | Split up |
| `Ctrl` `K`, then `↓` | Split down |
| `Ctrl` `K`, then `Ctrl` `→` / `←` / `↑` / `↓` | Move focus to the pane in that direction |
| `Alt` `1`…`9` | Focus a pane by position (left to right, top to bottom) |
| — | Next / previous pane in that order (`workspace: activate next pane` / `activate previous pane` in the palette — unbound in Zed too) |
| `Ctrl` `K`, then `Shift` `→` / `←` / `↑` / `↓` | Swap the active pane with its neighbour in that direction |
| — | Join the active pane's tabs into the next pane — right, then down, left, up (`pane: join into next` in the palette or the tab bar's `⊞` menu) |
| — | Join every pane's tabs into the active one (`pane: join all`) |
| `Shift` `Esc` | Zoom — the active pane alone in the work area, and back again |

A split copies the active tab into the new pane — Zed's default
`SplitMode::ClonePane`. A tab that cannot be shown twice — a diff, the
commit graph, the diagnostics, the agent's review — moves instead when the
pane has other tabs, and with nothing else there the new pane opens empty
on the far side so the tab ends up on the side you asked for, which is
also what Zed does. Two views of one file share one buffer: typing in one
shows in the other, and closing one of them never asks about unsaved
edits, because they are still on screen next door.

The divider between two panes drags. A pane never goes narrower than
about 80px or shorter than about 100px. Zooming follows focus: moving to
another pane un-zooms.

On a phone-width screen (under 600dp) the tree is capped at **two** panes,
and a third split is refused with a sentence rather than a sliver.
Folding the window narrower and unfolding it again keeps the layout —
the cap only stops new splits.

## Coming back to a project

Close the app on a project and open it again: the tabs, the split layout,
the carets, the scroll, the pinned tabs, the back/forward history, which
panel is open on each dock and how wide it is all come back the way you
left them. A tab that was a **preview** comes back a preview, so the pane
has the one provisional slot it had when you left and the next single click
replaces the same tab it would have replaced. Zed does the same from its workspace database; this app writes
one small JSON document per project into its private storage instead.

It is written a second after the last change and again when the app goes to
the background, so switching away is enough — there is nothing to save by
hand.

**Terminals are the exception, and cannot not be.** A shell is a process
tree, and Android ends it with the app. What is remembered is each terminal
tab's name and the directory it was standing in, and reopening the project
starts **fresh shells** in those directories — the history, the running
build and the scrollback are gone. Terminals come back only if the terminal
dock was open when you left; a closed dock comes back closed and empty
rather than quietly starting shells you cannot see.

Anything that has moved on since is dropped rather than restored wrongly: a
tab whose file was deleted does not come back, a caret past the end of a
file that has shrunk lands on the last line, and a session document that has
been corrupted is thrown away with a line in the log rather than taken as
gospel. Tabs that are views rather than files — a diff, the commit graph,
the problems tab, the agent's review, a language-server log, a
[multibuffer](#multibuffers) — are not written down at all: a stale one
would be a lie, and each is one keypress away. A multibuffer in particular
is a snapshot of a search or a set of references that has moved on by the
time you come back; reopening the search is what puts a true one on screen.

How much comes back is Zed's `restore_on_startup`, in **Settings ▸
Workspace ▸ Restore on startup** or in settings.json:

| Value | What happens at launch |
|---|---|
| `"last_session"` (default) | The last project, and everything in it |
| `"last_workspace"` | The last project, with no tabs — a fresh workspace |
| `"none"` | No project: the app starts on the project picker |

`Ctrl` `Alt` `O` — Zed's `projects::OpenRecent` — is the fuzzy list of the
projects you have opened before, newest first, matching on the name and
then the path. `×` on a row is Zed's **Remove from Recent Projects**: it
takes the project off the list and forgets its saved workspace, and does
not touch a byte on disk. Deleting a project is the other picker's job
(`Ctrl` `O`), and says so.

`workspace: close window` in the palette closes the project — writing its
session down first — and leaves you on the picker, which is also where
`"restore_on_startup": "none"` starts. With no project open the picker
cannot be dismissed: there would be nothing behind it.

**Settings ▸ Workspace ▸ Deleted files** is Zed's `close_on_file_delete`:
off by default, and when on, a tab whose file is deleted on disk closes
itself. A tab with unsaved edits is never closed this way.

## Git in the editor

The gutter carries a bar down its left edge for every line that differs from
the last commit — Zed's own strip, at Zed's own width (floor of 0.275 × the
line height) and in the colours the project panel already uses: added,
modified, and a rounded pill on the boundary where lines were deleted.

The end of the caret's line says who last touched it — Zed's `inline_blame`,
on by default as in Zed, and switchable in **Settings** → *Inline blame*. It
appears only while the file has **no unsaved edits**: blame describes the file
on disk, and once it is edited those line numbers describe a file that is not
there any more. It runs git when the file is opened and after each save, never
on a keystroke.

### Hunks

The chords are Zed's, from its Linux keymap, and every one is a palette
command under Zed's name — `editor: go to hunk`, `git: toggle staged` and so
on — so a finger reaches them from `F1` too. They act on the hunk under the
caret, or under every caret and selection where that makes sense.

| Shortcut | Action |
|---|---|
| `Alt` `.` or `Ctrl` `F8` | Go to the next hunk (`editor::GoToHunk`) — wraps around the file |
| `Alt` `,` or `Ctrl` `Shift` `F8` | Go to the previous hunk |
| `Ctrl` `'` | Expand the hunks under the cursors, or collapse them if any is open (`editor::ToggleSelectedDiffHunks`) |
| `Ctrl` `Shift` `'` | Expand every hunk in the file (`editor::ExpandAllDiffHunks`) — Zed's `ctrl-"` |
| `Ctrl` `Alt` `Y` | Stage the hunk under the cursor, or unstage it if the index already holds it (`git::ToggleStaged`) |
| `Alt` `Y` | Stage it and go to the next hunk (`git::StageAndNext`) |
| `Alt` `Shift` `Y` | Unstage it and go to the next hunk (`git::UnstageAndNext`) |
| `Ctrl` `K`, then `Ctrl` `R` | Restore the hunk — the commit's lines come back over it (`git::Restore`) |
| `Esc` | Collapse every expanded hunk, once nothing else is left to close |

**Tapping a gutter bar** — the coloured strip, or the pill of a deletion —
expands that hunk, as a click on the bar does in Zed; tapping it again
collapses it. An expanded hunk is a block *above* the hunk's own rows: a
header row, then the lines the last commit had there on Zed's
`deleted.background`, read-only, while the hunk's rows below wear
`created.background`. The header carries **Stage** (or **Unstage**, when the
index already holds the hunk), **Restore**, and a chevron that closes the
block; these are the touch route to the chords above.

Staging a hunk stages *that hunk*: the engine writes the index with only
those rows changed and applies it with `git apply --cached`, so the rest of
the file stays unstaged and the git panel's row reads as partly staged.
Restoring is an ordinary edit of the buffer — `Ctrl` `Z` undoes it — and the
file is dirty until saved, exactly as in Zed. What git says when it refuses
lands in a banner over the text; tap it to dismiss.

The caret steps over a block as though it were not there: `↑` from the row
below a header lands on the row above it.

### The blame column

| Shortcut | Action |
|---|---|
| `Alt` `G`, then `B` | Show or hide the blame column (`git::Blame`) |

Zed's full-file blame, left of the line numbers: the short hash in a player
colour so one commit's rows share a colour and neighbouring commits do not,
the author truncated to 20 characters, and the relative date at the right.
**Tapping a row's entry** opens its popover — the author and date, the hash,
the whole commit message, and **View on GitHub** when the repository's
`origin` is on github.com; tapping the entry again, or the popover, closes
it. Like inline blame, the column is shown only while the file has no unsaved
edits, and it is per editor, as Zed's is.

## Merge conflicts

A file git could not merge opens with each conflict on tinted rows — the
`<<<<<<<` line and *ours* in Zed's `version_control.conflict_marker.ours`,
the rest of the region down to `>>>>>>>` in `.theirs` (a theme without those
keys gets a wash of its added and deleted colours) — and Zed's three buttons
on the `<<<<<<<` line: **Use HEAD**, **Use `branch`**, **Use Both**, named for
the labels git wrote after the markers. Tapping one replaces the whole region,
markers included, with the side or sides you chose — one edit, so `Ctrl` `Z`
brings the conflict back. A `diff3` base, when git wrote one, is shown but is
never what a button keeps; Zed offers no button for it either.

Zed has no chords for conflicts and neither does this: the commands are in
the palette, greyed while the open file has none. They take a chord like any
other action in [your own keymap](#your-own-keymap) — `git::GoToNextConflict`,
`git::GoToPreviousConflict`, `git::UseOurs`, `git::UseTheirs`, `git::UseBoth`.

| Command | Action |
|---|---|
| `git: go to next conflict` | Put the caret on the next `<<<<<<<` (wraps) |
| `git: go to previous conflict` | The previous one (wraps) |
| `git: use ours` | Resolve the conflict under the caret keeping ours |
| `git: use theirs` | …keeping theirs |
| `git: use both` | …keeping both, ours first |

With the soft keyboard up, the row above it grows `conflict↑` / `conflict↓`
keys while the file has any.

In the git panel a conflicted file's row carries **Resolve** where other rows
have their staging checkbox; it, a tap on the row, `Enter` and the long-press
menu's **Resolve** all open the file on its first conflict (the menu's
**Open** still opens the diff). Once the last conflict in a file is gone, a
strip over the editor says so and offers **Mark resolved (stage)** — it saves
and runs `git add`, which is how git is told a conflict is settled. It is what
the Conflicts section of the panel is waiting for before a commit can go.

## Diffs

Tapping a changed file in the git panel opens its **diff** — what Zed does with
a click on a change, and the more useful answer to "what did I do here". It is
a tab, not a dock: a diff is a document, and it belongs beside the file it is
about. **View diff** at the top of the panel opens every change at once.

The view is unified — old and new in one column, added lines on green, removed
on red, both line numbers down the left — rather than side by side, which on a
phone means two twenty-column panes. It follows the repository: stage a file or
type in the editor beside it and the diff catches up.

On the working tree's diff — the panel's **View Diff** and a changed file's
row — every file header carries **Stage** (or **Unstage**, when the whole
file is in the index) and every `@@` header **Stage** / **Unstage** and
**Restore** for that hunk, through the same engine calls the editor's
expanded hunks use: Zed's project-diff staging. A commit's diff, the staged
view and a branch diff are history and carry no buttons. A restore of a file
that is open edits the buffer (undoable, dirty until saved); one with no open
buffer writes the file.

`Push` sends the commits you have made; on a branch nobody has pushed it reads
**Publish** and creates it on the remote, which is Zed's wording and the
accurate one. There is no credential helper inside the userland, so an HTTPS
remote will fail with git's own words about authentication — SSH with a key in
the userland's `~/.ssh` is the way that works today.

## The branch picker

The branch name — in the git panel's header, or in the title bar — opens the
**branch picker**, Zed's own: every local and remote branch with its last
commit, filtered as you type, `Enter` checks out. A remote branch checks out
by growing a local tracking branch named after it, exactly as Zed does. A name
no branch has becomes a **Create Branch** entry — `Enter` branches off HEAD,
`Ctrl` `Enter` off the repository's default branch. `Ctrl` `Shift`
`Backspace` deletes the selected branch (`Alt` on top force-deletes; a branch
that is not fully merged asks first), `Ctrl` `Shift` `I` cycles the
all/local/remote filter and `Ctrl` `K` opens it as a menu.

## The commit graph

**Graph** in the git panel's History tab — or `git: open graph` in the palette,
or `☰` → **Git graph** — opens the history as Zed's graph: the lanes down the
left that show where a branch forked and where it came back, then the
description, the date, the author and the short hash. Below 640dp the last
three fold onto a second line, because five columns on a phone is one column of
ellipses.

It loads a hundred commits at a time and asks for more as you reach the end.
Tapping a row shows that commit's message and the files it touched; tapping a
file opens it.

## Wrapping long lines

Off by default, as in Zed. **Settings** → *Wrap long lines*, `☰` → **Wrap long
lines**, or the command palette's `editor: toggle soft wrap` — Zed's own action,
on Zed's own chords, `Ctrl` `K` then `Ctrl` `Z` (or `Ctrl` `K` then `Z`).
Whichever route, it writes `soft_wrap` to settings.json, so it survives a
restart.

The third answer is Zed's `"bounded"`: wrap at `preferred_line_length` (80 by
default; **Settings** → *Preferred line length*) or the editor's width,
whichever comes first, with the column drawn as a wrap guide while it is in
use. `"wrap_guides": [80, 120]` in settings.json draws further guides without
wrapping at them.

Wrapped or not, the caret keys mean what they always meant: `Home` and `End` go
to the ends of the *line*, not of the screen row.

## Settings

`Ctrl` `,` opens the settings screen: rows for what has a row — under
**Appearance**, the light/dark mode, the theme and icon theme pickers, the
interface size and font, and the editor's font, weight, ligatures and line
height, each font row previewing itself in the face it names; then sizes, tab
width, *Indent with* (Zed's `hard_tabs`), wrapping, *Preferred line length*,
*Format on save*, *Autosave*, inline blame, the panels' docks and the agents —
under a filter box, and links at the bottom to the three files that hold
everything else. The keys are Zed's, in Zed's file layout
(`docs/src/configuring-zed.md`):

- **Per language.** `"languages": { "Rust": { "tab_size": 4, "hard_tabs":
  false, "soft_wrap": "editor_width", "format_on_save": "on",
  "preferred_line_length": 100 } }` — keyed by the language's name as Zed
  spells it (`Rust`, `C++`, `TypeScript`, `TSX`, `Go`, `Python`, `Markdown`).
  The editor asks the engine for each open buffer's *resolved* settings, so
  a Go file indents with tabs while a Rust file beside it uses spaces.
  `"enable_language_server": false` in a language's entry keeps its server
  from starting.
- **Which language a file is.** `"file_types": { "JSON": ["*.jsonc",
  ".babelrc"], "XML": ["**/res/**/*.axml"] }` — keyed by the language as the
  selector lists it, with the globs that claim it. It is asked *before* the
  built-in extension table, so it can take a suffix away from it as well as
  add one: `{"JSONC": ["*.json"]}` makes every `.json` in the project
  JSON-with-comments. A project's `.zed/settings.json` may set it too, and
  replaces the user's rule for the languages it names rather than adding to
  it, so a repository can narrow something you set widely. `Ctrl` `K`, then
  `M` still overrides the answer for one buffer.
- **Per project.** A `.zed/settings.json` in the project root overlays the
  user file for that project only — the editor, language, `lsp` and `git`
  keys; never the theme, the panels or `agent_servers`, exactly as Zed limits
  its project settings. It is read when the project opens and re-read when
  the file changes, from a save in the editor or a `git pull` in the
  terminal alike; a file that does not parse is refused whole and says so in
  a bar over the editor. `zed: open project settings` creates it.
- **Format on save.** `"format_on_save": "on"` runs the formatter before
  every save: the `code_actions_on_format` kinds first (`{
  "source.organizeImports": true }`), then the `formatter` — `"auto"` or
  `"language_server"` ask the running language server for
  `textDocument/formatting`; `{ "external": { "command": "rustfmt",
  "arguments": ["--edition", "2021"] } }` runs the program inside the Linux
  userland with the buffer on stdin and takes its stdout, `{buffer_path}` in
  an argument becoming the file's path. `"language_server"` as the
  `format_on_save` value means the server even when an external formatter is
  configured. A formatter that fails says so in the bar over the editor and
  the save goes ahead. Without the userland an external formatter is skipped
  with the same bar.
- **Language servers.** `"lsp": { "rust-analyzer": { "binary": { "path":
  "/root/.cargo/bin/rust-analyzer", "arguments": [] },
  "initialization_options": { … }, "settings": { … } } }` — the names are the
  servers' own (`rust-analyzer`, `clangd`, `gopls`, `pylsp`,
  `typescript-language-server`). `binary.path` is a program inside the
  userland and replaces the built-in command line; `initialization_options`
  go out with `initialize`; `settings` answer the server's
  `workspace/configuration` requests, section by section. A change takes
  effect when the server next starts, as in Zed.
- **Autosave.** `"off"`, `"on_focus_change"` (the tab you leave is saved),
  `"on_window_change"` (every dirty tab is saved when the app goes to the
  background), or `{ "after_delay": { "milliseconds": 1000 } }` (a tab that
  has sat unedited that long is saved). Delayed saves skip the formatter, as
  Zed's do.

## Welcome and About

The first time the app runs it shows a welcome screen: what it is, the
three things to try — open a project, open the command palette, open a
terminal, each a row that does it, with its chord beside it — and the
light/dark choice. **Don't show this again** is ticked; leave it clear to
see it next launch.

It is never gone. `zed: open onboarding` in the palette, **Welcome…** in
the `☰` menu, and **Show the welcome screen** in Settings all bring it
back.

**About** — `zed: about` in the palette, **About…** in the `☰` menu, or
**About Seeker IDE** in Settings — is what a bug report is pasted from:
the app version and edition, the engine's version, the Zed commit the
vendored crates were copied from, the device, the Android version, the ABI
and the kernel's page size. **Copy** puts the lot on the clipboard as plain
`Label: value` lines. The last two matter more than they look: an arm64 bug
and an x86_64 bug are different bugs, and a device with 16 KiB pages is the
first thing to ask about when the engine will not load at all.

## The frame around the editor

Zed lets you switch off the parts of the frame you do not use, and so does
this. Each key hides the thing it names and nothing else; every command
behind a hidden button is still in the command palette, so switching a bar
off never takes an action away.

| Key | What it hides |
|---|---|
| `tab_bar.show` | The tab strip. `Ctrl` `Tab`, the palette and the project panel still reach every tab |
| `tab_bar.show_nav_history_buttons` | The `←` `→` group at the left of the strip |
| `tab_bar.show_tab_bar_buttons` | The `⇥` `+` `⊞` `⤢` group at the right |
| `toolbar.breadcrumbs` | The file name and the symbol at the caret |
| `toolbar.quick_actions` | Find in file, project symbols and the preview toggle |
| `toolbar.selections_menu` | The selection-controls menu |
| `status_bar.active_language_button` | The language name in the status bar |
| `status_bar.cursor_position_button` | The `line:column` readout |

With all three `toolbar` keys off, the toolbar is not drawn at all rather
than left as an empty bordered strip. The rows are in **Settings** →
*Chrome*.

### The selections menu

The I-beam in the toolbar opens Zed's selection controls — the multi-caret
actions and the jumps, each with the chord that also runs it: **Select
All**, **Select Next Occurrence**, **Add Cursor Above** / **Below**, **Go
to Symbol**, **Next** / **Previous Problem**, **Next** / **Previous
Hunk**, **Move Line Up** / **Down**, **Duplicate Selection**.

## Accessibility

- **The editor with a screen reader.** The editor is a drawn canvas, not a
  stack of views, so it carries a description instead: the file, the line
  and column, and the text of the line the caret is on, re-announced as the
  caret moves. A selection is counted — in characters on one line, in lines
  across several — and more than one cursor is said. A diagnostic under the
  caret is announced separately, once, when it appears.
- **Everything is named.** Every icon-only button says what it is: the tab
  strip's glyphs, a tab's `✕`, the status bar's readouts, the toolbar's
  icons, and each key of the terminal's extra-key row — which says
  "Escape" and "Left arrow" rather than reading `esc` and `←` aloud, and
  says "held" when `ctrl` or `alt` is latched. A project-panel row says its
  kind, whether a folder is open, its git status, whether it has problems,
  whether it is the open file, and its nesting level.
- **Touch targets.** Small controls draw at Zed's size and are tappable at
  48dp. In a fixed-height bar the target widens rather than making the bar
  taller, so nothing moves.
- **Text size** follows the system font scale everywhere in the interface;
  the editor's own text is `buffer_font_size` and the chrome's is
  `ui_font_size`, both of which scale the whole layout around them.
- **Motion.** `reduce_motion` takes Zed's `"on"` and `"off"`, plus
  `"auto"` — the default here — which follows Android's **Settings ▸
  Accessibility ▸ Remove animations**. Reduced, the scroll animations
  become jumps and the working spinners stand still rather than
  disappearing, so they still say that something is happening.

## Panels and docks

Every panel lives in a dock — left or right — and each one's side is a setting,
as in Zed: **Settings** → *Panels*, or `project_panel.dock` and friends in
settings.json. Its button in the status bar moves with it, so the button is
always on the side the panel will appear. The third answer is `"hidden"`:
the panel is switched off — its button leaves the status bar and its
commands grey out — until the setting says otherwise.

| Shortcut | Action |
|---|---|
| `Ctrl` `B` | Show/hide the left dock |
| `Ctrl` `Alt` `B` | Show/hide the right dock |
| `Ctrl` `Shift` `B` | Show/hide the outline panel |

**One panel at a time per dock, and the two docks are independent.** Opening
git while search is up on the same side replaces it; opening it while the tree
is up on the *other* side leaves the tree alone. When both are open and the
screen cannot hold them at their full widths, they shrink to share what there
is rather than closing each other — and only when even two minimum widths and
an editor will not fit does the most recently opened one take the space, with
the other returning as soon as there is room.

Drag a dock's inner edge to resize it, the same way the terminal dock resizes.
On a phone a dock takes the whole work area, and opening a file from one hands
the area back.

## Notifications

When something goes wrong the editor says so, in a stack of toasts — Zed's
notification stack. **Top right** on a wide screen, **above the status bar** on
a compact one, where a thumb can reach them.

What comes through here: a file that could not be saved, a git command that
failed, a language server that died, a task that exited non-zero, an agent that
could not start or whose turn failed, an external formatter that refused, and
`settings.json`, `.zed/settings.json` or `keymap.json` that will not parse.
Several carry a button — **Show log**, **Show output**, **Open keymap.json** —
which takes you to the thing the message is about.

| Shortcut | Action |
|---|---|
| `Tab` to a toast, then `Esc` | Dismiss it |
| — | Dismiss it with the `✕` on the toast |
| — | `workspace: clear all notifications` in the palette clears the stack |

An **info** toast disappears on its own after about six seconds — twelve if it
carries a button, since a button needs reading. **Warnings and errors stay
until you dismiss them**: a failure nobody saw is a failure nobody fixed. Four
are shown at once and the rest collapse into a **+N more** row; tap it to see
them all, and **Show fewer** or **Clear all** to fold them back.

A message about a *state* rather than an event — a settings file that does not
parse — replaces its own previous toast rather than stacking a second copy, and
goes away by itself when the state does.

## The activity indicator

The left of the status bar shows one turning circle and one sentence for
whatever is running in the background — Zed's activity indicator. The worktree
scan when a project opens, a project search, a `git fetch`, a running task, and
a language server's own progress (`rust-analyzer: indexing (45%)`). When
several are running the newest is printed and the rest are a `+2` beside it.

Tap it to reveal wherever the work is happening: the project panel for a scan,
the search panel, the git panel, the terminal for a task, the server's log for
a language server.

## Git panel

`Ctrl` `Shift` `G` shows the changes in the project — Zed's
`git_panel::ToggleFocus`, on Zed's own chord. Press it again to put the
keyboard back on the file list. The branch button at the left of the status bar
is the same thing for a finger or a mouse, and `☰` → **Git panel** is the route
out of a focused terminal.

| Shortcut | Action |
|---|---|
| `↑` / `↓`, `PageUp` / `PageDown` | Move through the changed files |
| `Space` | Stage or unstage the selected file |
| `Enter` | Open it |
| `Delete` / `Backspace` | Discard its changes, after a prompt that names it |
| `Ctrl` `Enter` | Commit what is staged, from anywhere in the panel |
| `Ctrl` `Shift` `Enter` | Amend — the first press enters amend mode, the second commits |
| `Ctrl` `Space` | Stage everything |
| `Ctrl` `Shift` `Space` | Unstage everything |
| `Ctrl` `1` / `Ctrl` `2` | The Changes / History tab |
| `Esc`, or `✕` | Close the panel |

### The `Ctrl` `G` chords

While the panel has the keyboard, `Ctrl` `G` is a **leader**, exactly as in
Zed's `GitPanel` keymap context: press it, and the next keystroke completes a
two-step chord. A small `Ctrl G …` chip above the commit box says the chord
is waiting. A key that matches nothing cancels it — and does nothing else,
as in Zed — and so do `Esc` and saying nothing for a few seconds, which Zed
(with a status bar that echoes pending keys) does not need. In the editor,
`Ctrl` `G` is still go-to-line, and in a terminal it is still BEL.

| Chord | Action |
|---|---|
| `Ctrl` `G`, `Ctrl` `G` | Fetch |
| `Ctrl` `G`, `↑` | Push |
| `Ctrl` `G`, `↓` | Pull |
| `Ctrl` `G`, `Shift` `↑` | Force push (`--force-with-lease`) |
| `Ctrl` `G`, `Shift` `↓` | Pull with rebase |
| `Ctrl` `G`, `D` | Open the whole project's diff |

Every one of these is also a command in the palette — `git: fetch`,
`git: push`, `git: pull`, `git: force push`, `git: pull rebase`,
`git: stage all`, `git: unstage all`, `git: diff` — which is the route with
no keyboard at all, and the route from a focused terminal. Run from there,
the command opens the git panel and runs in it, so the spinner and whatever
git says back have somewhere to be seen.

### The stash

**Stash** at the right of the changes header opens Zed's stash rows: **Stash
All** (untracked files included), **Stash Tracked**, **Stash Staged** — each
asks for a message first, as Zed's stash modal does; leave it empty and git
writes its own `WIP on <branch>` line — then **Stash Pop**, which pops the
newest entry, and **View Stash**. The same five are palette commands under
Zed's names: `git: stash all`, `git: stash tracked`, `git: stash staged`,
`git: stash pop`, `git: stash apply` (the newest entry, kept in the stash),
and `git: view stash`. No chords, as Zed's Linux keymap gives them none.

`git: view stash` opens the **stash picker** — Zed's `StashList`: every
`git stash list` entry, newest first, as `#N: message` over the branch it was
taken on and when. Type to filter.

| Shortcut | Action |
|---|---|
| `↑` / `↓`, `Tab` / `Shift` `Tab` | Move through the entries |
| `Enter` | Apply the selected stash (it stays in the stash) |
| `Ctrl` `Enter` | Pop it — apply and drop |
| `Ctrl` `Shift` `Backspace` | Drop it, after a prompt |
| `Esc` | Close the picker |

Each row carries a pop and a drop button for a finger, and the footer
repeats **Drop**, **Pop** and **Apply**. A pop or apply that git refuses — a
conflict with what is in the tree — is said in a prompt titled as Zed titles
it, and nothing is changed.

A fetch, pull, push or clone that needs a credential — a token for a private
HTTPS remote, the passphrase of an SSH key, ssh's "are you sure you want to
continue connecting" for a host it has not seen — asks in a dialog titled
with the command, the way Zed's askpass modal does. The field is masked for a
password or passphrase, with an eye button to show it. `Enter` or **OK**
answers; `Esc` or **Cancel** refuses, and git stops with its own message in
the panel's strip. Your username is remembered for the host until the app
closes, so the second push asks for the token alone; tick **Remember this
password for this session** and it does not ask again either. Nothing is
written to disk — see [USERLAND.md](USERLAND.md#git-credentials).

The first commit in a fresh userland fails: git guesses an identity from the
hostname (`root@localhost.(none)`), refuses to use it, and says so. The panel
answers that with a name and email field rather than an error — what you type
goes into the userland's global git config, so it is asked once per userland
and not once per clone, and the commit you pressed runs straight after.

On a wide screen it docks beside the editor; on a phone it takes the work area.
The dock shows one panel at a time — git, project search or the preview — which
is a dock's rule in Zed too, and is what stops three of them sharing a phone
screen and leaving the editor a character wide.

## Agent panel

`Ctrl` `Alt` `A` opens a conversation with an ACP agent working on the open
project. Pressing it again puts the keyboard back in the composer.

**Threads.** Each conversation is a thread, as in Zed: **+ New** in the
panel's bar starts another for the open project, and **Threads** lists every
thread grouped by project, searchable, with the other projects shown so you
can see where threads would live. Tap a thread to return to it — its whole
transcript is kept — and **Close** to end it. Threads live with the agent
process: they survive the panel closing, not the app.

A thread is named after the first thing you say in it, and takes the agent's
own name for the conversation instead as soon as the agent sends one.

**Reasoning.** An agent that thinks out loud gets a **Thinking** line above
its answer. It opens itself while the thought is arriving and closes when the
answer starts; tap it to keep it open, or to read it again later.

**What the agent itself remembers.** Some agents keep their conversations on
their own side. When yours does, the Threads view has a *Kept by the agent*
section under your own threads: tap one to reopen it — with its transcript
where the agent can replay it, without where it can only continue — and
**Forget** to delete it for good. Agents that keep nothing simply have no such
section. **Agent** in the bar lists the agents settings.json configures:
picking one starts a *new* thread with it rather than closing the ones you
have, and signs out of an agent that supports it.

**The composer speaks the protocol.** Type `/` at the start for the agent's
own slash commands (it advertises them; the strip completes them), and `@`
anywhere for a mention — Zed's context picker, as a strip of sections above
the box. **Files** and **Directories** (every text file under it, up to a
size cap) travel as context, embedded when the agent takes embedded context
and as a link otherwise; **Symbols** offers the outline of the open buffers
and sends the symbol's lines; **Threads** attaches another thread's
transcript; **Fetch** takes an `https://` address typed after the `@` and
pulls the page as plain text — only when you take the row, never before;
**Rules** lists the project's rules files (`AGENTS.md`, `CLAUDE.md`,
`.rules`, `.cursorrules` and the rest of Zed's list) — the first message of
every thread carries them on its own, as Zed's does; **Diagnostics** sends
the project's current problems as text; and **Selection** sends what is
selected in the editor, with its file and line range. Every mention shows as
a chip above the box with an `✕`; a file mention also stays in the text as
`@path`, and deleting it there is deleting the mention.

`Ctrl` `Shift` `.` (Zed's `ctrl->`, `agent::AddSelectionToThread`) puts the
editor's selection on the draft from the editor, opening the panel when it
is hidden; `☰` → **Add selection to agent thread** is the same for a finger.

**Checkpoints.** A message whose turn changed files gets a **Restore
checkpoint** link under it: the files the agent edited from that turn on go
back to what they held before, open buffers reload, and the rows after the
message are dimmed and marked *reverted* — the conversation is kept, because
an ACP agent keeps its own context, but the project no longer reflects it.
What a checkpoint covers is what the panel saw the agent write: edits made
through its file access and edits reported on a completed tool call. A file
the agent changed some other way — a shell command it ran itself — was
never checkpointed and is not restored.

**Review changes.** Every file the active thread's agent edited, in one tab
(Zed's `agent::OpenAgentDiff`): each file as a unified diff from before the
agent's first touch to now, with **Reject** (put it back) and **Keep** (take
it out of the review; the checkpoint stays) per file, and **Reject all** /
**Keep all** over the lot. The panel's bar shows **Review N files** while
anything is pending, and tapping it opens the tab; so do `Ctrl` `Shift` `R`,
`☰` → **Review agent changes** and the palette.

**Told when it needs you.** When a turn finishes, or the agent asks
permission or a question, while the panel is hidden — another dock showing,
or the app in the background — you are told: a toast while the app is on
screen, an Android notification (channel *Agent*) otherwise, and tapping the
notification brings the app back with the panel open. **Settings** →
*Agent* → *Notify when the agent is waiting* turns it off; the key is Zed's
`agent.notify_when_agent_waiting` (`primary_screen` and `all_screens` both
mean on — a phone has one screen — and `never` means off).

**Context servers.** MCP servers in `context_servers` — Zed's key and Zed's
shape — are handed to the agent when a thread starts (`session/new`'s
`mcpServers`), and the agent runs or connects to them itself; the editor
starts nothing. A `command` entry runs in the userland over stdio, a `url`
entry is an HTTP server and is sent only to an agent that says it takes
those. **Settings** → *Context Servers* adds, edits and removes them (name,
command or URL, arguments), or write the file:

```jsonc
"context_servers": {
  "filesystem": { "command": "npx", "args": ["-y", "@modelcontextprotocol/server-filesystem", "."] },
  "docs": { "url": "https://example.com/mcp" }
}
```

**Attaching a picture.** The `+` at the start of the controls row opens the
system photo picker; what you choose rides the message as an image. It is
shown above the box with its size and an `✕` to take it back off again, and a
picture on its own is a message — you do not have to type anything with it.
Large images are shrunk before sending, because the whole prompt travels down
one pipe to the agent.

**The `+` appears only for an agent that reads images.** Whether it can is
the agent's own answer, given when it starts, so an agent that never claimed
it is not offered a button that would produce something it cannot see.
In the row under the message box sit the agent's session controls, straight
off the wire: its mode, and every config option it advertises (model, effort,
toggles). A pick-one option is a chip that drops its choices; a yes/no one is
a switch. What the turn has cost, when the agent reports it, sits at the
start of the same row.

| Shortcut | Action |
|---|---|
| `Ctrl` `Alt` `A` | Show/hide the agent panel |
| `Ctrl` `N` | Start a new thread — while the composer has the keyboard (Zed's `agent::NewThread`; elsewhere it is *New file*) |
| `Shift` `Alt` `A` | Allow the permission prompt in front of you, once (`agent::AllowOnce`; composer focused) |
| `Shift` `Alt` `Q` | Allow it always (`agent::AllowAlways`; composer focused) |
| `Shift` `Alt` `X` | Reject it, once (`agent::RejectOnce`; composer focused) |
| `Ctrl` `Shift` `R` | Open the **Review changes** tab (`agent::OpenAgentDiff`) |
| `Ctrl` `Shift` `.` | Add the editor's selection to the thread (`agent::AddSelectionToThread`) |
| `Enter` | Send the message |
| `Shift` `Enter` | Start a new line instead |
| `Ctrl` `;` | Attach an image (when the agent reads them) |
| `Tab` | Take the first suggestion while the `/` or `@` strip is up |
| `Esc` | Put the suggestion strip away; with none up, stop the agent mid-turn |

The permission chords answer the *first* waiting prompt with the option of
that kind; a prompt the agent offered no such option on is left alone, and
the panel says so. `agent::KeepAll` and `agent::RejectAll` are in the
palette without a chord — the review tab's own two buttons are the route.

Above the composer sits a strip with whatever needs you: the agent's plan
(tap to unfold), anything queued, a turn that failed and what to do about it,
and a **Waiting for you** line — with a **Show** that scrolls to it — whenever
a permission prompt or a question has scrolled out of sight.

On a phone `Enter` inserts a newline and the send button — the paper plane at
the end of the controls row — is how you send: a soft keyboard's Enter arrives
as text rather than as a keystroke, so it cannot mean two things at once.

**Typing while it works queues, it does not interrupt.** The button becomes
**Queue** while the agent is busy and there is something to send; queued
messages wait above the composer, in order, with a ✕ to take one back, and go
out one at a time as each turn ends. Stopping the agent is the separate
**Stop** button — a follow-up should never throw away the work in progress.

**The panel does not take the keyboard the way the terminal does.** Every
workspace chord keeps working while the composer has focus — it is a text box
in a dock, like the git panel's commit message — so `Ctrl` `S`, `Ctrl` `P` and
the rest still reach the editor. The reverse also holds: `Ctrl` `Alt` `A` is an
Alt chord, so while the *terminal* has focus it belongs to the shell, and the
`☰` menu or the command palette is the way to the panel from there.

**Nothing the agent writes lands without a decision.** When it asks to change a
file the turn stops, the whole diff appears in the conversation — never
truncated while you are being asked about it — and the agent's own choices sit
underneath it as a full-width list, in the order it offered them. The change
and the choice are one screen. Diffs are unified, never side by side.

**Signing in.** When an agent wants signing in to, the panel offers whatever
methods *it* advertised. Most are a button the agent handles itself; one kind
is not — a terminal sign-in opens a terminal running the agent's own command
with its login arguments, so you can answer its prompts. Finish there, then
start a new thread.

**The agent can ask you things.** Not everything an agent needs is a
yes-or-no about a file: it may want an API token, a choice between branches,
or for you to sign in on a web page and come back. Those arrive as a card at
the end of the conversation with the fields it asked for — text, numbers,
switches, pick-one and pick-many — and **Send** or **Decline** underneath.
Required fields are marked `*` and Send waits for them. A sign-in card stays
up after you say you have done it: the agent is watching for the sign-in and
takes the card away itself once it sees it.

**Commands the agent runs are on screen.** An agent that wants to run
`cargo test` asks the editor to run it rather than shelling out invisibly, so
the command line, what it printed and how it exited appear on the tool call —
tap the call to unfold it, and the output scrolls sideways for long lines. The
command runs in the Linux userland with the project as its working directory,
which it cannot leave. Only its tail is shown; a command that floods keeps its
last megabyte and says so.

**Agent-agnostic, like the protocol.** ACP is a standard, so the panel names
no agent of its own and installs nothing: every agent comes from
`agent_servers` in settings.json — the same key Zed uses — and the picker
offers exactly that list. Add, edit and remove entries from **Settings** →
*External Agents* (name, command, arguments), or write the file yourself:

```jsonc
"agent_servers": {
  "My agent": { "command": "my-agent", "args": ["--acp"], "env": {} }
}
```

The command runs inside the Linux userland, with the environment a login
shell would have — so it is anything you can start from the terminal by
typing its name (`~/.local/bin` and whatever your profile adds to PATH
included), or an absolute path in the guest, that speaks the Agent Client
Protocol on stdin and stdout. Putting it there is yours to do, in the
terminal. settings.json opens as an ordinary editor tab from `☰` → **Edit
settings.json**, the palette's `zed: open settings file`, or the link at the
bottom of the Settings screen; saving it applies it. An edit reaches the
picker as soon as you save; a conversation that is already running keeps the
command it started with until you press **New**.

The panel runs the agent inside the Linux userland, so it is absent from the
Play edition entirely.

## Find in file

`Ctrl` `F` opens a bar above the editor. Every match in the file is
highlighted and the current one is picked out; the count is honest about a
file with more matches than the engine will hand back at once. The
magnifier in the toolbar is the same bar for a finger.

The query starts as what you had selected — or, with nothing selected, the
word under the caret — exactly as Zed seeds its search, and it starts
selected, so typing replaces it. A selection that spans lines seeds nothing.
Pressing `Ctrl` `F` while the bar already has the keyboard keeps the query
and only selects it.

| Shortcut | Action |
|---|---|
| `Enter` / `F3` | Next match |
| `Shift` `Enter` / `Shift` `F3` | Previous match |
| `Alt` `Enter`, or `∗` | Put a cursor on every match and go back to the editor |
| `Ctrl` `H`, or `⇄` | Show or hide the replace row |
| `Esc` | Close the bar and clear the highlights |
| `Aa` `ab` `.*` | Match case / whole word / regular expression |
| `Alt` `C` / `Alt` `W` / `Alt` `R` | The same three, from the keyboard |

A regular expression that does not compile yet — `[`, halfway through
typing — outlines the field rather than clearing the file's highlights and
claiming there are no results.

### Replace

`Ctrl` `H` opens the bar with its replace row showing — Zed's
`buffer_search::DeployReplace` — and, when the query was seeded from a
selection, puts the caret straight into the replacement field. With the
bar already open, `Ctrl` `H` toggles the row; `Ctrl` `F` still opens
find-only, as in Zed.

| Shortcut (in the replacement field) | Action |
|---|---|
| `Enter`, or `↦` | Replace the current match and step to the next |
| `Ctrl` `Enter`, or `⇉` | Replace every match — one undo step |

The case, whole-word and regex toggles apply to the replacement exactly as
they do to the search. With regex on, `$1`, `$name` and `${name}` in the
replacement stand for the pattern's capture groups (`$$` is a dollar), and
`\n`, `\t` and `\\` become a newline, a tab and a backslash; a literal
query's replacement is used as typed. The match counter updates after every
replace, and `Ctrl` `Z` in the editor takes a replace-all back in one step.
All four are in the command palette too (`search: replace next`,
`search: replace all`, `search: toggle replace`, `search: select all
matches`), greyed while the bar is closed.

## Go to line

`Ctrl` `G` opens a small panel over the top of the editor. Type `42` for a
line, or `42:8` for a line and a column — a comma works as well as the colon,
because a soft keyboard hides the colon behind a modifier and the digit row
already has the comma.

The caret moves **as you type**, so you can watch the file scroll past and
stop where you meant to. A number past the end of the file lands on the last
line rather than being refused.

| Shortcut | Action |
|---|---|
| `Enter`, or `↵` | Keep the caret where it landed |
| `Esc`, or `✕` | Put the caret, the selection and the view back where they were |

Cancelling really does put everything back: the selection you had, every extra
cursor, and the exact scroll position — not just the line number.

`Ctrl` `G` is deliberately not offered while a terminal has the keyboard, where
it is BEL and readline's abort — and while the git panel has it, `Ctrl` `G` is
that panel's chord leader instead, exactly as in Zed (see **Git panel**).

## Outline

There are two ways at the same symbols, exactly as in Zed: a picker you open,
use and dismiss, and a panel that stays beside the editor.

### The picker

`Ctrl` `Shift` `O` opens the outline — every symbol in the file, nested as the
code nests, exactly Zed's `outline::Toggle`. Tapping the **breadcrumbs** above
the editor opens it too, which is Zed's own wiring for them and the touch
route. Type to filter; the caret follows the selected symbol **as you
browse**, the way go-to-line previews.

| Shortcut | Action |
|---|---|
| `↑` / `↓` | Browse — the editor follows |
| `Enter`, or a tap | Keep the caret on the symbol |
| `Esc` | Put the caret, the selection and the view back where they were |

### The panel

`Ctrl` `Shift` `B` shows or hides the **outline panel** — Zed's
`outline_panel::ToggleFocus`, on Zed's own chord. It is the same tree as the
picker, docked: expandable, with a filter box at the top, and it **follows the
caret** — scroll through a file and the symbol you are in is selected, with
every fold above it opened (Zed's `auto_reveal_entries`). Pressing the chord
again with the panel already up puts the keyboard back in its filter, which is
what `ToggleFocus` means. Its status-bar button (the `#`) is the touch route,
and a tap on a row is the touch twin of `Enter`.

Unlike the picker, nothing here is a preview: every jump is one you keep, and
the panel's folds are yours until you change them.

| Shortcut | Action |
|---|---|
| `Ctrl` `Shift` `B` | Show/hide the panel, or take its keyboard back |
| `↑` / `↓` | Move the selection |
| `→` / `←` | Expand / collapse the selected symbol |
| `Enter`, or a tap | Jump to the symbol |
| `Esc` | Close the panel |

Which side it docks on is **Settings → Panels → Outline panel**, or
`"outline_panel": { "dock": "left" }` in settings.json; `"hidden"` switches it
off, button, chord and all. It needs a text file open — an outline of a
picture or a diff is an outline of nothing — so its button appears with one.

## Project symbols

`Ctrl` `T` opens the project symbols picker — Zed's `project_symbols::Toggle`:
what you type is sent as a `workspace/symbol` query to **every running
language server** of the project, and the answers are ranked once more by
how well they match, best first. Each row shows the symbol with its
container in front of it, and the file and line under it. The palette's
**project_symbols: Toggle**, `☰` → **Project symbols…** and the `#` button
in the editor's toolbar are the touch routes. Nothing is listed until a
server is running; the status bar says which ones are not.

| Shortcut | Action |
|---|---|
| `↑` / `↓` | Move through the list |
| `Enter`, or a tap | Open the file with the caret on the symbol |
| `Esc` | Close the picker |

## Preview

`Ctrl` `Shift` `M` previews the open file — a `.md` rendered, a `.svg` drawn,
or a `.csv` / `.tsv` laid out as a table. Zed offers the same three, each from
one 👁 button in its toolbar (`quick_action_bar/preview.rs`,
`tabular_data_preview`), and so does this: the eye appears at the right of the
toolbar whenever the open file has a preview, and nothing appears when it does
not. Zed's own chord is `Ctrl` `Shift` `V`, which here is paste in the
editor, paste in the project panel and paste in the terminal — a workspace
command may not take a clipboard chord from any of them.
On a wide screen it docks beside the editor and its left edge drags to resize;
on a phone it takes the work area, the way project search and the terminal do.
`✕` in its title bar closes it, which is the route for a finger.

It follows the buffer: type in the editor and the rendering catches up, and
the preview keeps its place on the page while you edit.

It follows the *scroll* too, which is what the `⇅` in its title bar toggles.
With it on, scrolling the editor moves the preview to whichever block owns the
top visible line — Zed's `sync_preview_to_source_index`, which follows its
editor the same way — and tapping a block in the preview puts the caret on the
line that block was read from. Typing never moves the preview; only scrolling
does. The setting behind the toggle is `markdown_preview.scroll_sync` (on by
default), and it also has a row in Settings under **Preview → Follow the
editor**. Turn it off and the two panes scroll independently, as they did
before.

An SVG is text first — Zed keeps it out of its image viewer by name and opens
it in the editor, because whoever opens an icon file is usually editing it — so
the drawing is a preview like the Markdown one. Pinch or `Ctrl` `+` / `Ctrl` `-`
to zoom, drag to pan, double tap or `Ctrl` `0` to put it back. Gradients,
filters, masks and text are named under the drawing rather than drawn wrong.

A picture that is *only* a picture — `.png`, `.jpg`, `.webp` and the rest —
opens as one instead, with no buffer behind it: nothing to save, nothing to be
dirty, and `Ctrl` `S` and `Ctrl` `F` are refused on it rather than doing
nothing quietly.

| Shortcut | Action |
|---|---|
| `PageUp` / `PageDown` | A screenful |
| `↑` / `↓` | A few lines |
| `Ctrl` `Home` / `Ctrl` `End` | Top / bottom |
| `Esc`, or `✕` | Close it |

The scrolling keys apply once the preview has the focus — click or tap it
first; that is deliberate, so opening the preview never takes the keyboard away
from the file you are writing.

What it renders is what a README uses: headings, **bold**, *italic*,
`code`, ~~strikethrough~~, links (inline, reference and bare), nested and
ordered lists, task lists, block quotes and GitHub's `> [!NOTE]` alerts,
tables, horizontal rules and fenced code blocks — **coloured by the same
tree-sitter grammars that colour the editor**, so a Rust fence in a README
looks like Rust.

**Images inside the project are drawn**: `![shot](docs/shot.png)` and
`![logo](assets/logo.svg)` appear at their own size, shrunk to fit the panel
and never taller than about a screenful; a row of badges stays a row and
scrolls sideways if there are more of them than there is width. A picture is
resolved against the previewed file's own directory and clamped inside the
project. Anything with a scheme — `https://`, `data:`, `file:` — stays
`[image: alt text]`, because drawing it would mean fetching it, and this editor
makes no network requests you did not ask for. So does an image that is missing,
undecodable, or of a format Android has no decoder for.

**```mermaid fences are drawn.** There is no mermaid.js here and no WebView to
run it in, so the common subset is parsed and drawn in Compose:

- `flowchart` and `graph` in all five directions (`TD`, `TB`, `BT`, `LR`,
  `RL`); the node shapes `A`, `A[box]`, `A(round)`, `A([stadium])`,
  `A[[subroutine]]`, `A[(cylinder)]`, `A((circle))`, `A{diamond}`,
  `A{{hexagon}}`, `A>flag]`; the links `-->`, `---`, `-.->`, `-.-`, `==>`,
  `===`, `--o`, `--x`, with labels written either `A -->|text| B` or
  `A -- text --> B`; chains (`A --> B --> C`) and `;`-separated statements.
- `sequenceDiagram`: `participant` / `actor`, with or without `as`, and the
  messages `->`, `-->`, `->>`, `-->>`, `-x`, `--x`, `-)`, `--)`.

Everything else is named rather than drawn wrong. `subgraph`, `classDef`,
`style`, `click`, notes, loops and `alt` blocks are skipped and listed under
the diagram as *not drawn*; any other diagram type — `gantt`, `pie`,
`stateDiagram` — becomes a card naming the type with its source underneath.
The layout is layered by longest path with straight edges, not dagre, so a
large graph will be readable rather than beautiful.

**Math is kept, not typeset.** `$…$` and `$$…$$` are recognised, so
`$a_i * b_j$` no longer comes out italic with its underscores eaten; inline
math is set in the buffer font, and a `$$…$$` block gets its own centred box
with the TeX verbatim. Nothing here renders TeX — there is no engine on the
device small enough to be worth it — and `$5 and $10` is still money, because
a `$` followed by a space or closed after one opens nothing.

There is **no WebView** behind any of this — it is drawn in Compose with the
colours of your Zed theme, so it changes when the theme does.

A link to another file in the project opens that file in a tab. A `#anchor`
link scrolls the preview to that heading, using GitHub's own slug rules. A
link to `https://…` **asks first**, showing the address in full: a README is
somebody else's document, and leaving the editor for one of its links should
be your decision with the destination in front of you.

### Tables — `.csv`, `.tsv`

A delimited file gets Zed's `tabular_data_preview`: the first row as a header,
one column per field, widths from the content, and scrolling in both
directions rather than wrapping. The title bar says how many rows and columns
there are. Quoting is RFC 4180's — a comma inside `"…"` is content, `""` is
one quote, and a newline inside quotes keeps the row together — so an export
with an address in it does not shear every column after it. Tapping a row puts
the editor's caret on the line that row started on. Files past 20 000 rows are
shown as far as that and say so; the editor still has all of it.

## Pictures, sound and video

A picture opens fitted to the pane and never larger than life, which is how
Zed's image viewer opens one. Zed's own chords move it from there — they are
the `ImageViewer` context in its keymap, and they sit on the same keys as the
editor's font size and the first tab, winning over both *only while a picture
is the open tab*; the moment a text file is, `Ctrl` `=` grows the code again.
Under the picture: its width and height in pixels, its size on disk and its
format, the line Zed's status bar shows for one.

| Shortcut | Action |
|---|---|
| `Ctrl` `=` / `Ctrl` `+` | Zoom in |
| `Ctrl` `-` | Zoom out |
| `Ctrl` `0` | Reset zoom to 100% |
| `Ctrl` `1` | Zoom to actual size (also 100%) |
| `Ctrl` `Shift` `0` | Fit the picture to the pane |

Each has a button in the row under the picture (`−`, `+`, `1:1`, `Fit`), and
the pinch and the drag still work on top of them. The five are in the palette
as `image viewer: zoom in` and so on, greyed out while the open tab is not a
picture.

Sound and video play — `.mp3`, `.flac`, `.ogg`, `.wav`, `.mp4`, `.mkv`,
`.webm` and the rest — with a play/pause button, a seek bar and the elapsed
and total time beside it. Video is drawn at its own aspect ratio, as large as
the pane allows. Under the player: the dimensions (for video), the running
time and the size on disk. Opening a file never starts it; playback pauses
when the app leaves the screen and stops when the tab is closed or another
tab takes its place. The keys are every player's, once the pane has the
keyboard — it takes it when the file opens, and a tap gives it back:

| Shortcut | Action |
|---|---|
| `Space` | Play / pause |
| `←` / `→` | Back / forward five seconds |
| `Home` | Back to the start |

The `▶` / `❚❚` button is the finger's play and pause, `⇤` its restart, and the
seek bar drags. A file Android's decoders refuse says so, with **Open with…**
and **Share…** under the sentence to hand it to an app that can.

## Search all files

`Ctrl` `Shift` `F` opens project search. On a wide screen it docks beside
the editor and its left edge drags to resize; on a phone it takes the work
area, the way the terminal does. **Search all files…** in the `☰` menu
opens it too — which is the route from a terminal, where the shell keeps
`Ctrl` `Shift` `F` for itself.

Results arrive while the search runs: files appear as they are found, and
the line under the query says how far the walk has got — `12 results in 3
files · searched 480 of 1200` — with a progress bar that leaves when the
search finishes. A project still being scanned says so and waits, rather
than answering "no results" over half a repository.

| Shortcut | Action |
|---|---|
| `↑` `↓` | Move through files and matches |
| `PageUp` / `PageDown` | Move ten rows |
| `Enter` | Open the selected match, or fold the selected file |
| `Alt` `Enter` | Open every result as an editable multibuffer |
| `Esc` | Close the panel and stop the search |
| `Aa` `ab` `.*` | Match case / whole word / regular expression |
| `Alt` `C` / `Alt` `W` / `Alt` `R` | The same three, from the keyboard |
| `Ctrl` `Shift` `H`, or `⇄` | Show or hide the replace row |
| `Ctrl` `Alt` `Enter`, or `⇉` | Replace every match in every file found |
| `⋯` | Show the include and exclude patterns |
| `⊘` | Also search files git ignores |

The query keeps the caret while the arrows walk the results, so you can
keep typing without clicking back into the field. Clicking a file's row folds
its matches away.

What a match opens depends on the layout, because the two screens want
different things. On a **wide** one it opens the results as a
[multibuffer](#multibuffers) with the cursor on that hit — every result in one
editable document, which is what Zed's project search is. On a **phone** it
opens the file with the cursor on the hit, because a grouped, collapsible list
beats a long document there. The **Multibuffer** chip beside the result count,
and `Alt` `Enter`, open it from either.

Include and exclude take comma-separated globs — `src/**/*.rs`,
`vendor/*, *.lock` — and a pattern that isn't a valid glob outlines both
fields rather than searching the whole project instead.

Files the search cannot read honestly are counted but skipped: anything
over 4 MiB, anything holding a NUL byte, and anything that is not UTF-8.
The result count is what the engine found, so a search that hit its own
limit says `limit reached` rather than quietly showing you less.

### Replace across the project

`Ctrl` `Shift` `H` — Zed's chord on its project search bar — or the `⇄`
button shows a **Replace in project…** field under the query. `⇉` (or
`Ctrl` `Alt` `Enter` from the field) replaces every match in every file the
search found, once the search has finished: the button stays grey until
then. The same toggles and the same `$1` / `\n` rules as replacing in a file
apply.

Files you have open are edited in their buffers — the editor updates, the
tab goes dirty, and `Ctrl` `Z` in each takes its replacements back in one
step. Files you do not have open are rewritten on disk, atomically, and that
has no undo: read the results list before pressing it. The status line then
says what happened — `Replaced 12 matches in 3 files` — and names any file
that could not be written. There is no per-match replace in the list yet;
open the match and use the file's own bar for one hit at a time.

## Multibuffers

A **multibuffer** is Zed's signature surface: pieces of several files in one
editable document, each under a header naming the file and the lines it shows.
Search results, find-all-references and the project's problems all open as one,
and **you can type in it** — an edit goes to the file that row came from, so
undo, the language server and the dirty dot all happen per file, not in some
scratch copy.

Each excerpt carries two lines of context above and below, which is Zed's
figure; two hits close enough for their context to overlap are shown as one
continuous stretch of the file rather than twice.

| Shortcut | Action |
|---|---|
| `Ctrl` `S` | Save **every** file in the multibuffer that has edits |
| `Alt` `Enter` | Leave for the file the cursor's excerpt came from, at the same line |
| `Ctrl` `Z` / `Ctrl` `Y` | Undo / redo — in the file the last edit went to |

The header of the excerpt you are looking at sticks to the top of the pane;
**tapping it** is the touch route out to that file, the same as `Alt` `Enter`.
The header rows in the text itself cannot be edited: they belong to no file, so
an edit that reaches one is refused rather than written somewhere.

Editing a file in its own tab while a multibuffer shows it is fine — the
composition follows along, and an excerpt keeps pointing at the same code even
when lines are inserted above it.

Three ways in:

- **Search all files** — a hit on a wide screen opens the multibuffer with the
  cursor on it, which is what Zed's project search *is*. On a phone a hit still
  opens its file, because the grouped list is the better surface there; the
  **Multibuffer** chip under the query, and `Alt` `Enter`, reach it either way.
- **Find all references** (`Shift` `F12`) — **Open all in a multibuffer** at
  the foot of the list, or `Alt` `Enter` while it is showing.
- **Diagnostics** — the `❐` button in the tab's toolbar, or `Alt` `Enter`
  while the list has the keyboard.

Closing a multibuffer releases the files it opened for itself. One you had a
tab on, or one left with unsaved edits, stays open.

## Editor

A chord with `Ctrl` or `Alt` in it is the editor's before it is the soft
keyboard's: it is dispatched before the keyboard app sees the key, so
Gboard's own ideas about `Ctrl` `Backspace` (or `Ctrl` `Z`) never reach
your text. Only a chord no binding claims goes on to the keyboard.

### Moving around

| Shortcut | Action |
|---|---|
| `Home` / `End` | Start of the line — first press stops at the indent — / end of it |
| `Ctrl` `Home` / `Ctrl` `End` | Start / end of the file |
| `PageUp` / `PageDown` | A screenful, measured from what is actually on screen |
| `Ctrl` `←` / `Ctrl` `→` | One word (or one run of punctuation) at a time |
| `Shift` + any of the above | Select instead of jump |

Every one of them moves all your cursors, not just the first.


| Shortcut | Action |
|---|---|
| `Ctrl` `Z` | Undo |
| `Ctrl` `Shift` `Z` / `Ctrl` `Y` | Redo |
| `Ctrl` `A` | Select all |
| `Ctrl` `C` / `X` / `V` | Copy / cut / paste |
| `←` `→` `↑` `↓` | Move every cursor |
| `Shift` + arrows | Extend the selection |
| `Backspace` | Delete backwards (joins lines at column 0) |
| `Delete` | Delete forwards — the character after the cursor, or the selection; at the end of a line, the line break |
| `Ctrl` `Backspace` / `Alt` `Backspace` | Delete back to the start of the word |
| `Ctrl` `Delete` | Delete forward to the end of the word |
| `Enter` | New line, keeping the indent |
| `Ctrl` `Enter` | Open a new line below this one and go to it, indented |
| `Ctrl` `Shift` `Enter` | The same, above |
| `Tab` | With nothing selected, insert one indent level; with a selection, indent every selected line |
| `Shift` `Tab` | Outdent the selected lines (or the cursor's line) by one level |
| `Ctrl` `]` / `Ctrl` `[` | Indent / outdent the selected lines (or the cursor's line) |

Undo and redo, copy, cut, paste and select-all are also in the `☰` menu in
the title bar, with their shortcuts listed beside them. The rest of the
table is on the strip above the soft keyboard: `tab`, `outdent`, `del`,
`⌫word`, `word⌦`, `↵above` and `↵below`.

The word deletions follow Zed's rules rather than the arrow keys': a run of
two or more spaces is deleted on its own, so `foo   |` becomes `foo|` and
the word waits for a second press; a bracket or quote stops the deletion,
so in `f(x);|` only the `;` goes; and at the very start or end of a line
only the line break goes. `Alt` `Backspace` is Zed's macOS chord for the
same thing, kept for the Mac keyboards that get paired with tablets.

Indenting moves each line to the *next* tab stop — a line three spaces in
lands at four — and outdenting to the previous one, never taking anything
but whitespace. Tabs or spaces follow the file, as they do for `Enter`.
A selection that ends at column 0 of a line leaves that line alone, as in
Zed. Every one of these is one step in the undo history, however many
lines it touched.

### Folding

The chords are Zed's, from its Linux keymap; the gutter chevron and the `⋯`
chip do the same by touch or mouse. Where a fold starts and ends comes from
the best source there is: the language server's `textDocument/foldingRange`
when the file's server offers one, else the **syntax tree** — the blocks the
grammar's own indent queries know about, as Zed folds by its syntax — and,
for a language with no grammar, the indentation: a line folds away the
deeper-indented block beneath it.

| Shortcut | Action |
|---|---|
| `Ctrl` `Shift` `[` | Fold the innermost block around each cursor |
| `Ctrl` `Shift` `]` | Unfold at each cursor |
| `Ctrl` `K`, then `Ctrl` `0` | Fold every block in the file |
| `Ctrl` `K`, then `Ctrl` `J` | Unfold everything |

Tapping the chevron in the gutter folds that block; tapping the `⋯` chip at
the end of a folded line opens it again. Editing into a fold unfolds it, and
a search hit inside one unfolds its way to the match.

### Problems

The chords are Zed's, from its Linux keymap. A language server has to be
installed and running for there to be anything to go to; the status bar says
which ones are not.

| Shortcut | Action |
|---|---|
| `F8` | Go to the next problem in the file |
| `Shift` `F8` | Go to the previous one |
| `Alt` `Enter` | In the diagnostics tab: open every problem as a multibuffer |

`"diagnostics": {"inline": {"enabled": true}}` writes the message at the end
of the line it belongs to, in the severity's colour — Zed's error lens. Only
the worst problem on a row is drawn; `max_severity` sets how quiet a problem
still earns one, `padding` how far from the code it sits and `min_column`
the column the messages line up at. `editor: toggle inline diagnostics` in
the palette flips it for the open editor without touching settings.json.

The squiggle under a problem is Zed's, in Zed's severity colours, and it fades
while the file has edits the server has not seen yet — a diagnostic describes
the text the server last read, so it dims rather than pretending to have
moved. The gutter carries a mark for every affected row; tapping it goes
there. The status bar counts the project's errors and warnings and shows the
message under the caret.

### What the language server knows

A server has to be installed and running — the status bar says when one is
not, and clicking it offers to install it. The chords are Zed's, from its
Linux keymap, with two exceptions noted under the table.

| Shortcut | Action |
|---|---|
| `Ctrl` `Space` | Suggest completions where the cursor is |
| `Enter` / `Tab` | Accept the selected completion |
| `↑` / `↓` | Move through the list |
| `PageUp` / `PageDown` | Jump to the first / last one |
| `Esc` | Close the list (or the hover card, or the signature help) and leave the text alone |
| `Alt` `Enter` | With the references list showing: open every use as a multibuffer |
| `Ctrl` `K`, then `Ctrl` `I` | Show what the language server knows about the symbol |
| `Ctrl` `I` | Show the signature of the call the cursor is in, the active parameter in bold; again to hide it |
| `F12` | Go to where the symbol under the cursor is defined |
| `Ctrl` `F12` | Go to the definition of the symbol's **type** |
| `Ctrl` `Shift` `F12` | Go to the **implementation** (Zed: `Shift` `F12`) |
| `Shift` `F12` / `Alt` `Shift` `F12` | Find every reference to the symbol |
| `F2` | Rename the symbol everywhere the server knows it |
| `Ctrl` `.` | Show the code actions at the cursor and apply one |
| `Ctrl` `Shift` `I` | Format the file with the language's formatter |
| `Ctrl` `Shift` `;` | Show or hide inlay hints (Zed: `Ctrl` `:`) |

`Shift` `F12` has meant *find references* here since before the keymap did,
as it does in VS Code, so it keeps that meaning, references gets Zed's own
`Alt` `Shift` `F12` as a second spelling, and implementation moves to
`Ctrl` `Shift` `F12`. Go to declaration has no chord in Zed either: it is a
row on the hover card, a key in the row above the soft keyboard, and yours
to bind in keymap.json as `editor::GoToDeclaration`.

A jump with one answer goes there; a jump with several — a trait with many
implementations — opens them as a list under the cursor, and a tap on a row
goes to that one. The same list shows the references.

**Completions.** The list filters as you keep typing and does not ask the
server again unless it said its answer was incomplete. The characters that
open it — `.` and `::` in Rust, say — are the ones the server declares,
not a fixed set. Moving through the list asks the server for the selected
row's documentation (`completionItem/resolve`), which shows in a panel
beside the menu; an accepted row that resolves with extra edits (an
import to add, typically) lands them with the insert. The list opens
**above** the cursor when the soft keyboard would otherwise cover it — the
one placement rule here that is not Zed's, because a desktop editor never
has a keyboard eating the bottom third of the screen.

**Signature help.** Typing `(` or `,` — whichever characters the server
declares as triggers — shows the call's signature with the parameter you are
on in bold and its documentation under it; the popover follows the cursor
along the call and goes when the cursor leaves the line, when `Esc` is
pressed, or when the completion menu opens over it.

**Inlay hints** are off by default, as in Zed. `"inlay_hints": { "enabled":
true }` in settings.json — or the **Inlay hints** rows on the settings
screen, or the chord — turns them on; `show_type_hints`,
`show_parameter_hints` and `show_other_hints` pick which kinds show. They
are drawn dimmed in the text, but they are not text: the cursor, a
selection, a tap and a search all land on the real characters.

**Code actions** that a server describes as a command rather than an edit
are run through `workspace/executeCommand`, and whatever edit the server
then pushes back is applied as the action's own.

**The servers themselves.** Tapping the language-server icon at the left
of the status bar lists every server of the project with its state and,
under each, **Restart**, **Stop** and **Show logs**. The palette has the
same for the active file's language: **editor: Restart Language Server**,
**editor: Stop Language Server** and **dev: Open Language Server Logs**,
which opens a read-only **LSP logs** tab following the last two thousand
lines of the server's stderr, its log messages and the RPC trace. A stopped
server stays stopped until restarted.

By touch: the `suggest` key in the row above the keyboard opens the
completion list and a tap accepts a row; `fix`, `rename`, `refs`, `format`,
`sig`, `type def`, `impl` and `decl` in the same row are the code-action,
rename, references, format, signature-help and go-to chords. A **long
press** on a symbol shows what the server knows about it, with **Go to
definition**, **Go to type definition**, **Go to implementation** and **Go
to declaration** on the card. With a mouse, resting the pointer on a symbol
shows the same card, and `Ctrl` `+ click` goes to the definition.

### Multiple cursors

The bindings are Zed's, from its Linux keymap.

| Shortcut | Action |
|---|---|
| `Ctrl` `D` | Select the word under the cursor, then add a cursor on the next occurrence of it |
| `Ctrl` `Shift` `L` | Put a cursor on every occurrence at once |
| `Shift` `Alt` `↑` / `↓` | Add a cursor above / below (press the other way to take one back) |
| `Ctrl` `Alt` `↑` / `↓` | The same, for keyboards that spend `Shift` `Alt` on a layout switch |
| `Alt` + click | Place a cursor where you click |
| `Esc` | Back to one cursor, then to no selection |

Everything else applies to every cursor at once: typing, `Backspace`,
paste, the line operations below, and comment toggling. A multi-cursor
edit is one step in the undo history, so a single `Ctrl` `Z` takes all of
it back.

### Lines

| Shortcut | Action |
|---|---|
| `Alt` `↑` / `↓` | Move the line (or the selected lines) up or down |
| `Ctrl` `Alt` `Shift` `↑` / `↓` | Duplicate the line above or below |
| `Ctrl` `Shift` `K` | Delete the line |
| `Ctrl` `Shift` `J` | Join the next line onto this one |
| `Ctrl` `L` | Select the whole line, break included |
| `Ctrl` `K`, then `Ctrl` `Q` | Reflow the paragraph or comment block to `preferred_line_length` |
| `Ctrl` `/` | Comment or uncomment, with the token for the file's language |

`Alt` `↑` / `↓` moves lines here because that is what it does in Zed's own
Linux keymap; growing a selection is `Alt` `Shift` `→` / `←`, under
[Selecting by syntax](#selecting-by-syntax) below.

`Ctrl` `K` `Ctrl` `Q` — Zed's `editor::Rewrap`, and `Ctrl` `K` `Q` for the
same thing — reflows the paragraph the cursor sits in, or the lines you
selected. It keeps the indent and the comment marker on every line it makes,
so a long `//` block stays a `//` block; the column is `preferred_line_length`
(80 unless you changed it).

These have no chord in Zed and are palette rows here too — type part of the
name into `Ctrl` `Shift` `P`:

| Command | What it does |
|---|---|
| `editor: sort lines case sensitive` | Sort the selected lines |
| `editor: sort lines case insensitive` | The same, ignoring case |
| `editor: reverse lines` | Turn the selected lines back to front |
| `editor: shuffle lines` | Deal them into a random order |
| `editor: unique lines case sensitive` | Drop repeated lines, keeping the first of each |
| `editor: unique lines case insensitive` | The same, ignoring case |
| `editor: transpose` | Swap the two characters around the cursor |
| `editor: convert to upper case` | `value` → `VALUE` |
| `editor: convert to lower case` | `VALUE` → `value` |
| `editor: convert to title case` | `someValue` → `Some Value` |
| `editor: convert to snake case` | `someValue` → `some_value` |
| `editor: convert to kebab case` | `someValue` → `some-value` |
| `editor: convert to upper camel case` | `some_value` → `SomeValue` |
| `editor: convert to lower camel case` | `some_value` → `someValue` |
| `editor: convert to opposite case` | `fooBAR` → `FOObar` |

The sorts, the filters and the reflow work on the lines you selected; with
nothing selected they work on the cursor's line, which for a sort means
nothing at all — select first. The case conversions work on the selection or,
with nothing selected, on the word the cursor is in, and leave the indent and
any trailing spaces where they were.

### Selecting by syntax

The bindings are Zed's, from its Linux keymap. Both need a language with a
grammar — every language the app highlights has one.

| Shortcut | Action |
|---|---|
| `Alt` `Shift` `→` | Grow the selection to the enclosing syntax node |
| `Alt` `Shift` `←` | Shrink it back, retracing the way it grew |
| `Ctrl` `M` | Jump to the other bracket of the pair around the cursor |
| `Ctrl` `Shift` `\` | The same — Zed's second chord for it |

Growing walks the file's own syntax tree, so from a caret inside an argument
the presses give you the argument, then the call, then the statement, then the
block. Shrinking retraces exactly those steps rather than guessing, and any
other cursor move — a tap, an arrow key, an edit — ends the run.

The bracket pair around the cursor is highlighted while it is there, and
`Ctrl` `M` hops between the two ends of it. Both come from the grammar's own
bracket rules where the language has them, and from counting delimiters where
it does not, so a plain text file still matches its braces.

Both pairs are palette rows too — `editor: select larger syntax node`,
`editor: select smaller syntax node`, `editor: move to enclosing bracket` —
which is how a touch-only device reaches them.

`Ctrl` `/` uses the language's own tokens: `//` in Rust and Go, `#` in
Python, shell and YAML. A language with no line comment but a block one
gets that instead, wrapped around what you selected — `<!-- … -->` in
Markdown, `/* … */` in CSS — and pressing it again takes the delimiters
back off. A diff has neither, and there `Ctrl` `/` does nothing rather
than writing a token the format has no meaning for.

### Brackets, quotes and indentation

These need no binding; they are how typing behaves. Which pairs a
language has, and where, is the language's own business — the rules come
from the same grammar that colours the file.

- Typing an opening bracket or quote brings its partner with it, unless
  what follows the cursor is a word — the closer would land in the middle
  of it. A quote right after a word character stays a plain apostrophe,
  so `don't` types as you'd expect.
- **A quote typed inside a comment or a string stays a lone quote.** The
  editor asks where the cursor is in the file's syntax tree, so this is
  the real answer and not a guess about the characters around it.
- Openers longer than one character work too: `f"` in Python closes as
  one pair, and so do `r#"` in Rust and `/*` in C, Go and Rust.
- Typing the closer when it is already in front of the cursor steps over
  it rather than doubling it — inside a string as much as outside one.
- With text selected, an opening bracket or quote wraps the selection
  instead of replacing it, and the selection survives.
- `Backspace` between the two halves of an empty pair deletes both.
- `Enter` keeps the current line's indent and adds one level where the
  language says a block opens: after a bracket that expects a line of its
  own, after a `:` in Python, a `do` or `then` in a shell script, a key
  with nothing after it in YAML. If the closing bracket was waiting on
  the other side of the cursor it goes down onto a line of its own.

Rust's `<` is the one opener that never closes itself — it starts a
generic far less often than it is a comparison — but it still wraps a
selection, and `Enter` inside `<…>` still indents.

Indent width is the `tab_size` setting. Whether it is tabs or spaces is
read off the file you are in, so an existing file keeps its own style;
only a file with nothing to say falls back to the language (Go indents
with tabs).

### How the editor looks

None of these has a chord in Zed except the first; all of them are settings,
and the four toggles are palette rows so a touch-only device can reach them.

| Shortcut | Action |
|---|---|
| `Ctrl` `;` | Show or hide the line numbers in this editor |

| Command | What it does |
|---|---|
| `editor: toggle line numbers` | The same as `Ctrl` `;` |
| `editor: toggle relative line numbers` | Count from the cursor's row instead of the top of the file |
| `editor: toggle minimap` | Show or hide the map down the right edge |
| `editor: toggle inline diagnostics` | Show or hide the error-lens messages |

Each of the four flips the **open editor** and leaves settings.json alone,
which is what Zed's own toggles do. The defaults live in the file:

| Setting | What it does |
|---|---|
| `show_whitespaces` | `off`, `all`, `selection` (Zed's default), `boundary` or `trailing` — which spaces and tabs get a visible `·` or `→` |
| `show_wrap_guides` | Whether the `wrap_guides` columns are drawn at all (default `true`) |
| `wrap_guides` | Extra columns to mark, beside `preferred_line_length` |
| `soft_wrap: "bounded"` | Wrap at `preferred_line_length` or the pane's width, whichever is narrower |
| `relative_line_numbers` | `disabled` (default), `enabled` or `wrapped` |
| `gutter.line_numbers` | `false` hides the numbers |
| `current_line_highlight` | `none`, `gutter`, `line` or `all` (default) |
| `cursor_shape` | `bar` (default), `block`, `underline` or `hollow` |
| `cursor_blink` | `false` leaves the caret solid |
| `remove_trailing_whitespace_on_save` | Default `true`, as in Zed |
| `ensure_final_newline_on_save` | Default `true`, as in Zed |
| `scrollbar` | `show`, and `cursors` / `git_diff` / `search_results` / `selected_symbol` / `diagnostics` for the marks on the track |
| `minimap` | `show` (`never` by default), `thumb` and `max_width_columns` |

`show_whitespaces`, `show_wrap_guides` and the two save rules are per-language
settings, so `"languages": {"Markdown": {"show_whitespaces": "all"}}` works as
it does in Zed.

The scrollbar carries a mark for every git hunk, search hit, problem and
cursor in the file, in the colours the gutter uses for the same things — a
map of where the interesting rows are without scrolling to find out. The
minimap, when it is on, draws the file as coloured blocks down the right edge
with the visible rows washed over; dragging it scrolls.

The two whitespace-on-save rules run every time you save (not on the delayed
autosave, which Zed skips too), after the formatter and before the write, and
land as one step in the undo history.

### Snippets

A completion that carries a snippet body — most function completions do —
fills its placeholders in rather than dropping them:

| Shortcut | Action |
|---|---|
| `Tab` | Go to the next placeholder |
| `Shift` `Tab` | Back to the previous one |
| `Esc` | Stop filling it in and leave the text as it stands |

Accepting the completion selects the first placeholder; typing replaces it,
and a placeholder that appears more than once gets a cursor on each, so all of
them change together. `$0` is where the cursor rests when the snippet is done,
and reaching it ends the session — the next `Tab` indents as it always did.

Your own snippets go in `snippets/<language>.json` under the app's files
directory, in Zed's format:

```json
{
  "Log": {
    "prefix": "log",
    "body": ["console.log($1);", "$0"],
    "description": "Log to the console"
  }
}
```

`snippets/snippets.json` is the file for every language. They are offered in
the completion list beside the language server's own answers, sorted in with
them.

## Vim mode

Zed's vim mode, as a setting: `"vim_mode": true` in settings.json,
**Settings → Editor → Vim mode**, or **Turn on vim mode** in the `☰` menu
(`workspace::ToggleVimMode` in the palette). Off by default, as in Zed.
While it is on the status bar's left side prints the mode as Zed's
indicator does — `-- NORMAL --`, `-- INSERT --`, `-- VISUAL LINE --` — with
the keys of a half-typed command before it (`2d`, `"a`), and the caret is a
block in normal and visual mode, a bar in insert mode and an underline in
replace mode and while `f`, `t` or `r` waits for its character.

Vim's keys are not keymap bindings — the layer reads them ahead of the
keymap, at the rank Zed gives its `vim_mode == normal` contexts — so a
chord the layer does not use (`Ctrl` `S`, `Ctrl` `Shift` `P`, `Ctrl` `Z`)
still means what it means in every mode, and keymap.json can still move
those. Every edit a Vim command makes is one step in the undo history,
however many lines it touched.

### Modes

| Keys | Action |
|---|---|
| `i` `a` `I` `A` | Insert before / after the cursor, at the first non-blank / the end of the line |
| `o` `O` | Open a line below / above, indented, and insert |
| `R` | Replace mode: typing overwrites, `Backspace` puts the original back |
| `v` `V` `Ctrl` `V` | Visual, visual line, visual block |
| `Esc`, `Ctrl` `[`, `Ctrl` `C` | Back to normal mode, or cancel a half-typed command |
| `gv` | Reselect the last visual selection; `o` in visual mode swaps its ends |
| `I` / `A` in visual block | A cursor on every row of the block, at its left / right edge |

### Motions

Every motion takes a count (`3w`, `5j`) and can follow an operator.

| Keys | Action |
|---|---|
| `h` `j` `k` `l`, arrows | A character or a line; `j`/`k` keep the column they aim for |
| `w` `b` `e` `ge` / `W` `B` `E` `gE` | Word forwards / back / to its end (capitals count a run of punctuation as part of the word) |
| `0` `^` `$` | Start of the line, its first non-blank, its end |
| `gg` `G` `42G` | First line, last line, line 42 |
| `{` `}` | Paragraph back / forward |
| `%` | The bracket matching the one under (or after) the cursor; `50%` is halfway down the file |
| `f` `F` `t` `T` `;` `,` | To (`f`) or just before (`t`) a character on the line, forwards or back, and repeat either way |
| `H` `M` `L` | Top, middle, bottom of the screen |
| `Ctrl` `D` / `Ctrl` `U`, `Ctrl` `F` / `Ctrl` `B`, `Ctrl` `E` / `Ctrl` `Y` | Half a screen, a screen, a line of scrolling |
| `zz` `zt` `zb` | Scroll the cursor's line to the middle, top, bottom |
| `ma` … `mz`, `'a`, `` `a `` | Set a mark; jump to its line / its exact spot. `''` is where the last jump left from |
| `Ctrl` `O` / `Ctrl` `I` | Back and forward through the navigation history |
| `gd` | Go to definition, as Zed binds it |
| `]d` / `[d` | Next / previous diagnostic |

### Operators and text objects

| Keys | Action |
|---|---|
| `d` `c` `y` | Delete, change (delete and insert), yank — with a motion or a text object: `dw`, `ci(`, `yap` |
| `>` `<` `=` | Indent, outdent, re-indent — `>j`, `<ip`, `=G` |
| `gu` `gU` `g~` | Lowercase, uppercase, toggle case |
| `gc` | Toggle the comment, as Zed binds it — `gcc` for the line, `gcap` for the paragraph |
| `dd` `cc` `yy` `>>` `<<` `==` `gcc` | The same over whole lines, with a count: `3dd` |
| `D` `C` `Y` | To the end of the line; `Y` is the whole line |
| `x` `X` `s` `S` | Delete the character under / before the cursor; delete it and insert; replace the line |
| `r` | Replace the character under the cursor with the next one typed (`3rx`) |
| `~` | Toggle the case under the cursor and step on |
| `J` `gJ` | Join with the next line, with a space / without |
| `p` `P` | Put after / before the cursor; lines go on a line of their own |
| `u`, `Ctrl` `R` | Undo, redo |
| `.` | Repeat the last change, including the text an insert typed; `3.` repeats it three times |
| `Ctrl` `A` / `Ctrl` `X` | Add to / subtract from the number under the cursor |

Text objects, after `d`, `c`, `y` or in visual mode: `iw` `aw` `iW` `aW` (a
word), `i(` `a(` `i[` `a[` `i{` `a{` `i<` `a<` (a bracket pair — `ib` and
`iB` are the round and curly ones), `i"` `a"` `i'` `a'` `` i` `` `` a` ``
(a string), `it` `at` (an HTML tag), `ip` `ap` (a paragraph).

### Registers

`"a` before a yank, delete or put names a register: `"ayy`, `"ap`; a
capital (`"Ayy`) appends. `"0` is the last yank, `"1`–`"9` the last
deletes, `"-` the last delete inside a line, `"_` throws away. `"+` and
`"*` are the system clipboard, and the `vim.use_system_clipboard` setting —
**Settings → Editor → Vim and the clipboard** — says whether the unnamed
register is too: `always` (Zed's default: every yank and delete goes to the
clipboard, and `p` pastes what another app copied), `on_yank` (yanks only)
or `never`.

### Search and the command line

`/` and `?` open Vim's line at the bottom of the pane; `Enter` searches
forwards or back with the same engine search the find bar uses, every
match highlighted, wrapping at the ends with Vim's message. `n` / `N`
repeat it, `*` / `#` search for the word under the cursor, and an operator
takes a search as its motion (`d/end`). A pattern with a capital is
case-sensitive; patterns are regexes in Zed's syntax.

`:` opens the command line, with `'<,'>` filled in from a visual selection
and `.,.+N` from a count. `Esc` closes it; `Backspace` on an empty line
does too.

| Command | Action |
|---|---|
| `:w`, `:q`, `:q!`, `:wq`, `:x`, `ZZ`, `ZQ` | Save; close the tab (asking about unsaved changes, or `!` to drop them); save and close |
| `:e path` | Open a file by its project-relative path |
| `:42`, `:$`, `:'a` | Go to a line |
| `:[range]s/old/new/[g][i]` | Substitute on the cursor's line, `%` for the file, `'<,'>` for the selection; `g` every match on a line, `i` ignoring case; `$1` for a group |
| `:[range]d`, `:[range]y`, `:[range]j`, `:[range]>`, `:[range]<` | Delete, yank, join, indent, outdent the rows |
| `:noh` | Clear the search highlights |
| `:cn` / `:cp` | Next / previous diagnostic |
| `:sp`, `:vsp`, `Ctrl` `W` … | Split panes are not available here; the command line says so |

### By touch

The row above the soft keyboard gains a `:` key while vim mode is on, and
its `esc` key leaves insert mode — the one thing a soft keyboard cannot
otherwise say. Tapping the mode in the status bar is Escape too. In every
mode but insert the soft keyboard types commands, not text (its
autocorrect and suggestions are switched off for that), and a selection
made by long-press or drag is a visual selection: drag over a word and tap
`d`.

`vim.default_mode` — **Settings → Editor → Vim starts in** — chooses the
mode each buffer opens in.

## Terminal

The shell gets the keyboard. Every plain `Ctrl`+letter goes straight to
it — `Ctrl` `C` interrupts, `Ctrl` `D` ends input, `Ctrl` `R` searches
history — and so do `Esc`, `Alt` chords and the function keys, because vi
and htop need them. The workspace keeps only these:

| Shortcut | Action |
|---|---|
| ``Ctrl` ` `` | Hide the terminal and return to the editor |
| ``Ctrl` `Shift` ` `` | Open another terminal |
| `Ctrl` `Tab` / `Ctrl` `Shift` `Tab` | Next / previous terminal |
| `Ctrl` `PageDown` / `PageUp` | Next / previous editor tab |
| `Ctrl` `Shift` `W` | Close this terminal |
| `Ctrl` `Shift` `F` | Find in the scrollback (`Enter` / `Shift` `Enter` next and previous match, `Esc` back to the shell) |
| `Ctrl` `Shift` `C` / `Ctrl` `Shift` `V` | Copy the selection / paste (`Ctrl` `C` and `Ctrl` `V` are control codes) |
| `Ctrl` `Shift` `L` | Clear the screen and the scrollback |
| `Ctrl` `Shift` `N` | Rename this terminal |
| `Shift` `Home` / `Shift` `End` | Scroll to the top / bottom of the scrollback |
| `Ctrl` `Shift` `S` / `O` / `E` / `,` | Save, projects, project panel, settings |
| `Ctrl` `Shift` `B` | Show/hide the outline panel |
| `Ctrl` `Shift` `G` | Show/hide the git panel |
| `Ctrl` `Shift` `P` | Open the command palette |

`Ctrl` `Shift` `V` is paste here and nothing else — and everywhere else, which
is why the preview is on `Ctrl` `Shift` `M` — and `Ctrl` `G` stays
BEL. Both are reachable from the `☰` menu, which is the route out of a focused
terminal.

`Ctrl` `Shift` `P` means the command palette here as everywhere else,
which is why *find file* is the one command with no shifted twin: from a
shell it is `Ctrl` `Shift` `P` and then "file". `F1` is not taken, because
it belongs to whatever is running in the terminal.

`Ctrl` `Shift` `F` is the same action Zed deploys over a terminal —
buffer search, in the Terminal context — so the palette's *buffer search:
deploy* opens it too while a shell has the keyboard. The bar sits above the
screen: a query, an `Aa` case toggle, `‹` `›` and a match counter over the
whole scrollback. The current match is scrolled to and highlighted in the
selection colour. The `⌕` button in the terminal's tab bar and **Find…** in
its `⋮` and right-click menus are the touch routes.

**Paths and URLs in the output are links.** `Ctrl`+click a `src/main.rs:12:5`
— rustc's `--> src/main.rs:12:5`, Python's `File "x.py", line 41`, MSBuild's
`a.cs(22,5)`, a `file://` URL, a bare `README.md` — and the file opens in the
editor at that line and column, resolved against the shell's *current*
directory (read from `/proc`), not where the session started. A path outside
the project is reported in a toast rather than opened: the editor works on
project paths. An `http://` or `https://` URL opens in the browser. By touch,
long-press the path; with the soft keyboard, tap `ctrl` in the extra-key row
and then the path. Zed underlines the target while the modifier is held; the
vendored renderer cannot decorate a cell cheaply, so here it does not.

Sessions start in the project directory — or the open file's, or home, by
`terminal.working_directory` in settings.json, with `terminal.env` added to
the shell's environment and `terminal.max_scroll_history_lines` rows of
scrollback (Zed's names, Zed's defaults) — and keep running while the
terminal is hidden. They close when you switch projects. In the `full`
edition the shell runs inside Debian once you install it — see
[USERLAND.md](USERLAND.md).

## Tasks

Zed's tasks: shell commands with a label, run in a terminal tab — see
[TASKS.md](TASKS.md) for the file format and the variables.

| Shortcut | Action |
|---|---|
| `Alt` `Shift` `T` | Open the task picker (`task: spawn`): every task for the project and the file, recently run first; type a command and `Enter` to run it as a oneshot, `Tab` to edit the selected task's command line first |
| `Alt` `T` / `Ctrl` `Alt` `R` | Run the last task again (`task: rerun`) |
| — | Run the runnable at or nearest the caret (`editor: spawn nearest task` in the palette — unbound in Zed's Linux keymap and here) |
| — | Edit `tasks.json` / the project's `.zed/tasks.json` (`zed: open tasks` / `zed: open project tasks` in the palette, or the `☰` menu) |

The `▶` in the gutter is a runnable — a `#[test]`, a `fn main`, a
`test_` function, a `package.json` script — and tapping or clicking it
runs the task bound to that row, or opens the picker narrowed to the
row's tasks when there are several. The `▶` in the terminal's tab bar
opens the full picker; **Run task…** and **Rerun last task** are in the
`☰` menu. Inside the picker, `↑` `↓` (or `Ctrl` `N` / `Ctrl` `P`)
move, `Enter` runs, `Esc` closes, and the `✕` on a previously run row
forgets it.

## Mouse

- The editor shows a text cursor; tabs, tree rows and actions show a
  hand cursor. Dialog fields — the project name, the clone URL — show a
  text cursor too, so a mouse tells you where it can type.
- Click a file in the project panel to open it, a directory to expand
  or collapse it. `Ctrl`-click adds a row to the selection and `Shift`-click
  takes the range; long-press-drag moves the selection into another folder.
  With several folders open, each folder's own row expands and collapses
  everything in it.
- Click a tab to switch to it, its `✕` to close it, and its unsaved-changes
  dot to save — the dot is a save button, which matters because the soft
  keyboard covers the status bar while you type. Double-click a preview tab
  (the italic one) to keep it, and long-press-drag any tab to reorder it.
- At the left end of the tab strip, `←` and `→` walk the navigation
  history — every tab switch remembers where you were, going back returns
  there (reopening the file if you closed it), and going forward replays the
  jump. Greyed when there is nowhere to go. The `+` at the strip's right end
  creates a new file.
- In the status bar: the panel buttons sit with their docks — the left dock's
  at the left end, the right dock's and the terminal's at the right.
- In the terminal: the wheel scrolls the scrollback, drag selects,
  `Ctrl`+click follows a path or a URL in the output, and the bar between
  the editor and the terminal drags to resize the dock.
- In project search: the wheel scrolls the results, a row lights up under
  the pointer, and the panel's left edge drags to make it wider.
- In the toolbar: the 👁 button shows a hand cursor and opens the preview of
  the file that is open — the same button for Markdown, SVG and a `.csv`, as
  in Zed.
- In the Markdown preview: links show a hand cursor and light up under the
  pointer, the wheel scrolls the page, clicking a block puts the caret on its
  source line, the `⇅` in the title bar turns following on and off, and the
  panel's left edge drags to make it wider. In the SVG preview the wheel zooms
  and a drag pans. In a table preview the wheel scrolls the rows and the
  columns scroll sideways.

## Touch

Everything above is reachable by touch too: long-press to select a word,
drag the handles to adjust a selection, and use the floating toolbar for
copy/paste.

While the soft keyboard is up, a row above it carries the editor commands
the on-screen keyboard has no keys for: `esc`, `tab`, undo and redo, add a
cursor above or below, select the next occurrence, move a line up or down,
duplicate, delete and join lines, toggle the comment, and the language
server's family — `suggest`, `fix`, `rename`, `refs`, `format`, `sig`,
`type def`, `impl`, `decl`. It appears with the keyboard and goes away with
it, so a paired keyboard or DeX never sees it.

Project search is a touch surface too: tap a match to open the file at it,
tap a file's row to fold its matches, and tap `⋯` for the include and
exclude fields. On a phone the panel takes the whole work area, so opening
a match hands the screen back to the editor — what you typed is kept, and
`☰` → **Search all files…** brings it back.

The preview is a touch surface too. The 👁 in the toolbar opens it, and `✕` in
its title bar closes it — the control it needs a finger to reach, since on a
phone it covers the editor and there is no `Ctrl` `Shift` `M` to press into.
In Markdown, tap a link to follow it (an outside link asks first, with its
address in full), tap a block to take the editor's caret to the line it came
from, tap `⇅` to stop or start following the editor's scroll, and drag to
scroll; in SVG, pinch to zoom, drag to pan and double tap to put both back. In
a table preview, drag sideways for more columns and tap a row to put the caret
on its line. A picture pinches and drags the
same way, with `−` `+` `1:1` `Fit` under it; sound and video have `▶`, `⇤` and
a seek bar. The dock's left edge drags on a
wide screen. Go to line is
the same: the panel's `↵` confirms and `✕` cancels, both reachable while the
soft keyboard is up.

A toast is a touch surface: its `✕` dismisses it, its button — **Show log**,
**Show output**, **Open keymap.json** — does the thing it names, and **+N more**
opens the rest of the stack with **Show fewer** and **Clear all** under it. The
activity indicator at the left of the status bar is a button too: tap the
spinner to go to whatever is running.

The status bar carries one button per panel, each on the side its dock is on:
the file tree, the outline panel, the git panel, project search and the
preview, plus the terminal at the right end. A button lights while its panel is showing, and pressing it
again closes that dock. Project, file and settings commands live in the `☰`
menu in the title bar, and
**Command palette…** at the top of that menu reaches everything else —
it is the touch route to any command that has no button of its own. The
project picker's own footer carries **New**, **Clone…** and **Import
folder**; a clone in progress shows what git is doing and a **Cancel**
that stops it and removes the half-cloned directory.

**The back gesture closes the topmost thing, and only that.** In order:
an open modal (the command palette, go to line, the outline, rename, the
theme picker, the branch picker, any dialog), then the find-in-file bar,
then the terminal dock while the shell has the keyboard (the sessions keep
running, as with ``Ctrl` ` ``), then — on a phone, where a dock takes the
whole work area — that dock. Only with nothing left to close does back reach
Android and put the app in the background. On a tablet, where docks sit
beside the editor, back leaves them alone. Escape inside the terminal is
untouched: the gesture never reaches the shell.

**Files from other apps.** Seeker IDE is an *Open with* and *Share*
target: a text-like file, or a piece of shared text, arrives as an
**Add *name* to *project*?** dialog with a destination path — **Add** puts
it in the open project (a clash gets ` copy` appended rather than
overwriting), **Scratch** puts it in a project called Scratch instead, and
`Esc` or **Cancel** drops it. Going the other way, **Open with…** and
**Share…** are in the project panel's context menu (long-press or
right-click a file), in a tab's menu, and under an audio or video file's
name in its pane.

Panes split by touch as well. The `⊞` in a tab bar's right-hand group opens
a menu with **Split right / left / up / down**, **Join into next**, **Join all
panes** and **Zoom**; the `⤢` beside it zooms the pane and `⤡` zooms back. A
tab's long-press menu has **Split right** and **Split down**, which take *that*
tab into the new pane. Or drag: long-press a tab until it lifts (a short
buzz), then drag it — over another pane's tab bar or middle to move it there,
or to any pane's edge to split that pane on that side; the half a split would
take is highlighted while you hover. A mouse drags a tab straight away. The
line between two panes drags to resize them. Tapping anywhere in a pane makes
it the active one.

In the terminal, the row above the keyboard carries the keys a soft
keyboard doesn't have: `esc`, `tab`, `ctrl`, `alt`, arrows, `home` and
`end`. `ctrl` and `alt` latch for exactly one keypress, so `ctrl` then `c`
sends `^C`. Pinch to change the terminal's font size; long-press a path or a
URL in the output to open it, long-press anything else to select text, with
the usual handles and copy/paste toolbar. The `⌕` in the terminal's tab bar
opens the scrollback search, and the `▶` beside it the task picker; a `▶`
in the editor's gutter runs that row's test or program.

## Your own keymap

The keymap is Zed's: a `keymap.json` next to `settings.json`, in the format
Zed documents at <https://zed.dev/docs/key-bindings>, so a binding copied out
of a Zed keymap works here unchanged. `zed: open keymap` in the palette
(`Ctrl` `K`, then `Ctrl` `S` — Zed's chord), **Edit keymap.json** in the `☰`
menu, or the link under **Settings → Keyboard** opens it as a tab; the first
time, it is a commented starter. **Saving the tab applies it**, exactly as with
settings.json. Comments and trailing commas are fine.

```jsonc
[
  {
    "context": "Workspace",
    "bindings": {
      "ctrl-shift-r": "workspace::Save",      // one more chord for save
      "ctrl-k ctrl-c": "editor::ToggleComments", // a two-stroke chord
      "ctrl-alt-a": null                     // switch a default off
    }
  },
  {
    "context": "Editor",
    "bindings": {
      "ctrl-shift-up": "editor::MoveLineUp",
      "alt-up": null
    }
  }
]
```

**Keystrokes** are modifiers — `ctrl`, `alt`, `shift`, in any order — joined
by `-` and then a key: `a`–`z`, `0`–`9`, `f1`–`f12`, `enter`, `escape`, `tab`,
`backspace`, `delete`, `space`, `insert`, `up`/`down`/`left`/`right`,
`home`/`end`/`pageup`/`pagedown`, and the punctuation keys `-` `=` `[` `]` `;`
`'` `,` `.` `/` `\` `` ` ``. Zed's spellings for shifted glyphs work too:
`ctrl-{` is `ctrl-shift-[` and `ctrl-!` is `ctrl-shift-1`. `cmd` and `super`
are refused with a sentence — an Android keyboard has not got them. Several
keystrokes separated by spaces make a **chord** (`ctrl-k ctrl-c`): after the
first stroke the status bar shows `Ctrl K` while it waits, for a second and a
half, for the rest; a stroke that continues no chord ends the wait and means
what it always meant.

**Contexts** are the part of Zed's tree this app has: `Workspace`, `Pane`,
`Editor`, `Terminal`, `AgentPanel` (Zed's `AcpThread` is the same surface
here — the agent composer) and `ImageViewer` (a picture as the open tab), or
no `"context"` at all for everywhere. A deeper
context wins over a shallower one, and at the same depth the later binding
wins, which is how yours beat the defaults. A **terminal hears only `Terminal`
bindings** — every plain `Ctrl`+letter belongs to the shell — so a chord meant
for a shell has to say `"context": "Terminal"`. A section whose context this
app does not have (`ProjectPanel`, `Editor && vim_mode == normal`) is skipped.

**Actions** are Zed's names, which is what the palette prints beside each
command; `zed: open default keymap` opens the whole default table in this same
format, which is the list of every name there is. `null` unbinds. An action
that takes an argument is written as Zed writes it: `["pane::ActivateItem", 2]`.

**`base_keymap`** in settings.json — **Settings → Keyboard → Base keymap** —
lays one of Zed's alternative keymaps over the defaults: `"VSCode"` (the
default, and Zed's), `"JetBrains"`, `"SublimeText"`, `"Atom"` or `"Emacs"`.
They are Zed's own files, filtered to the actions this app has; `"None"`
switches every built-in binding off and leaves keymap.json as the whole
keymap.

A line that cannot be used — a keystroke that does not parse, an action this
app has not got, a section that is not an object — costs that line and nothing
else: the rest of the file loads, a strip above the status bar says what was
wrong with **Open keymap.json** beside it, and **Settings → Keyboard** lists
every problem until the next save fixes it.
