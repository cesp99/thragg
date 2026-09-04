package to.eyed.thragg.core

/**
 * A git remote URL split into the two parts anything ever asks about: the
 * host, and the path under it.
 *
 * Zed wraps `url::Url` plus one regex for the scp spelling (remote.rs:16-31)
 * and compares `host_str()` exactly (github.rs:192-198); this is that parse,
 * on foot, and the **one** place a remote URL's host is ever read — the
 * branch picker's glyph ([GitRemote.isGithub]) and the graph sidebar's
 * permalink and avatars (`githubRepoSlug`) must agree on what a github.com
 * remote is, or the same remote wears the GitHub glyph in one place and not
 * the other.
 *
 * The point of parsing properly is the `@`: RFC 3986 userinfo cannot contain
 * `/`, so an `@` in the *path* —
 * `https://git.evil.example/mirror/x@github.com/a/b` — must not move the
 * host, which a naive substring-after-`@` did.
 */
internal data class GitRemoteUrl(val host: String, val path: String) {
    internal companion object {
        /**
         * The scp spelling's `user@` prefix: anything but the `@`/`:`/`/`
         * that delimit the user, host and path — Zed's `USERNAME_REGEX`
         * (remote.rs:16-17), matched by exclusion so `first.last@` counts.
         */
        private val SCP_USER = Regex("^[^/@:]+@")

        /**
         * The spellings git actually writes into `.git/config`:
         *
         *  - `scheme://[user@]host[:port]/path` — https, ssh, git, anything;
         *  - `user@host:path` — the scp shorthand;
         *  - bare `host/path`, which git also takes.
         *
         * Null when [remoteUrl] has no readable host — a local path, say.
         */
        fun parse(remoteUrl: String): GitRemoteUrl? {
            val url = remoteUrl.trim()
            val schemeEnd = url.indexOf("://")
            if (schemeEnd > 0) {
                // A URL proper. The authority ends at the first '/', and only
                // an '@' *inside the authority* marks a user — one later in
                // the path belongs to the path.
                val rest = url.substring(schemeEnd + 3)
                val slash = rest.indexOf('/')
                val authority = if (slash >= 0) rest.take(slash) else rest
                // After the user (if any), before the port (if any).
                val host = authority.substringAfterLast('@').substringBefore(':')
                if (host.isEmpty()) return null
                return GitRemoteUrl(host, if (slash >= 0) rest.substring(slash + 1) else "")
            }
            if (SCP_USER.containsMatchIn(url)) {
                // The scp spelling: the host runs from the '@' to the ':',
                // and everything after the colon is the path — Zed rewrites
                // exactly this shape to `ssh://` (remote.rs:22-28).
                val afterUser = url.substringAfter('@')
                val colon = afterUser.indexOf(':')
                if (colon <= 0) return null
                val host = afterUser.take(colon)
                if (host.contains('/')) return null
                return GitRemoteUrl(host, afterUser.substring(colon + 1))
            }
            // Bare `host/path`, with nothing user- or port-shaped in the host.
            val slash = url.indexOf('/')
            if (slash > 0) {
                val host = url.take(slash)
                if (host.none { it == '@' || it == ':' }) {
                    return GitRemoteUrl(host, url.substring(slash + 1))
                }
            }
            return null
        }
    }
}
