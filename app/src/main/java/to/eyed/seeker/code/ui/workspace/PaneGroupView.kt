package to.eyed.seeker.code.ui.workspace

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import to.eyed.seeker.code.ui.theme.LocalZedTheme
import to.eyed.seeker.code.ui.theme.ZedRadius
import to.eyed.seeker.code.ui.theme.rem
import kotlin.math.roundToInt

/**
 * The grip between two panes: the same 6dp as the dock handles, on Zed's
 * 1px `pane_group.border` line (pane_group.rs:1322-1348 lays the handle
 * over the divider).
 */
private val PaneHandleWidth = 6.dp

/**
 * The pane tree drawn — Zed's `PaneGroup::render` (pane_group.rs:230-241)
 * over `Member::render` and `PaneAxis::render`: a leaf is a pane, an axis
 * is a row or a column of its members at their flexes, with a draggable
 * handle between neighbours. A zoomed pane is drawn alone
 * (`render` takes `zoomed` and skips the rest, pane_group.rs:532-560).
 *
 * What each leaf *contains* is [leaf]'s business; this composable owns the
 * chrome around it: reporting where the pane is (for the direction
 * commands and for drops), the press that activates it, the border on the
 * active one, the drop highlight, and the name that follows a dragged tab.
 */
@Composable
internal fun PaneGroupView(
    panes: PaneGroupState,
    onActivate: (Pane) -> Unit,
    modifier: Modifier = Modifier,
    leaf: @Composable (Pane) -> Unit,
) {
    var areaOrigin by remember { mutableStateOf(Offset.Zero) }
    Box(modifier = modifier.fillMaxSize().onGloballyPositioned { areaOrigin = it.positionInRoot() }) {
        val zoomed = panes.zoomedPaneId?.let(panes::paneById)
        if (zoomed != null) {
            PaneLeaf(zoomed, panes, onActivate, Modifier.fillMaxSize(), leaf)
        } else {
            PaneTree(panes.root, panes, onActivate, Modifier.fillMaxSize(), leaf)
        }
        // The dragged tab's name under the pointer — Zed drags a rendering
        // of the tab itself (`DraggedTab`, pane.rs:2950); a label is what
        // survives a finger covering it.
        val drag = panes.drag
        if (drag != null) {
            val theme = LocalZedTheme.current
            Text(
                text = drag.file.name,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (drag.position.x - areaOrigin.x).roundToInt() + 16.dp.roundToPx(),
                            (drag.position.y - areaOrigin.y).roundToInt() - 16.dp.roundToPx(),
                        )
                    }
                    .clip(RoundedCornerShape(rem(ZedRadius.SM)))
                    .background(theme.color("elevated_surface.background"))
                    .padding(horizontal = rem(0.5f), vertical = rem(0.25f)),
            )
        }
    }
}

@Composable
private fun PaneTree(
    member: PaneMember,
    panes: PaneGroupState,
    onActivate: (Pane) -> Unit,
    modifier: Modifier,
    leaf: @Composable (Pane) -> Unit,
) {
    when (member) {
        is Pane -> PaneLeaf(member, panes, onActivate, modifier, leaf)
        is PaneAxisNode -> PaneAxisView(member, panes, onActivate, modifier, leaf)
    }
}

/**
 * One axis: `PaneAxis::render` (pane_group.rs:968-1046). Each member gets
 * its flex as a weight; the handle after member *i* resizes *i* against
 * *i + 1*, in pixels of this axis's own length, as Zed's `compute_resize`
 * measures it.
 */
@Composable
private fun PaneAxisView(
    axis: PaneAxisNode,
    panes: PaneGroupState,
    onActivate: (Pane) -> Unit,
    modifier: Modifier,
    leaf: @Composable (Pane) -> Unit,
) {
    var containerPx by remember { mutableFloatStateOf(0f) }
    val members = axis.memberList
    val flexes = axis.flexList
    if (axis.axis == PaneAxis.Horizontal) {
        Row(modifier = modifier.onSizeChanged { containerPx = it.width.toFloat() }) {
            members.forEachIndexed { index, member ->
                if (index > 0) {
                    PaneHandle(vertical = true) { delta -> panes.resize(axis, index - 1, delta, containerPx) }
                }
                key(keyOf(member)) {
                    PaneTree(
                        member, panes, onActivate,
                        Modifier.weight(flexes.getOrElse(index) { 1f }.coerceAtLeast(0.01f)).fillMaxHeight(),
                        leaf,
                    )
                }
            }
        }
    } else {
        Column(modifier = modifier.onSizeChanged { containerPx = it.height.toFloat() }) {
            members.forEachIndexed { index, member ->
                if (index > 0) {
                    PaneHandle(vertical = false) { delta -> panes.resize(axis, index - 1, delta, containerPx) }
                }
                key(keyOf(member)) {
                    PaneTree(
                        member, panes, onActivate,
                        Modifier.weight(flexes.getOrElse(index) { 1f }.coerceAtLeast(0.01f)).fillMaxWidth(),
                        leaf,
                    )
                }
            }
        }
    }
}

