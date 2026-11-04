package com.coralcoder.iwatchbridge

import android.content.Intent
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coralcoder.iwatchbridge.protocol.registry.WatchRecord
import com.coralcoder.iwatchbridge.service.BridgeService
import com.coralcoder.iwatchbridge.ui.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The app must open cleanly on a fresh install (no permissions granted) and with watches paired. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35])
class StartupTest {
    private val app get() = ApplicationProvider.getApplicationContext<BridgeApp>()

    @Test fun freshInstallOpens() {
        ActivityScenario.launch(MainActivity::class.java).use { s ->
            s.onActivity { assertNotNull(it.window.decorView) }
        }
    }

    @Test fun opensWithAPairedWatchAndStartsTheService() {
        app.controller.registry.upsert(WatchRecord("w", "Watch"))
        ActivityScenario.launch(MainActivity::class.java).use { }
        val started = org.robolectric.Shadows.shadowOf(app).nextStartedService
        assertEquals(BridgeService::class.java.name, started?.component?.className)
        // The service itself must come up without throwing.
        Robolectric.buildService(BridgeService::class.java, Intent(app, BridgeService::class.java)).create().get()
    }

    @Test fun reportsThePreviousCrashOnce() {
        java.io.File(app.filesDir, "last_crash.txt").writeText("boom")
        assertEquals("boom", com.coralcoder.iwatchbridge.core.CrashLog.take(app))
        assertEquals(null, com.coralcoder.iwatchbridge.core.CrashLog.take(app))
    }
}
