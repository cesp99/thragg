package to.eyed.thragg.ui.git

import java.net.URLEncoder
import java.security.MessageDigest
import to.eyed.thragg.core.GitRemoteUrl

/**
 * The github.com half of Zed's hosting-provider registry, on foot.
 *
 * Zed parses every remote URL against a table of providers and builds commit
 * permalinks and avatar URLs from the winner (hosting_provider.rs:240-255);
 * this app talks only to github.com — the one host whose avatar CDN works
 * unauthenticated (github.rs:178-182) — so the table collapses to these
 * functions. All pure, all tested: URL parsing is exactly where a wrong split
 * turns into a broken link.
 */

/**
 * `owner/repo` out of a github.com remote URL, or null for any other host.
 *
 * The three spellings git actually writes — Zed's `parse_remote_url` accepts
 * the same set (github.rs:192-212):
 *
 *  - `https://github.com/owner/repo.git` (with an optional `user@`)
 *  - `git@github.com:owner/repo.git`
 *  - `ssh://git@github.com/owner/repo.git`
 *
 * The split is [GitRemoteUrl]'s — the same parse `GitRemote.isGithub` reads,
 * so the picker's glyph and this sidebar gate cannot disagree — and the host
 * must be github.com *exactly*: `notgithub.com`, `github.com.evil.example`
 * and an `@github.com` buried in another host's path are all somebody else.
 */
internal fun githubRepoSlug(remoteUrl: String): String? {
    val url = GitRemoteUrl.parse(remoteUrl) ?: return null
    if (url.host != "github.com") return null
    // Owner and repo are the first two segments and there must be exactly
    // two, `.git` and a trailing slash shrugged off (github.rs:203-211).
    val segments = url.path.removeSuffix("/").removeSuffix(".git").split('/')
    if (segments.size != 2 || segments.any { it.isBlank() }) return null
    return "${segments[0]}/${segments[1]}"
}

/**
 * The commit's page on github.com, or null when the remote is another host —
 * `https://github.com/{owner}/{repo}/commit/{sha}` (github.rs:214-225).
 */
internal fun githubCommitUrl(remoteUrl: String, sha: String): String? {
    val slug = githubRepoSlug(remoteUrl) ?: return null
    return "https://github.com/$slug/commit/$sha"
}

/**
 * GitHub's email→avatar CDN endpoint — the lookup that needs no API token:
 * `https://avatars.githubusercontent.com/u/e?email={email}&s=128`
 * (github.rs:75-82). Null when there is no email to ask about, and for
 * `…[bot]@users.noreply.github.com` addresses, which the CDN does not resolve
 * and Zed skips (github.rs:84-91).
 */
internal fun githubAvatarUrl(email: String): String? {
    val trimmed = email.trim().removePrefix("<").removeSuffix(">").trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.endsWith("[bot]@users.noreply.github.com")) return null
    val encoded = URLEncoder.encode(trimmed, "UTF-8")
    return "https://avatars.githubusercontent.com/u/e?email=$encoded&s=128"
}

/**
 * One author, one cache entry: the key the avatar caches file things under.
 *
 * Case-folded because mail is, angle brackets stripped because git sometimes
 * leaves them on, and hashed because an email is not a filename — `/`, `..`
 * and friends must not reach the cache directory's own namespace.
 */
internal fun avatarCacheKey(email: String): String {
    val normalized = email.trim().removePrefix("<").removeSuffix(">").trim().lowercase()
    val digest = MessageDigest.getInstance("SHA-1").digest(normalized.toByteArray())
    return digest.joinToString("") { "%02x".format(it) }
}
