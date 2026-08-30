package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import to.eyed.seeker.code.core.ClosePosition
import to.eyed.seeker.code.core.TabSettings
import to.eyed.seeker.code.ui.theme.LocalAppSettings
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.touchTarget
import to.eyed.seeker.code.ui.theme.ZedRadius
import to.eyed.seeker.code.ui.theme.glyphHeight
import to.eyed.seeker.code.ui.theme.rem
import to.eyed.seeker.code.ui.theme.remsAt
import to.eyed.seeker.code.ui.theme.revealItem
import to.eyed.seeker.code.ui.theme.revealBy

/**
 * The tab strip's metrics as rem multiples — `ui_font_size` is the rem
 * (theme_settings/src/settings.rs:619), so raising the UI font grows the strip
 * with its labels rather than leaving 14px tabs full of 20px text.
 *
 * Bare numbers rather than `Dp` so the table is checkable on the host at any
 * font size (`ChromeMetricsTest`); the composable getters underneath are what
 * the strip reads.
 */
internal object TabMetrics {

    /**
     * `DynamicSpacing::Base32` — 32px at the default 16px rem, and a rem
     * despite the name (tab.rs:84, ui_macros/src/dynamic_spacing.rs:147-162).
     * It was 40 for a while, for the ✕ and the dot; the 2026-08-17 density
     * decision in DECISIONS.md reversed that — exact Zed metrics win, and every
     * small target keeps a second route (the tab's long-press menu closes it,
     * Ctrl+S saves it).
     */
    const val BAR_HEIGHT = 2f

    /** `px(Base04)` inside the tab, and the gap between its slots (tab.rs:173-174). */
    const val CONTENT_PADDING = 0.25f
    const val CONTENT_GAP = 0.25f

    /** `Indicator::dot()` — `w_1p5`/`h_1p5` = `rems(0.375)` (indicator.rs:73-78). */
    const val DIRTY_DOT = 0.375f

    /**
     * How wide a label is allowed to get before it ellipsises. Ours, not Zed's
     * — Zed truncates the *string* at 24 characters (items.rs:66) — but it is a
     * measure of text, so it grows with the text.
     */
    const val MAX_LABEL_WIDTH = 11.25f

    /** How far one notch of the wheel moves the strip. About one narrow tab. */
    const val WHEEL_STEP = 6f

    /**
     * The fixed groups at the strip's ends: Zed frames each in `px(Base06)`
     * with `gap(Base04)` between the buttons, bordered below and on the side
     * facing the tabs (tab_bar.rs:103-112 for the start group, 141-150
     * mirrored for the end one).
     */
    const val TOOL_GROUP_PADDING = 0.375f
    const val TOOL_GROUP_GAP = 0.25f

    /**
     * Zed's IconButton at `ButtonSize::Default`: `rems_from_px(22)`
     * (button_like.rs:465-473).
     */
    const val TOOL_BUTTON_BOX = 1.375f

    /**
     * Base32 − 1px: the border, or the selected tab's `pb_px`, eats it
     * (tab.rs:79). Only the 32 scales; the pixel it gives up is a real pixel.
     */
    fun contentHeight(uiFontSize: Float): Dp = remsAt(uiFontSize, BAR_HEIGHT) - TabPixels.Border
}

/**
 * The tab dimensions Zed writes in **pixels**, which do not move with
 * `ui_font_size`.
 *
 * The slots are the interesting pair: `START_TAB_SLOT_SIZE` and
 * `END_TAB_SLOT_SIZE` are `px(12.)` and `px(14.)` (tab.rs:8-9), not rems, so
 * spelling them `rem(0.75f)`/`rem(0.875f)` would have made our tabs diverge
 * from Zed's at exactly the setting this change exists to honour. The dot
 * doubles as the save button and the ✕ closes — both keep a bigger route
 * (Ctrl+S, the long-press menu), which is what the density decision in
 * DECISIONS.md asks for instead of widening the slots.
 */
internal object TabPixels {

    /** Zed's `border_1`, which is every border in the chrome (styles.rs:1337). */
    val Border = 1.dp

    val StartSlotWidth = 12.dp
    val EndSlotWidth = 14.dp
}

/**
 * The bar, with the accessibility floor on top of Zed's metric.
 *
 * `max(rem(2), the label's ink)`: at every ordinary font scale this is exactly
 * [TabMetrics.BAR_HEIGHT] — 32dp at the default — and it only grows once the
 * *system's* font scale has made a tab label taller than the bar Zed specifies,
 * which is the point at which a fixed 32 would start slicing the ascenders off.
 * See [glyphHeight].
 */
internal val TabBarHeight: Dp
    @Composable @ReadOnlyComposable get() = maxOf(
        rem(TabMetrics.BAR_HEIGHT),
        glyphHeight(MaterialTheme.typography.bodyMedium) + TabPixels.Border,
    )

/** The bar less the pixel the border takes, whichever of the two won above. */
private val TabContentHeight: Dp
    @Composable @ReadOnlyComposable get() = TabBarHeight - TabPixels.Border

