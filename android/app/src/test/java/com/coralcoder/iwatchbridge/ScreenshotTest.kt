package com.coralcoder.iwatchbridge

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.coralcoder.iwatchbridge.protocol.registry.LinkMode
import com.coralcoder.iwatchbridge.protocol.registry.WatchRecord
import com.coralcoder.iwatchbridge.ui.screens.AllWatchesScreen
import com.coralcoder.iwatchbridge.ui.screens.BridgeScreen
import com.coralcoder.iwatchbridge.ui.screens.FaceGalleryScreen
import com.coralcoder.iwatchbridge.ui.screens.MyWatchScreen
import com.coralcoder.iwatchbridge.ui.screens.SettingsDetailScreen
import com.coralcoder.iwatchbridge.ui.theme.BridgeTheme
import com.coralcoder.iwatchbridge.ui.theme.Ios
import com.github.takahirom.roborazzi.captureRoboImage
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Renders the main screens to PNGs (app/build/screenshots) so UI changes can be reviewed without
 * a device. Also checks they compose without crashing.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "w393dp-h852dp-xxhdpi")
class ScreenshotTest {
    private val bridge get() = ApplicationProvider.getApplicationContext<BridgeApp>().controller

    private fun shot(name: String, dark: Boolean = false, content: @Composable () -> Unit) =
        captureRoboImage("build/screenshots/$name.png") {
            BridgeTheme(dark = dark) { Box(Modifier.fillMaxSize().background(Ios.colors.groupedBackground)) { content() } }
        }

    private fun pairTwo() {
        bridge.registry.upsert(WatchRecord("u", "Dana's Apple Watch", model = "Apple Watch Ultra 2", osVersion = "26.1", caseSizeMm = 49))
        bridge.registry.upsert(WatchRecord("s", "Dana's Series 10", model = "Apple Watch Series 10", osVersion = "26.1", caseSizeMm = 46, mode = LinkMode.SHARED))
    }

    @Test fun startPairing() = shot("01-start") { MyWatchScreen({}, {}, {}) }
    @Test fun myWatch() { pairTwo(); shot("02-my-watch") { MyWatchScreen({}, {}, {}) } }
    @Test fun myWatchDark() { pairTwo(); shot("03-my-watch-dark", dark = true) { MyWatchScreen({}, {}, {}) } }
    @Test fun allWatches() { pairTwo(); shot("04-all-watches") { AllWatchesScreen({}, {}, {}) } }
    @Test fun general() { pairTwo(); shot("05-general") { SettingsDetailScreen("general") {} } }
    @Test fun sharedSetup() = shot("06-shared-setup") { SettingsDetailScreen("shared") {} }
    @Test fun faces() = shot("07-face-gallery") { FaceGalleryScreen() }
    @Test fun bridgeTab() = shot("08-bridge") { BridgeScreen() }
}