/** A pane by its id, an axis by identity: what keeps a swap from remounting the editors. */
private fun keyOf(member: PaneMember): Any = when (member) {
    is Pane -> member.id
    is PaneAxisNode -> System.identityHashCode(member)
}

/**
 * The grip between two members. [vertical] is the *line's* orientation:
 * a vertical line stands between two columns and moves sideways.
 */
@Composable
private fun PaneHandle(vertical: Boolean, onResize: (Float) -> Unit) {
    val border = LocalZedTheme.current.color("pane_group.border")
    Box(
        modifier = Modifier
            .then(if (vertical) Modifier.width(PaneHandleWidth).fillMaxHeight() else Modifier.height(PaneHandleWidth).fillMaxWidth())
            .pointerHoverIcon(PointerIcon.Crosshair)
            .pointerInput(vertical) {
                if (vertical) {
                    detectHorizontalDragGestures { _, delta -> onResize(delta) }
                } else {
                    detectVerticalDragGestures { _, delta -> onResize(delta) }
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .then(if (vertical) Modifier.width(1.dp).fillMaxHeight() else Modifier.height(1.dp).fillMaxWidth())
                .background(border),
        )
    }
}

/**
 * One pane's frame. Three things happen here that the content need not
 * know about:
 *
 * - its bounds are reported, in root coordinates, for
 *   [PaneGroupState.findPaneInDirection] and the drop target;
 * - a press anywhere in it makes it the active pane — taken in the
 *   initial pass so the tab or button under the finger then acts on a
 *   pane that is already active, the way Zed's `Event::Focus` runs before
 *   the click (workspace.rs:5942-5946);
 * - the active pane, when there is more than one, is outlined in
 *   `border.selected` — Zed's `active_pane_modifiers.border_size`
 *   (pane_group.rs:1489-1531), which Zed leaves at 0 because a desktop has
 *   a caret to show focus with; a tablet with the keyboard down does not,
 *   so it is 1px here. The drop highlight is Zed's `drop_target.background`
 *   over the half of the pane a split would take, or all of it for a join.
 */
@Composable
private fun PaneLeaf(
    pane: Pane,
    panes: PaneGroupState,
    onActivate: (Pane) -> Unit,
    modifier: Modifier,
    leaf: @Composable (Pane) -> Unit,
) {
    val theme = LocalZedTheme.current
    val density = LocalDensity.current
    val tabBarPx = with(density) { TabBarHeight.toPx() }
    val hasTabs = pane.files.tabs.isNotEmpty()
    val isActive = panes.active === pane
    val outlined = isActive && panes.panes.size > 1
    val outline = theme.color("border.selected", theme.color("text.accent"))
    val dropFill = theme.color("drop_target.background", theme.color("text.accent").copy(alpha = 0.2f))
    val target = panes.dropTarget?.takeIf { it.paneId == pane.id }
    DisposableEffect(pane) {
        onDispose { panes.forgetBounds(pane) }
    }
    Box(
        modifier = modifier
            .onGloballyPositioned { coordinates ->
                val rect = coordinates.boundsInRoot()
                panes.recordBounds(pane, rect, rect.top + if (hasTabs) tabBarPx else 0f)
            }
            .pointerInput(pane) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Press) onActivate(pane)
                    }
                }
            }
            .drawWithContent {
                drawContent()
                if (target != null) {
                    val (offset, extent) = when (target.direction) {
                        null -> Offset.Zero to size
                        SplitDirection.Left -> Offset.Zero to Size(size.width / 2f, size.height)
                        SplitDirection.Right -> Offset(size.width / 2f, 0f) to Size(size.width / 2f, size.height)
                        SplitDirection.Up -> Offset.Zero to Size(size.width, size.height / 2f)
                        SplitDirection.Down -> Offset(0f, size.height / 2f) to Size(size.width, size.height / 2f)
                    }
                    drawRect(dropFill, topLeft = offset, size = extent)
                }
                if (outlined) {
                    val stroke = 1.dp.toPx()
                    drawRect(
                        outline,
                        topLeft = Offset(stroke / 2f, stroke / 2f),
                        size = Size(size.width - stroke, size.height - stroke),
                        style = Stroke(stroke),
                    )
                }
            },
    ) {
        leaf(pane)
    }
}