private val TabContentPadding: Dp
    @Composable @ReadOnlyComposable get() = rem(TabMetrics.CONTENT_PADDING)

private val TabContentGap: Dp
    @Composable @ReadOnlyComposable get() = rem(TabMetrics.CONTENT_GAP)

private val DirtyDotSize: Dp
    @Composable @ReadOnlyComposable get() = rem(TabMetrics.DIRTY_DOT)

private val MaxTabLabelWidth: Dp
    @Composable @ReadOnlyComposable get() = rem(TabMetrics.MAX_LABEL_WIDTH)

/** `IconSize::Small` — `rems(0.875)` (ui/src/styles/typography.rs). */
private val TabIconSize: Dp
    @Composable @ReadOnlyComposable get() = rem(0.875f)

/** How close two clicks have to be to count as one double-click. */
private const val DOUBLE_CLICK_MS = 300L

private val ToolGroupPadding: Dp
    @Composable @ReadOnlyComposable get() = rem(TabMetrics.TOOL_GROUP_PADDING)

private val ToolGroupGap: Dp
    @Composable @ReadOnlyComposable get() = rem(TabMetrics.TOOL_GROUP_GAP)

private val ToolButtonBox: Dp
    @Composable @ReadOnlyComposable get() = rem(TabMetrics.TOOL_BUTTON_BOX)

/**
 * Zed-style tab strip: one tab per open file, a dot for unsaved edits, a
 * close affordance on each, and pinned tabs held at the left.
 *
 * The dot and the close button are separate targets rather than the desktop
 * dot-turns-into-× trick, which depends on hover — a gesture a touch device
 * doesn't have.
 *
 * Closing goes through [OpenFilesState.requestClose] rather than
 * `close`, so a buffer with unsaved edits asks before it is dropped; the
 * question itself is [UnsavedChangesDialog], hosted here so that every route
 * into the strip is covered by it.
 *
 * Mouse and touch reach the same menu: right-click or long-press a tab.
 * Middle-click closes one, and the wheel scrolls the strip — a vertical wheel
 * on a horizontal strip, because that is the wheel most mice have.
 *
 * The strip sits between two fixed groups, as Zed's does: the navigation
 * arrows at the left (tab_bar.rs:103-112) and the `+` at the right
 * (tab_bar.rs:141-150) stay put while the tabs scroll between them. Zed's
 * `tab_bar` block switches each of the three off — `show`,
 * `show_nav_history_buttons`, `show_tab_bar_buttons` (default.json:1386-1397).
 *
 * With `show: false` this composable still runs and draws nothing but
 * [UnsavedChangesDialog]: the dialog is hosted here, and every route into
 * closing a tab — the palette, a chord, the project panel — goes through it.
 * Hiding the strip must not quietly make Ctrl+W drop unsaved work.
 */
