package to.eyed.seeker.code.terminal

import android.content.Context

/**
 * The `play` flavour's answer: there is no userland.
 *
 * This build targets a modern SDK to stay distributable on Google Play, and
 * Android will not execute a program that was downloaded rather than
 * installed. The terminal still works — it runs Android's own shell, which is
 * mksh, with toybox's ~210 commands on `PATH` — but `apt` cannot exist here.
 */
internal fun createUserlandBackend(): UserlandBackend = NoUserland

private object NoUserland : UserlandBackend {
    override val isSupported = false
    override val displayName = "none"
    override val downloadDescription: String? = null

    override fun state(context: Context) = UserlandState.Unsupported

    override fun shellCommand(context: Context, projectDir: String): ShellCommand? = null

    /**
     * There is no guest to run anything in, so every caller — clone included —
     * sees null and leaves its feature out of the UI entirely.
     */
    override fun execCommand(
        context: Context,
        hostWorkingDir: String?,
        argv: List<String>,
        extraEnvironment: List<String>,
    ): ShellCommand? = null

    override fun install(
        context: Context,
        isActive: () -> Boolean,
        onProgress: (String, Float?) -> Unit,
    ): Result<Unit> =
        Result.failure(UnsupportedOperationException("This build has no userland support"))

    override fun remove(context: Context) = Unit
}
