package to.eyed.thragg.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.runtime.Composable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * The back gesture: one ordered handler, replacing the eight-branch chain at
 * WorkspaceScreen.kt:3608-3650.
 *
 * The order is the specification (docs/UI.md, "Navigation" — BACK GESTURE) and
 * it is total: every press resolves to exactly one of eight steps, and the
 * eighth is "leave the app". Written as a pure function over a value
 * ([backStep] over [BackContext]) so the order is a thing a host test can
 * assert step by step rather than a `when` buried in a composable — see
 * ShellBackHandlerTest, which pins all eight.
 *
 * What the old chain had and this does not: closing a dock (there are none),
 * unfocusing the terminal (Shell is a mode of Build, and back from it goes to
 * Code with the mode remembered), and dismissing a tab switcher (there is no
 * tab strip and no Ctrl+Tab).
 */

/** A back seam a destination registers with [ShellState]: is there something to close, and close it. */
class BackSeam(
    /**
     * Whether this seam would consume a back press. Read *during composition*
     * by [backContext], which is deliberate: the read subscribes the shell to
     * the destination's own snapshot state, so `BackHandler(enabled = …)` is
     * re-evaluated the moment a completion menu opens or the find bar closes.
     */
    val isActive: () -> Boolean,
    /** Close it. Only ever called when [isActive] just said true. */
    val consume: () -> Unit,
)

/** The eight steps, in order. */
enum class BackStep {
    /** 1. A completion menu, hover card, selection toolbar or code-action popup. */
    DismissOverlay,

    /** 2. The topmost sheet or dialog. */
    CloseSheet,

    /** 3. The IME. In Code the action row's state is left alone. */
    HideIme,

    /** 4. The find bar, and its match highlights with it. */
    CloseFindBar,

    /** 5. A full-screen route on the current destination's stack. */
    PopRoute,

    /** 6. In Code, one entry off the jump stack — OpenFiles.kt's NavHistory. */
    PopJump,

    /** 7. Not on Code → go to Code. */
    GoToCode,

    /** 8. In Code at the root → leave the app. */
    LeaveApp,
}

/** The eight facts the order is decided on, as plain values. */
data class BackContext(
    val overlayShowing: Boolean = false,
    val sheetOpen: Boolean = false,
    val imeVisible: Boolean = false,
    val findBarOpen: Boolean = false,
    val routePushed: Boolean = false,
    val jumpAvailable: Boolean = false,
    val destination: Destination = Destination.Code,
    /**
     * The route on top is the toolchain gate and the toolchain is not in.
     * Back does not pop it — there is nothing usable underneath — and goes
     * to the launcher instead, which is the honest reading of "I'm not doing
     * this now": the install keeps running under the foreground service and
     * the gate is there again when the app comes back.
     */
    val gated: Boolean = false,
)

/**
 * Which step a back press resolves to. Pure, total, and the only place the
 * order is written down.
 *
 * Two orderings in here are load-bearing and were argued for in the spec
 * rather than chosen for convenience. The IME comes *third*, above the find
 * bar and above popping a route: with the keyboard up the screen the user is
 * looking at is half keyboard, and the first back has to give the screen back.
 * The toolchain gate sits just above the route pop: an overlay, a sheet or the
 * keyboard on top of Setup still closes first, but the gate itself is never
 * popped by back while the toolchain is missing — the press leaves the app.
 * The jump stack comes *after* the route stack and only in Code, so following
 * a go-to-definition out of a diff and pressing back returns to the diff — the
 * surface you came from — before it starts walking the caret backwards.
 */
fun backStep(context: BackContext): BackStep = when {
    context.overlayShowing -> BackStep.DismissOverlay
    context.sheetOpen -> BackStep.CloseSheet
    context.imeVisible -> BackStep.HideIme
    context.findBarOpen -> BackStep.CloseFindBar
    context.gated -> BackStep.LeaveApp
    context.routePushed -> BackStep.PopRoute
    context.destination == Destination.Code && context.jumpAvailable -> BackStep.PopJump
    context.destination != Destination.Code -> BackStep.GoToCode
    else -> BackStep.LeaveApp
}

/**
 * The shell's state as a [BackContext]. [imeVisible] is passed in because
 * `WindowInsets.isImeVisible` is only readable from a composition and this is
 * where the tests get in.
 */
fun ShellState.backContext(imeVisible: Boolean): BackContext = BackContext(
    overlayShowing = overlaySeam?.isActive?.invoke() == true,
    sheetOpen = sheetCount > 0,
    imeVisible = imeVisible,
    findBarOpen = findBarSeam?.isActive?.invoke() == true,
    routePushed = !currentStack.isEmpty,
    jumpAvailable = jumpSeam?.isActive?.invoke() == true,
    destination = destination,
    gated = isGated,
)

/**
 * Run one back press. Returns the step taken, having already done it —
 * except for [BackStep.LeaveApp], which is the caller's business (the shell
 * does not register a handler at all in that state, so the gesture reaches
 * the system and backgrounds the app).
 *
 * No confirm on the way out. Autosave-on-leaving-a-file is on by default
 * (docs/UI.md, P8), so leaving is never destructive, and a dialog asking
 * whether you meant it is the thing that makes a back gesture feel unsafe.
 */
fun ShellState.handleBack(imeVisible: Boolean, hideIme: () -> Unit): BackStep {
    val step = backStep(backContext(imeVisible))
    when (step) {
        BackStep.DismissOverlay -> overlaySeam?.consume?.invoke()
        BackStep.CloseSheet -> dismissTopSheet()
        BackStep.HideIme -> hideIme()
        BackStep.CloseFindBar -> findBarSeam?.consume?.invoke()
        BackStep.PopRoute -> pop()
        BackStep.PopJump -> jumpSeam?.consume?.invoke()
        BackStep.GoToCode -> show(Destination.Code)
        BackStep.LeaveApp -> Unit
    }
    return step
}

/**
 * Hosts the one handler. Composed once, at the shell's root.
 *
 * `enabled` is the whole reason the step is computed twice — once here to
 * decide whether to take the gesture at all, and once inside to act on it. A
 * handler that took the press and did nothing would trap the user in the app;
 * one that was never registered while a sheet was up would put the launcher
 * over the IDE, which is the bug the old chain's comment describes.
 *
 * [rootFocus] is the focus-restoration half, carried across from
 * WorkspaceScreen.kt:3645. Compose hands focus *nowhere* when the composable
 * holding it leaves — measured on the Fold, dismissing an overlay left the
 * whole key path dead — so every step that removes a surface puts focus back
 * on the shell's root. Two steps deliberately do not: [BackStep.HideIme],
 * because moving focus would end the IME session the user is still in and take
 * the editor's action row with it (docs/UI.md: "and, in Code, leave the action
 * row's state alone"), and [BackStep.PopJump], because the jump it just
 * restored ends with the caret in an editor that has to keep the keyboard.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ShellBackHandler(state: ShellState, rootFocus: FocusRequester) {
    val imeVisible = WindowInsets.isImeVisible
    val keyboard = LocalSoftwareKeyboardController.current
    val step = backStep(state.backContext(imeVisible))
    BackHandler(enabled = step != BackStep.LeaveApp) {
        val taken = state.handleBack(imeVisible) { keyboard?.hide() }
        if (taken != BackStep.HideIme && taken != BackStep.PopJump) {
            // Guarded: the root is focusable but a FocusRequester that has not
            // been attached yet throws, and back can arrive during the first
            // frame after a rotation.
            runCatching { rootFocus.requestFocus() }
        }
    }
}