@Composable
internal fun EditorTabs(
    files: OpenFilesState,
    onSave: (OpenFile) -> Unit,
    modifier: Modifier = Modifier,
    /** Wired by the workspace, which knows how to open a file again. */
    onReopen: (() -> Unit)? = null,
    /** Zed's `pane::GoBack` — the workspace owns it, since going back can reopen a file. */
    onNavigateBack: (() -> Unit)? = null,
    /** Zed's `pane::GoForward`. */
    onNavigateForward: (() -> Unit)? = null,
    /** The workspace's new-file flow — what Zed's `+` leads with (pane.rs:4272). */
    onNewFile: (() -> Unit)? = null,
    /** "Open with…" for a tab that is a file on disk — Zed's `workspace::OpenWithSystem`. */
    onOpenWith: ((OpenFile) -> Unit)? = null,
    /** "Share…" — Android's share sheet; no Zed counterpart. */
    onShare: ((OpenFile) -> Unit)? = null,
    /**
     * The split button's menu — Zed's `split_item` popover in the end group
     * (pane.rs:4353-4380) — built by the workspace, which owns the panes.
     * Null hides the button.
     */
    splitMenu: (() -> List<ContextMenuItem>)? = null,
    /** Zed's zoom button beside it (pane.rs:4384-4396); null hides it. */
    onToggleZoom: (() -> Unit)? = null,
    isZoomed: Boolean = false,
    /** The tab menu's "Split right" / "Split down": this tab, into a new pane. */
    onSplitTab: ((Int, SplitDirection) -> Unit)? = null,
    /** Dragging a tab out of the strip — to another pane, or to an edge. */
    onDragStart: ((OpenFile, Offset) -> Unit)? = null,
    onDrag: ((Offset) -> Unit)? = null,
    onDragEnd: (() -> Unit)? = null,
    onDragCancel: (() -> Unit)? = null,
    /** Zed's `tabs` block: icons, git tint, the diagnostics mark, close side. */
    tabs: TabSettings = TabSettings(),
    /**
     * A tab's git status, for `tabs.git_status`. A lambda rather than a map so
     * the strip reads one path at a time and nothing is allocated per frame;
     * the default says "no repository", which is what a plain tab looks like.
     */
    gitStatusOf: (String) -> GitFileStatus = { GitFileStatus.None },
    /**
     * Whether a tab's file is marked by `tabs.show_diagnostics` — the workspace
     * applies the setting's `off`/`errors`/`all` and answers yes or no.
     */
    hasDiagnostics: (String) -> Boolean = { false },
    /** The Ctrl+Tab switcher, for a finger: the strip's own ⇥ button. */
    onOpenSwitcher: (() -> Unit)? = null,
) {
    val bar = LocalAppSettings.current.tabBar
    if (!bar.show) {
        UnsavedChangesDialog(files)
        return
    }
    val theme = LocalZedTheme.current
    val border = theme.color("border")
    val strip = rememberLazyListState()
    val scope = rememberCoroutineScope()
    // The strip's left edge in root coordinates. A tab drag is reported in
    // root coordinates — the frame the pane group keeps its pane bounds in —
    // and the reorder needs it in the strip's, where the item offsets are.
    // NaN until the row has been laid out, which is "no reorder yet".
    var stripLeft by remember { mutableFloatStateOf(Float.NaN) }
    val wheelStep = with(LocalDensity.current) { rem(TabMetrics.WHEEL_STEP).toPx() }
    val barHeight = TabBarHeight
    val borderWidth = TabPixels.Border

    // Ctrl+Tab and Ctrl+9 can select a tab that is scrolled out of the strip
    // entirely; bring it back rather than leaving the user looking at tabs
    // that are not the one they are editing.
    LaunchedEffect(files.activeIndex, files.tabs.size) {
        val index = files.activeIndex
        if (index < 0) return@LaunchedEffect
        val item = strip.layoutInfo.visibleItemsInfo.firstOrNull { it.index == index }
        val viewportEnd = strip.layoutInfo.viewportEndOffset
        when {
            item == null -> strip.revealItem(index)
            item.offset < 0 -> strip.revealBy(item.offset.toFloat())
            item.offset + item.size > viewportEnd ->
                strip.revealBy((item.offset + item.size - viewportEnd).toFloat())
        }
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(theme.color("tab_bar.background")),
    ) {
        // Zed's start group: back and forward, greyed when their stack is
        // empty rather than hidden (pane.rs:3407-3452). The glyphs are text —
        // sized like the tab's ✕ — since chrome here draws no new icons.
        if (bar.showNavHistoryButtons && (onNavigateBack != null || onNavigateForward != null)) {
            TabBarToolGroup(trailing = false) {
                TabBarIconButton(
                    glyph = "←",
                    label = "Go back",
                    enabled = files.canGoBack && onNavigateBack != null,
                    onClick = { onNavigateBack?.invoke() },
                )
                TabBarIconButton(
                    glyph = "→",
                    label = "Go forward",
                    enabled = files.canGoForward && onNavigateForward != null,
                    onClick = { onNavigateForward?.invoke() },
                )
            }
        }
        LazyRow(
            state = strip,
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .weight(1f)
                .fillMaxHeight()
                .onGloballyPositioned { stripLeft = it.positionInRoot().x }
                // The strip's own bottom border, drawn *behind* the tabs so the
                // selected one's background covers its share of it — which is
                // what makes the active tab read as open into the editor below
                // rather than as a label sitting on a line (tab_bar.rs:122-128).
                // The end groups draw their own stretch, so the line runs
                // unbroken across all three.
                .drawBehind {
                    val thickness = borderWidth.toPx()
                    drawRect(
                        color = border,
                        topLeft = Offset(0f, size.height - thickness),
                        size = Size(size.width, thickness),
                    )
                }
                .pointerInput(wheelStep) {
                    // A horizontally scrolling row ignores a vertical wheel,
                    // which is the only wheel most mice have. Taken in the
                    // initial pass and consumed, so it can't also scroll
                    // something else.
                    awaitPointerEventScope {
                        while (true) {
                            val event = awaitPointerEvent(PointerEventPass.Initial)
                            if (event.type != PointerEventType.Scroll) continue
                            val delta = event.changes.fold(0f) { sum, change ->
                                sum + change.scrollDelta.y + change.scrollDelta.x
                            }
                            if (delta == 0f) continue
                            event.changes.forEach { it.consume() }
                            scope.launch { strip.scrollBy(delta * wheelStep) }
                        }
                    }
                },
        ) {
            tabItems(
                files, onSave, onReopen, onOpenWith, onShare, onSplitTab,
                settings = tabs,
                gitStatusOf = gitStatusOf,
                hasDiagnostics = hasDiagnostics,
                strip = strip,
                stripOrigin = { stripLeft },
                scope = scope,
                drag = if (onDragStart != null) {
                    TabDragHandlers(onDragStart, onDrag ?: {}, onDragEnd ?: {}, onDragCancel ?: {})
                } else {
                    null
                },
            )
        }
        // Zed's end group holds `+`, split and zoom, and shows them only
        // while the pane has focus (pane.rs:4244-4250). A finger has no
        // focus to speak of, so they show while the pane has tabs. The ⇥ is
        // this app's addition: the Ctrl+Tab switcher's gesture is
        // hold-and-release, which a finger cannot perform, so it needs a
        // button of its own to be reachable at all.
        if (
            bar.showTabBarButtons &&
            files.tabs.isNotEmpty() &&
            (onNewFile != null || onOpenSwitcher != null || splitMenu != null || onToggleZoom != null)
        ) {
            TabBarToolGroup(trailing = true) {
                if (onOpenSwitcher != null) {
                    TabBarIconButton(
                        glyph = "⇥",
                        label = "Switch tab",
                        enabled = files.tabs.size > 1,
                        onClick = onOpenSwitcher,
                    )
                }
                if (onNewFile != null) {
                    TabBarIconButton(
                        glyph = "+",
                        label = "New file",
                        enabled = true,
                        onClick = onNewFile,
                    )
                }
                if (splitMenu != null) {
                    var splitMenuOpen by remember { mutableStateOf(false) }
                    Box {
                        TabBarIconButton(
                            glyph = "⊞",
                            label = "Split pane",
                            enabled = true,
                            onClick = { splitMenuOpen = true },
                        )
                        if (splitMenuOpen) {
                            ContextMenu(
                                expanded = true,
                                onDismiss = { splitMenuOpen = false },
                                items = splitMenu(),
                                offset = DpOffset.Zero,
                            )
                        }
                    }
                }
                if (onToggleZoom != null) {
                    TabBarIconButton(
                        glyph = if (isZoomed) "⤡" else "⤢",
                        label = if (isZoomed) "Zoom out" else "Zoom in",
                        enabled = true,
                        onClick = onToggleZoom,
                    )
                }
            }
        }
    }

    UnsavedChangesDialog(files)
}

