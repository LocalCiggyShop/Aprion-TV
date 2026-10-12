package org.privateview.quietscreen.core

import java.io.IOException
import java.util.concurrent.CancellationException

enum class UpdateSource(val label: String) { RAW("GitHub raw feed"), API("GitHub API feed") }
data class UpdateFeedResult(val bytes: ByteArray, val source: UpdateSource)

/** Two fixed routes to the SAME repository/branch/file. Metadata never follows redirects. */
class UpdateFeed(owner: String, repo: String, branch: String, packageName: String) {
    init {
        require(UpdateManifest.validateRepository(owner, repo))
        require(branch.matches(Regex("[A-Za-z0-9_-]{1,64}")))
        require(packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
    }
    val raw = "https://raw.githubusercontent.com/$owner/$repo/$branch/updates/$packageName.qsu"
    val api = "https://api.github.com/repos/$owner/$repo/contents/updates/$packageName.qsu?ref=$branch"
    fun allowed(url: String) = url == raw || url == api
    fun fetch(onFallback: (IOException) -> Unit = {}, read: (String, UpdateSource) -> ByteArray): UpdateFeedResult {
        try { return UpdateFeedResult(read(raw, UpdateSource.RAW), UpdateSource.RAW) }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (failure: IOException) {
            // No fallback after metadata/signature rejection. Verification happens
            // outside this function, before interpreting any returned payload.
            if (failure is UpdateFailure && failure.problem !in setOf(UpdateProblem.DNS, UpdateProblem.CONNECTION,
                    UpdateProblem.TIMEOUT, UpdateProblem.TLS, UpdateProblem.HTTP, UpdateProblem.POLICY)) throw failure
            onFallback(failure)
        }
        return UpdateFeedResult(read(api, UpdateSource.API), UpdateSource.API)
    }
}
