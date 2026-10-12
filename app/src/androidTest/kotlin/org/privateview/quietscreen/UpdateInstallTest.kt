package org.privateview.quietscreen

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import java.io.File
import org.privateview.quietscreen.updates.UpdateManager

/** Self replacement terminates instrumentation. Verify fixture version 8 externally, then restore the actual app. */
class UpdateInstallTest {
    @Test fun installsVerifiedSelfUpdateOnOwnedEmulator() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        context.getSharedPreferences("updates", android.content.Context.MODE_PRIVATE).edit().putBoolean("automatic", false).commit()
        val directory = File(context.cacheDir, "updates").apply { mkdirs() }
        listOf("update-valid.apk" to "candidate.apk", "update-valid.qsu" to "candidate.qsu").forEach { (asset, destination) ->
            instrumentation.context.assets.open(asset).use { input -> File(directory, destination).outputStream().use { input.copyTo(it) } }
        }
        instrumentation.startActivitySync(Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        instrumentation.runOnMainSync {
            val updates = UpdateManager.get(context)
            updates.automatic(false)
            updates.enterForeground()
            updates.install()
        }
        Thread.sleep(20000)
        throw AssertionError("Expected Android to replace the running process; inspect installer result")
    }
}