/**
 * The frame of one fixed group: bordered below like the strip, and on the
 * side facing the tabs — `border_b_1` + `border_r_1` leading,
 * `border_b_1` + `border_l_1` trailing (tab_bar.rs:107-110, 145-148).
 */
@Composable
private fun TabBarToolGroup(
    trailing: Boolean,
    content: @Composable RowScope.() -> Unit,
) {
    val border = LocalZedTheme.current.color("border")
    val borderWidth = TabPixels.Border
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(ToolGroupGap),
        modifier = Modifier
            .fillMaxHeight()
            .drawBehind {
                val thickness = borderWidth.toPx()
                drawRect(
                    color = border,
                    topLeft = Offset(0f, size.height - thickness),
                    size = Size(size.width, thickness),
                )
                val edge = if (trailing) 0f else size.width - thickness
                drawRect(
                    color = border,
                    topLeft = Offset(edge, 0f),
                    size = Size(thickness, size.height),
                )
            }
            .padding(horizontal = ToolGroupPadding),
        content = content,
    )
}

/**
 * Zed's IconButton in its `Subtle` ghost style: a 22dp box, transparent at
 * rest, `ghost_element.hover`/`.active` swapped instantly under the pointer
 * (button_like.rs:298-303). Disabled keeps the box and greys the glyph to
 * `text.disabled`, as a disabled `IconButton` does (Color::Disabled,
 * ui/src/styles/color.rs) — present but inert, so the group never reflows.
 */
@Composable
private fun TabBarIconButton(
    glyph: String,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val theme = LocalZedTheme.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            // The glyph is text, not a drawable, so the box is sized from the
            // same TextUnit: `rem(1.375f)` — Zed's 22px button — until the
            // system's font scale makes the arrow taller than that, and then
            // the arrow. Width follows height so the box stays square.
            .size(maxOf(ToolButtonBox, glyphHeight(MaterialTheme.typography.labelMedium)))
            .clip(RoundedCornerShape(rem(ZedRadius.SM)))
            // A glyph is not a name: `onClickLabel` tells a screen reader what
            // the tap *does*, and without this the node it does it to has no
            // label at all.
            .semantics { contentDescription = label }
            .touchTarget()
            .background(
                when {
                    !enabled -> Color.Transparent
                    pressed -> theme.color("ghost_element.active", Color.Transparent)
                    hovered -> theme.color("ghost_element.hover", Color.Transparent)
                    else -> Color.Transparent
                }
            )
            .then(
                if (enabled) {
                    Modifier
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            interactionSource = interaction,
                            indication = null,
                            onClickLabel = label,
                            onClick = onClick,
                        )
                } else {
                    Modifier
                }
            ),
    ) {
        Text(
            text = glyph,
            style = MaterialTheme.typography.labelMedium,
            color = if (enabled) {
                theme.color("icon", MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                theme.color("text.disabled", MaterialTheme.colorScheme.onSurfaceVariant)
            },
        )
    }
}

/** The drag half of [TabGestures], as the workspace hands it to the strip. */
private class TabDragHandlers(
    val onStart: (OpenFile, Offset) -> Unit,
    val onMove: (Offset) -> Unit,
    val onEnd: () -> Unit,
    val onCancel: () -> Unit,
)

