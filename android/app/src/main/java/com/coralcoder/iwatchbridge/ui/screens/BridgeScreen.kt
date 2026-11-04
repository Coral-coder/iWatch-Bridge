package com.coralcoder.iwatchbridge.ui.screens

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.coralcoder.iwatchbridge.bridge
import com.coralcoder.iwatchbridge.core.BridgeController.Radio
import com.coralcoder.iwatchbridge.protocol.host.HostStack
import com.coralcoder.iwatchbridge.ui.components.LargeTitle
import com.coralcoder.iwatchbridge.ui.components.ListPadding
import com.coralcoder.iwatchbridge.ui.components.ListRow
import com.coralcoder.iwatchbridge.ui.components.section
import com.coralcoder.iwatchbridge.ui.theme.Ios

/** Connection status, radio choice and the Bluetooth trace: what the iPhone hides, for when things go wrong. */
@Composable
fun BridgeScreen() {
    val bridge = LocalContext.current.bridge
    val radio by bridge.radio.collectAsState()
    val log by bridge.log.collectAsState()
    val shared by bridge.shared.state.collectAsState()
    val askBt = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
        if (r.values.all { it }) bridge.shared.start()
    }
    val startShared = {
        if (Build.VERSION.SDK_INT >= 31) askBt.launch(arrayOf(Manifest.permission.BLUETOOTH_ADVERTISE, Manifest.permission.BLUETOOTH_CONNECT))
        else bridge.shared.start()
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
        item { LargeTitle("Bridge") }
        section(
            header = "Shared with iPhone",
            footer = "For a watch that stays paired to your iPhone: the iWatch Bridge app on the watch connects to this phone over Bluetooth.",
            rows = listOf(
                @Composable { ListRow("Watch app link", value = shared.status, chevron = false) },
                @Composable { ListRow(if (shared.running) "Stop" else "Start", tint = true, chevron = false, onClick = { if (shared.running) bridge.shared.stop() else startShared() }) },
            ),
        )
        section(
            header = "Primary phone (replaces iPhone)",
            footer = radio.error ?: "Native pairing runs its own Bluetooth host on a radio this app controls.",
            rows = listOf(
                @Composable { ListRow("Radio", value = radio.radio?.label ?: "Off", chevron = false) },
                @Composable { ListRow("State", value = radio.state.name.lowercase(), subtitle = radio.detail.ifEmpty { null }, chevron = false) },
                @Composable { ListRow("Use USB adapter", tint = true, chevron = false, onClick = { bridge.startRadio(Radio.USB) }) },
                @Composable { ListRow("Use built-in Bluetooth (root)", tint = true, chevron = false, onClick = { bridge.startRadio(Radio.ROOT) }) },
                @Composable { ListRow("Turn off", destructive = true, chevron = false, enabled = radio.state != HostStack.State.OFF, onClick = { bridge.stopRadio() }) },
            ),
        )
        section(header = "Bluetooth trace", rows = listOf {
            Text(
                if (log.isEmpty()) "No activity yet." else log.takeLast(60).joinToString("\n"),
                fontFamily = FontFamily.Monospace, fontSize = 11.sp, color = Ios.colors.secondaryLabel,
                style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(12.dp),
            )
        })
    }
}
