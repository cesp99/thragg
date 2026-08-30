package to.eyed.seeker.code.core

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * [LaunchedEffect] for a polling loop: the block runs only while the app is
 * actually on screen.
 *
 * The engine has no callbacks into the JVM — every change arrives behind a
 * version counter something polls — and a plain [LaunchedEffect] keeps
 * polling from the background, where the reads are all for nothing and the
 * git-status counter's poll keeps scheduling real `git status` runs under
 * proot. `repeatOnLifecycle` cancels the block when the app leaves the
 * foreground and runs it again from the top on the way back.
 *
 * That restart is part of the contract: a loop's "seen" version belongs
 * *inside* the block, so coming back re-reads once and picks up whatever
 * moved while the app was away. State that must survive the restart — a
 * baseline the loop compares against rather than re-captures — belongs
 * outside, in a `remember`.
 */
@Composable
fun ResumedEffect(vararg keys: Any?, block: suspend CoroutineScope.() -> Unit) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle, *keys) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { block() }
    }
}

/**
 * The house polling loop, with its costs put where they belong: the whole
 * loop lives on [Dispatchers.Default], so the per-tick counter read — one
 * JNI call for one long — no longer pays a `withContext` round trip that
 * cost more than the read; and the main thread is touched only when the
 * counter moved and there is state to write.
 *
 * [version] and [read] run on [Dispatchers.Default] ([read] may hop to IO
 * itself when the payload is a subprocess); [apply] runs on the main thread,
 * which is where this codebase writes snapshot state. The counter is read
 * *before* the payload, so a bump that lands between the two is picked up on
 * the next tick rather than recorded against older data.
 *
 * The "seen" value lives beside the loop, never in an effect's keys — the
 * trap that re-runs a keyed effect when a version starting at zero is
 * corrected a frame later (agent-docs/CONVENTIONS.md § Traps). Loops that
 * terminate, or that gate on more than one counter, stay hand-rolled; this
 * is the shape all the others share.
 *
 * Runs until cancelled — call it under [ResumedEffect].
 */
suspend fun <T> pollVersion(
    intervalMs: Long,
    version: () -> Long,
    read: suspend (Long) -> T,
    apply: (T) -> Unit,
) {
    withContext(Dispatchers.Default) {
        var seen = Long.MIN_VALUE
        while (true) {
            val fresh = version()
            if (fresh != seen) {
                val payload = read(fresh)
                withContext(Dispatchers.Main) { apply(payload) }
                seen = fresh
            }
            delay(intervalMs)
        }
    }
}