/** Kept out of [EditorTabs] only so the lambda nesting stays readable. */
private fun LazyListScope.tabItems(
    files: OpenFilesState,
    onSave: (OpenFile) -> Unit,
    onReopen: (() -> Unit)?,
    onOpenWith: ((OpenFile) -> Unit)?,
    onShare: ((OpenFile) -> Unit)?,
    onSplitTab: ((Int, SplitDirection) -> Unit)?,
    settings: TabSettings,
    gitStatusOf: (String) -> GitFileStatus,
    hasDiagnostics: (String) -> Boolean,
    strip: LazyListState,
    /** The strip's left edge in root coordinates, or NaN before it is laid out. */
    stripOrigin: () -> Float,
    scope: CoroutineScope,
    drag: TabDragHandlers?,
) {
    items(count = files.tabs.size, key = { index -> files.tabs[index].path }) { index ->
        val file = files.tabs[index]
        EditorTab(
            file = file,
            isActive = index == files.activeIndex,
            borders = tabBorders(index, files.activeIndex, files.tabs.size),
            settings = settings,
            status = if (settings.gitStatus) gitStatusOf(file.path) else GitFileStatus.None,
            hasDiagnostics = hasDiagnostics(file.path),
            // Every index is looked up by path rather than closed over: a
            // reorder drag moves this tab while the callback is alive.
            menu = {
                tabMenu(files, files.indexOfPath(file.path), onReopen, onOpenWith, onShare, onSplitTab)
            },
            onSelect = { files.select(files.indexOfPath(file.path)) },
            // Zed's double-click on a preview tab is what makes it permanent
            // (`Pane::handle_tab_double_click` clears `preview_item_id`).
            onPromote = { files.promote(file.path) },
            onSave = { onSave(file) },
            onClose = { files.requestClose(files.indexOfPath(file.path)) },
            onTogglePin = { files.togglePin(files.indexOfPath(file.path)) },
            // Drag to reorder — Zed's `Pane::move_item`. The same drag that
            // carries a tab to another pane reorders it while it is still
            // over its own strip: the pointer is reported in root
            // coordinates (see TabDrag.kt), turned into a strip coordinate
            // here, where the layout is known, and the tab it is over is the
            // new position. Outside the strip nothing is reordered and the
            // drop lands in whichever pane the pointer is over — a drop back
            // on this pane's own tab bar is a no-op, since the tab is already
            // where the reorder put it (`PaneGroup.moveItem`).
            //
            // The tab's *current* index is looked up every time because the
            // move that just happened changed it.
            onDragTo = { root ->
                val box = strip.layoutInfo.viewportSize
                val origin = stripOrigin()
                val x = root.x - origin
                if (origin.isFinite() && x >= 0f && x <= box.width) {
                    val from = files.indexOfPath(file.path)
                    val target = strip.layoutInfo.visibleItemsInfo.firstOrNull {
                        x >= it.offset && x < it.offset + it.size
                    }
                    if (from >= 0 && target != null && target.index != from) {
                        files.move(from, target.index)
                    }
                    // A tab dragged to either edge scrolls the strip, so a
                    // long strip can be reordered end to end.
                    val viewportEnd = strip.layoutInfo.viewportEndOffset
                    val nudge = when {
                        x < DRAG_EDGE_PX -> -DRAG_SCROLL_PX
                        x > viewportEnd - DRAG_EDGE_PX -> DRAG_SCROLL_PX
                        else -> 0f
                    }
                    if (nudge != 0f) scope.launch { strip.scrollBy(nudge) }
                }
            },
            drag = drag,
        )
    }
}

/** How close to an edge a drag has to get before the strip scrolls, in px. */
private const val DRAG_EDGE_PX = 48f

/** How far one drag frame at the edge scrolls it. */
private const val DRAG_SCROLL_PX = 12f

/**
 * The tab's own menu, in Zed's order.
 *
 * The bulk closes leave pinned tabs alone — `close_pinned: false` is Zed's
 * default for all three — so pinning a tab is a way of saying "not this one"
 * once, rather than every time.
 */
