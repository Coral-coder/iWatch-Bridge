package com.coralcoder.iwatchbridge.ui.screens

import android.content.ComponentName
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.NotificationManagerCompat
import com.coralcoder.iwatchbridge.bridge
import com.coralcoder.iwatchbridge.core.NotificationQueue
import com.coralcoder.iwatchbridge.service.NotificationRelayService
import com.coralcoder.iwatchbridge.ui.components.ListPadding
import com.coralcoder.iwatchbridge.ui.components.ListRow
import com.coralcoder.iwatchbridge.ui.components.NavBar
import com.coralcoder.iwatchbridge.ui.components.ToggleRow
import com.coralcoder.iwatchbridge.ui.components.section

/** One toggle in a settings page: key, title, default, footer for its section. */
private data class Toggle(val key: String, val title: String, val default: Boolean)
private data class Group(val header: String?, val footer: String?, val toggles: List<Toggle>)

/** The watch settings pages, mirroring the iPhone Watch app's structure and wording. */
private val Pages: Map<String, List<Group>> = mapOf(
    "general" to listOf(
        Group(null, null, listOf(Toggle("auto_app_install", "Automatic App Install", true))),
        Group("Orientation", "Choose which wrist you wear Apple Watch on.", listOf(Toggle("wrist_left", "Left Wrist", true), Toggle("crown_right", "Digital Crown on Right", true))),
        Group(null, "Raise your wrist to wake the display.", listOf(Toggle("wake_on_raise", "Wake on Wrist Raise", true))),
    ),
    "display" to listOf(
        Group(null, "When your wrist is down, the display dims to save power.", listOf(Toggle("always_on", "Always On", true))),
        Group("Text", null, listOf(Toggle("bold_text", "Bold Text", false))),
    ),
    "accessibility" to listOf(
        Group("Vision", null, listOf(Toggle("voiceover", "VoiceOver", false), Toggle("zoom", "Zoom", false), Toggle("reduce_motion", "Reduce Motion", false), Toggle("invert_colors", "Invert Colors", false))),
        Group("Physical and Motor", null, listOf(Toggle("assistive_touch", "AssistiveTouch", false))),
    ),
    "sounds" to listOf(
        Group(null, null, listOf(Toggle("silent_mode", "Silent Mode", false), Toggle("cover_to_mute", "Cover to Mute", true))),
        Group("Haptics", null, listOf(Toggle("haptic_alerts", "Haptic Alerts", true), Toggle("prominent_haptic", "Prominent Haptic", false))),
    ),
    "passcode" to listOf(
        Group(null, "Wrist detection locks your watch when you take it off.", listOf(Toggle("wrist_detection", "Wrist Detection", true))),
        Group(null, "Unlock this watch when you unlock your phone while wearing it.", listOf(Toggle("unlock_with_phone", "Unlock with Phone", false))),
    ),
    "sos" to listOf(
        Group(null, "Press and hold the side button to call emergency services.", listOf(Toggle("hold_side_button", "Hold Side Button", true))),
        Group(null, "Apple Watch can call emergency services if it detects a hard fall.", listOf(Toggle("fall_detection", "Fall Detection", false))),
    ),
    "privacy" to listOf(Group("Motion & Fitness", null, listOf(Toggle("fitness_tracking", "Fitness Tracking", true), Toggle("heart_rate", "Heart Rate", true)))),
    "smartstack" to listOf(Group(null, null, listOf(Toggle("smart_rotate", "Smart Rotate", true), Toggle("live_activities", "Live Activities", true)))),
    "apps" to listOf(Group("App View", null, listOf(Toggle("grid_view", "Grid View", true)))),
    "clock" to listOf(Group(null, null, listOf(Toggle("time_24h", "24-Hour Time", false), Toggle("push_alerts_from_phone", "Push Alerts from Phone", true)))),
    "battery" to listOf(Group(null, "Low Power Mode turns off the Always On display and limits background activity.", listOf(Toggle("low_power", "Low Power Mode", false), Toggle("optimized_charging", "Optimized Charge Limit", true)))),
    "activity" to listOf(Group(null, null, listOf(Toggle("stand_reminders", "Stand Reminders", true), Toggle("daily_coaching", "Daily Coaching", true), Toggle("goal_completions", "Goal Completions", true)))),
    "heart" to listOf(Group("Notifications", null, listOf(Toggle("high_hr", "High Heart Rate", true), Toggle("low_hr", "Low Heart Rate", true), Toggle("irregular_rhythm", "Irregular Rhythm", false)))),
    "workout" to listOf(Group(null, null, listOf(Toggle("start_reminder", "Start Workout Reminder", true), Toggle("end_reminder", "End Workout Reminder", true), Toggle("auto_pause", "Running Auto Pause", false)))),
    "wallet" to listOf(Group(null, "Apple Pay needs Apple's servers and card provisioning, which this app can't do yet.", listOf(Toggle("double_click", "Double-Click Side Button", true)))),
)

@Composable
fun SettingsDetailScreen(key: String, onBack: () -> Unit) {
    val entry = (SystemSettings + ExperienceSettings + AppSettings).firstOrNull { it.key == key }
    val title = entry?.title ?: key
    if (key == "notifications") { NotificationsScreen(onBack); return }
    val bridge = LocalContext.current.bridge
    val watchId = bridge.registry.state.collectAsState().value.activeId ?: return
    val values by bridge.settings.values.collectAsState()
    val pending by bridge.settings.pending.collectAsState()
    Column(Modifier.fillMaxSize()) {
        NavBar(title, back = "My Watch", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
            Pages[key].orEmpty().forEach { g ->
                section(header = g.header, footer = g.footer, rows = g.toggles.map { t ->
                    @Composable {
                        val k = "$watchId/${t.key}"
                        ToggleRow(t.title, values[k] ?: t.default, subtitle = if (k in pending) "Syncs when your watch connects" else null) {
                            bridge.settings.set(watchId, t.key, it)
                        }
                    }
                })
            }
        }
    }
}

@Composable
private fun NotificationsScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val granted = NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)
    val queued by NotificationQueue.items.collectAsState()
    val muted by NotificationQueue.mutedApps.collectAsState()
    val apps = queued.map { it.app to it.appName }.distinct()
    Column(Modifier.fillMaxSize()) {
        NavBar("Notifications", back = "My Watch", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
            section(
                footer = "Notifications from this phone are shown on Apple Watch when you're wearing it and your phone is locked.",
                rows = listOf {
                    ListRow(
                        "Mirror Phone Alerts", value = if (granted) "On" else "Off",
                        onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS)
                                .putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, ComponentName(ctx, NotificationRelayService::class.java).flattenToString())
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                        },
                    )
                },
            )
            if (apps.isNotEmpty()) {
                section(header = "Mirror alerts from", rows = apps.map { (pkg, name) ->
                    @Composable { ToggleRow(name, pkg !in muted) { on -> NotificationQueue.mutedApps.value = if (on) muted - pkg else muted + pkg } }
                })
            }
        }
    }
}
