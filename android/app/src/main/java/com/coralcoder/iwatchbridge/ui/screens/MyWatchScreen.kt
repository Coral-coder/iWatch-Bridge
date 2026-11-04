package com.coralcoder.iwatchbridge.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccessibilityNew
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.BrightnessHigh
import androidx.compose.material.icons.filled.DirectionsRun
import androidx.compose.material.icons.filled.Emergency
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.PanTool
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Timelapse
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Wallet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.coralcoder.iwatchbridge.bridge
import com.coralcoder.iwatchbridge.protocol.registry.WatchRecord
import com.coralcoder.iwatchbridge.ui.components.FootnoteText
import com.coralcoder.iwatchbridge.ui.components.IconTile
import com.coralcoder.iwatchbridge.ui.components.LargeTitle
import com.coralcoder.iwatchbridge.ui.components.ListPadding
import com.coralcoder.iwatchbridge.ui.components.ListRow
import com.coralcoder.iwatchbridge.ui.components.PrimaryButton
import com.coralcoder.iwatchbridge.ui.components.VSpace
import com.coralcoder.iwatchbridge.ui.components.WatchIllustration
import com.coralcoder.iwatchbridge.ui.components.section
import com.coralcoder.iwatchbridge.ui.theme.Ios

/** A settings entry on My Watch: key (route), title, icon and tile colour, as on iPhone. */
data class SettingEntry(val key: String, val title: String, val icon: ImageVector, val color: Color)

val SystemSettings = listOf(
    SettingEntry("general", "General", Icons.Filled.Settings, Color(0xFF8E8E93)),
    SettingEntry("display", "Display & Brightness", Icons.Filled.BrightnessHigh, Color(0xFF007AFF)),
    SettingEntry("accessibility", "Accessibility", Icons.Filled.AccessibilityNew, Color(0xFF007AFF)),
    SettingEntry("sounds", "Sounds & Haptics", Icons.Filled.VolumeUp, Color(0xFFFF2D55)),
    SettingEntry("passcode", "Passcode", Icons.Filled.Lock, Color(0xFFFF3B30)),
    SettingEntry("sos", "Emergency SOS", Icons.Filled.Emergency, Color(0xFFFF3B30)),
    SettingEntry("privacy", "Privacy & Security", Icons.Filled.PanTool, Color(0xFF007AFF)),
)
val ExperienceSettings = listOf(
    SettingEntry("notifications", "Notifications", Icons.Filled.Notifications, Color(0xFFFF3B30)),
    SettingEntry("smartstack", "Smart Stack", Icons.Filled.ViewModule, Color(0xFF5856D6)),
    SettingEntry("apps", "App View", Icons.Filled.Apps, Color(0xFF5856D6)),
    SettingEntry("clock", "Clock", Icons.Filled.AccessTime, Color(0xFF1C1C1E)),
    SettingEntry("battery", "Battery", Icons.Filled.BatteryFull, Color(0xFF34C759)),
)
val AppSettings = listOf(
    SettingEntry("activity", "Activity", Icons.Filled.Timelapse, Color(0xFF1C1C1E)),
    SettingEntry("heart", "Heart", Icons.Filled.Favorite, Color(0xFFFF2D55)),
    SettingEntry("workout", "Workout", Icons.Filled.DirectionsRun, Color(0xFF92E82A)),
    SettingEntry("wallet", "Wallet & Apple Pay", Icons.Filled.Wallet, Color(0xFF1C1C1E)),
)

@Composable
fun MyWatchScreen(onAllWatches: () -> Unit, onPair: () -> Unit, onSetting: (String) -> Unit) {
    val ctx = LocalContext.current
    val reg by ctx.bridge.registry.state.collectAsState()
    val active = reg.active
    if (active == null) {
        StartPairing(onPair, onShared = { onSetting("shared") })
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
        item { LargeTitle("My Watch") }
        item { WatchHeader(active) }
        section(rows = listOf {
            ListRow("All Watches", value = if (reg.watches.size > 1) "${reg.watches.size}" else null, onClick = onAllWatches)
        })
        listOf(SystemSettings, ExperienceSettings, AppSettings).forEach { group ->
            section(rows = group.map { e -> @Composable { ListRow(e.title, icon = { IconTile(e.icon, e.color) }, onClick = { onSetting(e.key) }) } })
        }
    }
}

@Composable
private fun WatchHeader(w: WatchRecord) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        WatchIllustration(Modifier.size(150.dp), ultra = w.model.contains("Ultra"))
        VSpace(8.dp)
        Text(w.name, style = MaterialTheme.typography.titleLarge, color = Ios.colors.label)
        val sub = listOf(if (w.mode == com.coralcoder.iwatchbridge.protocol.registry.LinkMode.SHARED) "Shared with iPhone" else "", w.model, if (w.caseSizeMm > 0) "${w.caseSizeMm}mm" else "", if (w.osVersion.isNotEmpty()) "watchOS ${w.osVersion}" else "")
            .filter { it.isNotEmpty() }.joinToString(" · ")
        if (sub.isNotEmpty()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = Ios.colors.secondaryLabel)
    }
}

/** Shown when no watch is paired: the iPhone's "Start Pairing" screen. */
@Composable
private fun StartPairing(onPair: () -> Unit, onShared: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(24.dp)) {
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            WatchIllustration(Modifier.size(180.dp))
            VSpace(16.dp)
            Text("Apple Watch", style = MaterialTheme.typography.displaySmall, color = Ios.colors.label)
            FootnoteText("Set up your Apple Watch with this phone, just like with iPhone.", Modifier.padding(horizontal = 24.dp))
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            PrimaryButton("Start Pairing", onClick = onPair)
            com.coralcoder.iwatchbridge.ui.components.TextButton("Use a Watch Paired to My iPhone", onClick = onShared)
        }
    }
}