private fun tabMenu(
    files: OpenFilesState,
    index: Int,
    onReopen: (() -> Unit)?,
    onOpenWith: ((OpenFile) -> Unit)?,
    onShare: ((OpenFile) -> Unit)?,
    onSplitTab: ((Int, SplitDirection) -> Unit)?,
): List<ContextMenuItem> {
    val file = files.tabs.getOrNull(index) ?: return emptyList()
    val closable = files.tabs.count { !it.isPinned }
    return buildList {
        add(ContextMenuItem("Close", "Ctrl W") { files.requestClose(index) })
        add(
            ContextMenuItem("Close others", enabled = closable > if (file.isPinned) 0 else 1) {
                files.requestCloseOthers(index)
            }
        )
        // Zed's order after the separator: Left, Right, Clean, All
        // (pane.rs:3177-3220).
        add(
            ContextMenuItem(
                "Close to the left",
                enabled = files.tabs.take(index).any { !it.isPinned },
            ) { files.requestCloseToTheLeft(index) }
        )
        add(
            ContextMenuItem(
                "Close to the right",
                enabled = files.tabs.drop(index + 1).any { !it.isPinned },
            ) { files.requestCloseToTheRight(index) }
        )
        add(
            ContextMenuItem(
                "Close clean",
                enabled = files.tabs.any { !it.isPinned && !it.isDirty },
            ) { files.requestCloseClean() }
        )
        add(ContextMenuItem("Close all", enabled = closable > 0) { files.requestCloseAll() })
        add(
            ContextMenuItem(if (file.isPinned) "Unpin tab" else "Pin tab") {
                files.togglePin(index)
            }
        )
        // Only offered on a preview tab: on a permanent one it would be a row
        // that does nothing, which is worse than no row.
        if (file.isPreview) {
            add(ContextMenuItem("Keep open") { files.promote(file.path) })
        }
        if (onReopen != null) {
            add(
                ContextMenuItem("Reopen closed tab", "Ctrl Shift T", files.hasClosedTabs) {
                    onReopen()
                }
            )
        }
        // The two splits a finger is most likely to want — Zed's tab bar
        // menu offers all four (pane.rs:4366-4376); the other two are in the
        // strip's ⊞ menu and the palette. This tab goes into the new pane.
        if (onSplitTab != null) {
            add(
                ContextMenuItem("Split right", separatorAbove = true) {
                    onSplitTab(index, SplitDirection.Right)
                }
            )
            add(ContextMenuItem("Split down") { onSplitTab(index, SplitDirection.Down) })
        }
        // A file on disk can leave the app; a diff, the graph or the
        // diagnostics have no file to hand over, so the group is absent
        // rather than greyed — there is nothing it could ever do there.
        if (file.absolutePath != null) {
            if (onOpenWith != null) {
                add(
                    ContextMenuItem("Open with…", "Ctrl Shift Enter", separatorAbove = true) {
                        onOpenWith(file)
                    }
                )
            }
            if (onShare != null) {
                add(
                    ContextMenuItem("Share…", separatorAbove = onOpenWith == null) { onShare(file) }
                )
            }
        }
    }
}

/**
 * Which edges of one tab are drawn, and which are 1px of padding instead.
 *
 * Every case leaves exactly 1px on each side and 1px at the bottom, so the
 * label sits in the same place whichever tab is selected — that is why Zed
 * pads where it does not draw (tab.rs:150-165).
 */
private data class TabBorders(val left: Boolean, val right: Boolean, val bottom: Boolean)

/**
 * Zed's five cases, by where a tab sits relative to the selected one.
 *
 * The selected tab is the one with side borders and *no* bottom border; its
 * neighbours carry a bottom border and lend it the side they face. That is the
 * whole trick: the active tab is the only break in the line under the strip.
 */
private fun tabBorders(index: Int, activeIndex: Int, count: Int): TabBorders {
    val selected = index == activeIndex
    return when {
        // The first tab has nothing to its left to separate it from.
        index == 0 -> TabBorders(left = false, right = selected, bottom = !selected)
        selected -> TabBorders(left = true, right = true, bottom = false)
        index == count - 1 -> TabBorders(left = false, right = true, bottom = true)
        index < activeIndex -> TabBorders(left = true, right = false, bottom = true)
        else -> TabBorders(left = false, right = true, bottom = true)
    }
}

