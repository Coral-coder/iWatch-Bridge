package com.coralcoder.iwatchbridge.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.coralcoder.iwatchbridge.bridge
import com.coralcoder.iwatchbridge.protocol.registry.WatchRecord
import com.coralcoder.iwatchbridge.ui.components.ListPadding
import com.coralcoder.iwatchbridge.ui.components.ListRow
import com.coralcoder.iwatchbridge.ui.components.NavBar
import com.coralcoder.iwatchbridge.ui.components.ToggleRow
import com.coralcoder.iwatchbridge.ui.components.WatchIllustration
import com.coralcoder.iwatchbridge.ui.components.section
import com.coralcoder.iwatchbridge.ui.theme.Ios
import java.text.DateFormat
import java.util.Date

/**
 * The iPhone's All Watches screen: every paired watch, which one is active, Auto Switch, and
 * Add Watch. Tap a watch to make it active; tap (i) for its details and Unpair.
 */
@Composable
fun AllWatchesScreen(onBack: () -> Unit, onAdd: () -> Unit, onInfo: (String) -> Unit) {
    val bridge = LocalContext.current.bridge
    val reg by bridge.registry.state.collectAsState()
    Column(Modifier.fillMaxSize()) {
        NavBar("All Watches", back = "My Watch", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
            section(
                header = "My Watches",
                rows = reg.watches.filter { it.familyMember == null }.map { w ->
                    @Composable { WatchRow(w, w.id == reg.activeId, onSelect = { bridge.registry.setActive(w.id) }, onInfo = { onInfo(w.id) }) }
                } + listOf<@Composable () -> Unit>({ ListRow("Add Watch", tint = true, chevron = false, onClick = onAdd) }),
            )
            val family = reg.watches.filter { it.familyMember != null }
            if (family.isNotEmpty()) {
                section(header = "Family Watches", rows = family.map { w ->
                    @Composable { WatchRow(w, false, onSelect = { onInfo(w.id) }, onInfo = { onInfo(w.id) }) }
                })
            }
            section(
                footer = "When Auto Switch is on, the watch you're wearing and have unlocked becomes your active watch.",
                rows = listOf { ToggleRow("Auto Switch", reg.autoSwitch) { bridge.registry.setAutoSwitch(it) } },
            )
        }
    }
}

@Composable
private fun WatchRow(w: WatchRecord, active: Boolean, onSelect: () -> Unit, onInfo: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onSelect).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        WatchIllustration(Modifier.size(52.dp), ultra = w.model.contains("Ultra"), showRings = false)
        Column(Modifier.weight(1f)) {
            Text(w.name, style = MaterialTheme.typography.bodyLarge, color = Ios.colors.label)
            Text(listOf(if (w.mode == com.coralcoder.iwatchbridge.protocol.registry.LinkMode.SHARED) "Shared with iPhone" else null, w.model, w.osVersion.takeIf { it.isNotEmpty() }?.let { "watchOS $it" }).filterNotNull().filter { it.isNotEmpty() }.joinToString(" · "),
                style = MaterialTheme.typography.labelMedium, color = Ios.colors.secondaryLabel)
        }
        if (active) Icon(Icons.Filled.Check, "Active", tint = Ios.colors.tint, modifier = Modifier.size(20.dp))
        Icon(Icons.Outlined.Info, "Info", tint = Ios.colors.tint, modifier = Modifier.size(24.dp).clickable(onClick = onInfo))
    }
}

/** Details for one paired watch, with Unpair. */
@Composable
fun WatchInfoScreen(id: String, onBack: () -> Unit) {
    val bridge = LocalContext.current.bridge
    val reg by bridge.registry.state.collectAsState()
    val w = reg.watches.firstOrNull { it.id == id } ?: run { onBack(); return }
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        NavBar(w.name, back = "All Watches", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
            section(rows = listOfNotNull(
                @Composable { ListRow("Name", value = w.name, chevron = false) },
                @Composable { ListRow("Model", value = w.model.ifEmpty { "—" }, chevron = false) },
                @Composable { ListRow("watchOS", value = w.osVersion.ifEmpty { "—" }, chevron = false) },
                @Composable { ListRow("Serial Number", value = w.serial.ifEmpty { "—" }, chevron = false) },
                @Composable { ListRow("Bluetooth", value = w.btAddress.ifEmpty { "—" }, chevron = false) },
                @Composable { ListRow("Paired", value = if (w.pairedAtEpochMs > 0) DateFormat.getDateInstance().format(Date(w.pairedAtEpochMs)) else "—", chevron = false) },
                @Composable { ListRow("Activation", value = w.activationState.name.lowercase().replace('_', ' '), chevron = false) },
            ))
            section(
                footer = "Unpairing removes this watch's keys from this phone. Erase the watch from its own Settings to set it up again.",
                rows = listOf { ListRow("Unpair Apple Watch", destructive = true, chevron = false, onClick = { confirm = true }) },
            )
        }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text("Unpair ${w.name}?") },
            confirmButton = { androidx.compose.material3.TextButton(onClick = { bridge.registry.remove(w.id); confirm = false; onBack() }) { Text("Unpair", color = Ios.colors.red) } },
            dismissButton = { androidx.compose.material3.TextButton(onClick = { confirm = false }) { Text("Cancel") } },
        )
    }
}
