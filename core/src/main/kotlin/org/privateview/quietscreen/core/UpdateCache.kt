package org.privateview.quietscreen.core

import java.io.File
import java.io.IOException
import java.util.concurrent.CancellationException

object UpdateCache {
    private val invalidApk = setOf("UPDATE_SIZE", "UPDATE_HASH", "UPDATE_ARCHIVE", "UPDATE_IDENTITY", "UPDATE_SIGNER")
    /** Reverify reusable bytes; evict damaged cache and attempt exactly one fresh download. */
    fun obtain(directory: File, bytes: ByteArray, verify: (File) -> Unit, download: (File) -> Unit,
        checkpoint: () -> Unit = {}): Boolean {
        require(bytes.isNotEmpty() && bytes.size <= UpdateManifest.MAX_ENVELOPE_BYTES) { "UPDATE_ENVELOPE" }
        val apk = File(directory, "candidate.apk")
        val envelope = File(directory, "candidate.qsu")
        val partial = File(directory, "candidate.part")
        var repaired = false
        checkpoint()
        val matching = if (envelope.length() in 1L..UpdateManifest.MAX_ENVELOPE_BYTES.toLong() && apk.exists()) {
            try { envelope.readBytes().contentEquals(bytes) } catch (_: IOException) { throw UpdateFailure(UpdateProblem.STORAGE) }
        } else false
        if (matching) {
            try { verify(apk); checkpoint(); return false }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (failure: Exception) {
                if (failure !is IllegalArgumentException && failure !is IllegalStateException || failure.message !in invalidApk) throw failure
                repaired = true
            }
        }
        if (!directory.isDirectory && !directory.mkdirs()) throw UpdateFailure(UpdateProblem.STORAGE)
        if (apk.exists() && !apk.delete() || envelope.exists() && !envelope.delete()) throw UpdateFailure(UpdateProblem.STORAGE)
        try {
            checkpoint(); download(partial); checkpoint(); verify(partial); checkpoint()
            if (!partial.renameTo(apk)) throw UpdateFailure(UpdateProblem.STORAGE)
            try { envelope.writeBytes(bytes) } catch (_: IOException) { throw UpdateFailure(UpdateProblem.STORAGE) }
            return repaired
        } catch (failure: Exception) {
            apk.delete(); envelope.delete(); throw failure
        } finally { partial.delete() }
    }
}