@Composable
private fun EditorTab(
    file: OpenFile,
    isActive: Boolean,
    borders: TabBorders,
    settings: TabSettings,
    status: GitFileStatus,
    hasDiagnostics: Boolean,
    menu: () -> List<ContextMenuItem>,
    onSelect: () -> Unit,
    onPromote: () -> Unit,
    onSave: () -> Unit,
    onClose: () -> Unit,
    onTogglePin: () -> Unit,
    /** The pointer, in root coordinates, while a drag is running. */
    onDragTo: (Offset) -> Unit,
    drag: TabDragHandlers?,
) {
    val theme = LocalZedTheme.current
    var menuAt by remember { mutableStateOf<DpOffset?>(null) }
    var lastClickAt by remember { mutableLongStateOf(0L) }
    /**
     * A long press has fired on this tab, so the click that follows it is not
     * a click. `clickable`'s tap detector has no timeout and reports one
     * anyway; see [onAnyPress], which is what clears this again.
     */
    val longPressed = remember { mutableStateOf(false) }
    val tabInteraction = remember { MutableInteractionSource() }
    val tabHovered by tabInteraction.collectIsHoveredAsState()
    val gestures = TabGestures(
        onSelect = {
            onSelect()
            // Double-click promotes a preview tab. Timed by hand rather than
            // asked of a double-tap detector, which would delay *every*
            // single click by the double-tap timeout — and selecting a tab is
            // the gesture that has to be instant.
            val now = System.currentTimeMillis()
            if (now - lastClickAt < DOUBLE_CLICK_MS) onPromote()
            lastClickAt = now
        },
        onMenu = { menuAt = DpOffset.Zero },
        onDragStart = { position -> drag?.onStart(file, position) },
        onDrag = { position ->
            // Reorder first, so a tab dragged along its own strip moves under
            // the finger; the workspace still hears every move, because the
            // drop target is its to decide.
            onDragTo(position)
            drag?.onMove(position)
        },
        onDragEnd = { drag?.onEnd() },
        onDragCancel = { drag?.onCancel() },
    )
    val onSurfaceVariant = MaterialTheme.colorScheme.onSurfaceVariant
    // Zed tints a tab's *label* by git status when `tabs.git_status` is on —
    // `tab_content` asks `entry_git_aware_label_color` for the colour, the
    // same function the project panel's rows use (editor/src/items.rs:
    // 2205-2219). Resolved once per tab rather than per frame.
    val statusColours = remember(theme, onSurfaceVariant) {
        // `forProjectPanel`, despite the name: it is the resolution order for
        // the *status family* (`modified`, `created`, …), which is what
        // `entry_git_aware_label_color` reads, and the tab and the tree
        // therefore agree on one colour per file.
        GitStatusColours.forProjectPanel(theme, onSurfaceVariant, onSurfaceVariant)
    }
    val background = if (isActive) {
        theme.color("tab.active_background")
    } else {
        theme.color("tab.inactive_background")
    }
    val foreground = when {
        // A file that vanished underneath us is worth shouting about; an
        // unsaved one is only worth marking.
        file.isDeleted -> theme.color("error", MaterialTheme.colorScheme.error)
        status != GitFileStatus.None ->
            statusColours.colorFor(status, isIgnored = false, dimIgnored = false)
        isActive -> MaterialTheme.colorScheme.onSurface
        else -> onSurfaceVariant
    }
    val border = theme.color("border")
    val borderWidth = TabPixels.Border
    Box(
        modifier = Modifier
            .height(TabBarHeight)
            .background(background)
            .drawBehind {
                val thickness = borderWidth.toPx()
                if (borders.left) {
                    drawRect(border, Offset.Zero, Size(thickness, size.height))
                }
                if (borders.right) {
                    drawRect(
                        border,
                        Offset(size.width - thickness, 0f),
                        Size(thickness, size.height),
                    )
                }
                if (borders.bottom) {
                    drawRect(
                        border,
                        Offset(0f, size.height - thickness),
                        Size(size.width, thickness),
                    )
                }
            }
            .pointerHoverIcon(PointerIcon.Hand)
            .onAnyPress { longPressed.value = false }
            .onSecondaryClick { position -> menuAt = position }
            .onMiddleClick { if (!file.isPinned) onClose() }
            // Zed's tabs do not change colour on hover (tab.rs:112-125
            // computes the hover fills and drops them); the source is only
            // for knowing when to show the ✕.
            .hoverable(tabInteraction)
            // Tap, long-press and drag — see TabDrag.kt for how a finger
            // and a mouse are told apart, and how a long press decides at the
            // end whether it was a drag or the menu.
            .tabGestures(gestures),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(TabContentGap),
            modifier = Modifier
                // The pixel the border takes, whether or not this tab draws
                // one: the content box is 31px tall and inset by 1 on each
                // side in all five cases.
                .padding(
                    start = TabPixels.Border,
                    end = TabPixels.Border,
                    bottom = TabPixels.Border,
                )
                .height(TabContentHeight)
                .padding(horizontal = TabContentPadding),
        ) {
            // Zed's slot order: the start slot, the label, the end slot.
            // `tabs.close_position` decides which of the two marks lives at
            // which end -- "left" puts the close button in the start slot and
            // the unsaved dot in the end one, which is the whole of what the
            // setting does.
            val closeAtStart = settings.closePosition == ClosePosition.Left
            if (closeAtStart) {
                TabCloseSlot(file, isActive, tabHovered, onClose, onTogglePin)
            } else {
                TabDirtySlot(file, foreground, onSave)
            }
            // `tabs.file_icons`: the file's own icon, in `icon.muted` like
            // every other monochrome icon in the chrome.
            if (settings.fileIcons) {
                Image(
                    painter = FileIcons.forFile(file.name),
                    contentDescription = null,
                    colorFilter = ColorFilter.tint(theme.color("icon.muted", onSurfaceVariant)),
                    modifier = Modifier.size(TabIconSize),
                )
            }
            // `tabs.show_diagnostics`: a dot beside the title for a file its
            // servers have complained about. Zed marks the *icon* and so
            // shows nothing at all without `file_icons`; a dot of its own
            // means the setting works on its own terms.
            if (hasDiagnostics) {
                Box(
                    modifier = Modifier
                        .size(DirtyDotSize)
                        .clip(CircleShape)
                        .background(theme.color("error", MaterialTheme.colorScheme.error)),
                )
            }
            Text(
                text = file.name,
                style = MaterialTheme.typography.bodyMedium,
                // A preview tab's title is italic -- Zed's one visual mark for
                // a provisional tab (`tab_content` sets `italic()` when the
                // item is the pane's preview item).
                fontStyle = if (file.isPreview) FontStyle.Italic else FontStyle.Normal,
                color = foreground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.widthIn(max = MaxTabLabelWidth),
            )
            if (closeAtStart) {
                TabDirtySlot(file, foreground, onSave)
            } else {
                TabCloseSlot(file, isActive, tabHovered, onClose, onTogglePin)
            }
        }
        val position = menuAt
        if (position != null) {
            ContextMenu(
                expanded = true,
                onDismiss = { menuAt = null },
                items = menu(),
                offset = position,
            )
        }
    }
}

/**
 * The unsaved-work dot, which doubles as the save button.
 *
 * The status bar has one too, but the soft keyboard covers the status bar —
 * and typing is exactly when you want to save, so the affordance has to live
 * up here as well.
 */
@Composable
private fun TabDirtySlot(file: OpenFile, foreground: Color, onSave: () -> Unit) {
    val theme = LocalZedTheme.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            // Genuinely `px(12.)` in Zed (tab.rs:8), and what it holds is a
            // dp-sized dot, so nothing here grows with the font.
            .width(TabPixels.StartSlotWidth)
            .fillMaxHeight()
            .then(
                if (file.isDirty) {
                    Modifier
                        // A dot is not a name, and this one is the only
                        // one-tap save a tab has.
                        .semantics { contentDescription = "Save ${file.name}" }
                        .touchTarget()
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(onClickLabel = "Save", onClick = onSave)
                } else {
                    Modifier
                }
            ),
    ) {
        if (file.isDirty || file.hasDiskChange) {
            Box(
                modifier = Modifier
                    .size(DirtyDotSize)
                    .clip(CircleShape)
                    .background(
                        // `warning` is a file that moved under the buffer;
                        // plain unsaved work is `text.accent`
                        // (pane.rs:4973-4979).
                        if (file.hasDiskChange) {
                            theme.color("warning", foreground)
                        } else {
                            theme.color("text.accent", foreground)
                        }
                    ),
            )
        }
    }
}

/**
 * The ✕, or the pin on a pinned tab.
 *
 * A pinned tab shows the pin where the ✕ would be, as Zed does: the way out of
 * a pinned tab is to unpin it, and the mark is the button that does that. The
 * ✕ itself appears on hover, which is Zed's default (pane.rs:3014-3015) — and
 * on the active tab, which is not: a finger has no hover, and the active tab's
 * ✕ is the only one-tap close it gets. Inactive tabs close from their
 * long-press menu or the wheel button.
 */
@Composable
private fun TabCloseSlot(
    file: OpenFile,
    isActive: Boolean,
    hovered: Boolean,
    onClose: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val show = file.isPinned || isActive || hovered
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            // `px(14.)` in Zed (tab.rs:9) — but unlike the start slot this one
            // holds *text*, so the pixel width is a minimum: at an
            // accessibility font scale the ✕ is wider than 14dp and a fixed
            // width would slice it down the middle.
            .widthIn(min = TabPixels.EndSlotWidth)
            .fillMaxHeight()
            .then(
                if (show) {
                    Modifier
                        .semantics {
                            contentDescription = if (file.isPinned) {
                                "Unpin ${file.name}"
                            } else {
                                "Close ${file.name}"
                            }
                        }
                        // 14dp of ✕ is not a target; the strip's height caps
                        // the growth, so this widens it and leaves the bar.
                        .touchTarget()
                        .pointerHoverIcon(PointerIcon.Hand)
                        .clickable(
                            onClickLabel = if (file.isPinned) "Unpin" else "Close",
                            onClick = if (file.isPinned) onTogglePin else onClose,
                        )
                } else {
                    Modifier
                }
            ),
    ) {
        if (show) {
            Text(
                text = if (file.isPinned) "⚑" else "✕",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * The strip shown above the editor when the file underneath a buffer moved.
 *
 * It only ever appears for a *dirty* buffer or a deleted file: a clean buffer
 * whose file changed is reloaded silently, because there is nothing to lose
 * and so nothing to ask about.
 */
@Composable
fun FileConflictBar(
    file: OpenFile,
    onReload: () -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val theme = LocalZedTheme.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(rem(1f)),
        modifier = modifier
            .fillMaxWidth()
            .background(theme.color("status_bar.background"))
            .padding(horizontal = rem(0.875f), vertical = rem(0.5f)),
    ) {
        Text(
            text = if (file.isDeleted) {
                "${file.name} was deleted on disk"
            } else {
                "${file.name} changed on disk"
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        when {
            // A picture has no buffer to write back, so "Save" would be a
            // button that does nothing; the tab is all there is to close.
            file.isDeleted && file.session == null -> Unit
            file.isDeleted -> ConflictAction("Save", onSave)
            else -> ConflictAction("Reload", onReload)
        }
        ConflictAction("Dismiss", onDismiss)
    }
}

@Composable
private fun ConflictAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier
            .pointerHoverIcon(PointerIcon.Hand)
            .clickable(onClick = onClick)
            .padding(horizontal = rem(0.25f), vertical = rem(0.125f)),
    )
}
