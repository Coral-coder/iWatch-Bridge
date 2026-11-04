package com.coralcoder.iwatchbridge.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.coralcoder.iwatchbridge.bridge
import com.coralcoder.iwatchbridge.core.BridgeController.Radio
import com.coralcoder.iwatchbridge.protocol.host.HostStack
import com.coralcoder.iwatchbridge.protocol.host.SetupAdvertisement
import com.coralcoder.iwatchbridge.protocol.pairing.PairingEvent
import com.coralcoder.iwatchbridge.protocol.pairing.PairingProof
import com.coralcoder.iwatchbridge.protocol.pairing.PairingStep
import com.coralcoder.iwatchbridge.ui.components.FootnoteText
import com.coralcoder.iwatchbridge.ui.components.ListPadding
import com.coralcoder.iwatchbridge.ui.components.ListRow
import com.coralcoder.iwatchbridge.ui.components.NavBar
import com.coralcoder.iwatchbridge.ui.components.PrimaryButton
import com.coralcoder.iwatchbridge.ui.components.TextButton
import com.coralcoder.iwatchbridge.ui.components.VSpace
import com.coralcoder.iwatchbridge.ui.components.WatchIllustration
import com.coralcoder.iwatchbridge.ui.components.section
import com.coralcoder.iwatchbridge.ui.theme.Ios

private enum class Stage { RADIO, SCAN, MANUAL, PIN, PROGRESS }

/**
 * Setting up a watch, screen for screen like the iPhone: hold the watch up to the camera, or
 * "Pair Manually" by picking it from a list and typing the code it shows.
 */
@Composable
fun PairingFlow(onClose: () -> Unit) {
    val bridge = LocalContext.current.bridge
    val radio by bridge.radio.collectAsState()
    var stage by remember { mutableStateOf(if (radio.state == HostStack.State.READY) Stage.SCAN else Stage.RADIO) }
    var target by remember { mutableStateOf<SetupAdvertisement?>(null) }

    LaunchedEffect(radio.state) { if (radio.state == HostStack.State.READY && stage == Stage.RADIO) stage = Stage.SCAN }
    LaunchedEffect(stage) { if (stage == Stage.SCAN || stage == Stage.MANUAL) bridge.startDiscovery() }
    DisposableEffect(Unit) { onDispose { bridge.stopDiscovery(); bridge.cancelPairing() } }

    val close = { onClose() }
    when (stage) {
        Stage.RADIO -> RadioChooser(onCancel = close)
        Stage.SCAN -> CameraScan(onManual = { stage = Stage.MANUAL }, onCancel = close)
        Stage.MANUAL -> ManualList(onPick = { target = it; bridge.beginPairing(it); stage = Stage.PIN }, onBack = { stage = Stage.SCAN })
        Stage.PIN -> PinEntry(target?.localName ?: "Apple Watch", onBack = { bridge.cancelPairing(); stage = Stage.MANUAL }) { pin ->
            bridge.providePairingProof(PairingProof.Pin(pin)); stage = Stage.PROGRESS
        }
        Stage.PROGRESS -> Progress(onDone = close)
    }
}

@Composable
private fun RadioChooser(onCancel: () -> Unit) {
    val bridge = LocalContext.current.bridge
    val radio by bridge.radio.collectAsState()
    val usb = remember { bridge.usbAdapters() }
    Column(Modifier.fillMaxSize()) {
        NavBar("", back = "Cancel", onBack = onCancel)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Bluetooth for Apple Watch", style = MaterialTheme.typography.headlineSmall, color = Ios.colors.label, textAlign = TextAlign.Center)
                    VSpace(8.dp)
                    FootnoteText("Apple Watch talks to its phone in ways Android's Bluetooth doesn't allow apps to use, so this app runs its own Bluetooth on a radio it controls.")
                }
            }
            section(
                header = "Choose a radio",
                footer = "A USB Bluetooth adapter needs no root and leaves your phone's Bluetooth free for headphones and cars. The built-in radio needs root and Android's Bluetooth turned off.",
                rows = listOf(
                    @Composable { ListRow(Radio.USB.label, subtitle = if (usb.isEmpty()) "No adapter plugged in" else usb.first().productName ?: "Adapter found", onClick = { bridge.startRadio(Radio.USB) }) },
                    @Composable { ListRow(Radio.ROOT.label, subtitle = "Requires root (Magisk)", onClick = { bridge.startRadio(Radio.ROOT) }) },
                ),
            )
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    when (radio.state) {
                        HostStack.State.STARTING -> CircularProgressIndicator(color = Ios.colors.gray)
                        HostStack.State.FAILED -> Text(radio.error ?: "Couldn't start Bluetooth", color = Ios.colors.red, textAlign = TextAlign.Center)
                        else -> {}
                    }
                }
            }
        }
    }
}

/** "Hold your Apple Watch up to the camera" with the circular viewfinder. */
@Composable
private fun CameraScan(onManual: () -> Unit, onCancel: () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) ask.launch(Manifest.permission.CAMERA) }
    Column(Modifier.fillMaxSize().background(Color.Black)) {
        NavBar("", back = "Cancel", onBack = onCancel)
        Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Hold your Apple Watch up to the camera", color = Color.White, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
            VSpace(8.dp)
            Text("Position Apple Watch in the centre of the viewfinder.", color = Color(0xFF8E8E93), style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            VSpace(32.dp)
            Box(Modifier.size(280.dp).clip(CircleShape).border(3.dp, Color.White.copy(alpha = 0.8f), CircleShape)) {
                if (granted) CameraPreview(Modifier.fillMaxSize())
            }
            VSpace(24.dp)
            Text(
                "Reading the pairing animation needs Apple's visual pairing decoder, which can't be shipped with this app. Use Pair Manually for now.",
                color = Color(0xFF8E8E93), style = MaterialTheme.typography.labelMedium, textAlign = TextAlign.Center,
            )
            Box(Modifier.weight(1f))
            TextButton("Pair Manually", onClick = onManual)
        }
    }
}

@Composable
private fun CameraPreview(modifier: Modifier) {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    AndroidView(modifier = modifier, factory = { c ->
        PreviewView(c).also { view ->
            val future = ProcessCameraProvider.getInstance(c)
            future.addListener({
                val provider = future.get()
                val preview = Preview.Builder().build().also { it.surfaceProvider = view.surfaceProvider }
                runCatching { provider.unbindAll(); provider.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview) }
            }, ContextCompat.getMainExecutor(ctx))
        }
    })
}

/** "Pair Manually": watches showing the setup screen nearby. */
@Composable
private fun ManualList(onPick: (SetupAdvertisement) -> Unit, onBack: () -> Unit) {
    val bridge = LocalContext.current.bridge
    val watches by bridge.setupWatches.collectAsState()
    Column(Modifier.fillMaxSize()) {
        NavBar("", back = "Back", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
            item {
                Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Pair Apple Watch Manually", style = MaterialTheme.typography.headlineSmall, color = Ios.colors.label, textAlign = TextAlign.Center)
                    VSpace(8.dp)
                    FootnoteText("Tap the ⓘ on your Apple Watch to see its name, then choose it below.")
                }
            }
            if (watches.isEmpty()) {
                item {
                    Row(Modifier.fillMaxWidth().padding(24.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = Ios.colors.gray)
                        Text("  Looking for Apple Watch…", color = Ios.colors.secondaryLabel)
                    }
                }
            } else {
                section(rows = watches.map { w ->
                    @Composable { ListRow(w.localName ?: "Apple Watch", subtitle = "${w.address} · ${w.rssi} dBm", onClick = { onPick(w) }) }
                })
            }
        }
    }
}

/** Six boxes for the code shown on the watch. */
@Composable
private fun PinEntry(watchName: String, onBack: () -> Unit, onDone: (String) -> Unit) {
    var pin by remember { mutableStateOf("") }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize()) {
        NavBar("", back = "Back", onBack = onBack)
        Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Enter Code", style = MaterialTheme.typography.headlineSmall, color = Ios.colors.label)
            VSpace(8.dp)
            FootnoteText("Enter the 6-digit code shown on $watchName.")
            VSpace(32.dp)
            BasicTextField(
                value = pin,
                onValueChange = { v -> pin = v.filter(Char::isDigit).take(6); if (pin.length == 6) onDone(pin) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.focusRequester(focus),
                decorationBox = {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        repeat(6) { i ->
                            Box(Modifier.width(44.dp).height(56.dp).clip(RoundedCornerShape(10.dp)).background(Ios.colors.cell), contentAlignment = Alignment.Center) {
                                Text(pin.getOrNull(i)?.toString() ?: "", fontSize = 28.sp, color = Ios.colors.label)
                            }
                        }
                    }
                },
            )
        }
    }
}

@Composable
private fun Progress(onDone: () -> Unit) {
    val bridge = LocalContext.current.bridge
    val events by bridge.pairing.collectAsState()
    val failed = events.filterIsInstance<PairingEvent.Failed>().lastOrNull()
    val paired = events.filterIsInstance<PairingEvent.Paired>().lastOrNull()
    val step = events.filterIsInstance<PairingEvent.Progress>().lastOrNull()?.step ?: PairingStep.CONNECT_LINK
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        WatchIllustration(Modifier.size(160.dp), showRings = paired != null)
        VSpace(24.dp)
        when {
            paired != null -> {
                Icon(Icons.Filled.CheckCircle, null, tint = Ios.colors.green, modifier = Modifier.size(40.dp))
                Text("Your Apple Watch Is Paired", style = MaterialTheme.typography.headlineSmall, color = Ios.colors.label)
                VSpace(24.dp); PrimaryButton("Done", onClick = onDone)
            }
            failed != null -> {
                Icon(Icons.Filled.ErrorOutline, null, tint = if (failed.unsupported) Ios.colors.orange else Ios.colors.red, modifier = Modifier.size(40.dp))
                VSpace(8.dp)
                Text(if (failed.unsupported) "Not Supported Yet" else "Couldn't Pair", style = MaterialTheme.typography.headlineSmall, color = Ios.colors.label)
                VSpace(8.dp)
                FootnoteText("Stopped at: ${failed.step.title} (${failed.step.name})\n\n${failed.reason}")
                VSpace(24.dp); PrimaryButton("OK", onClick = onDone)
            }
            else -> {
                Text(step.title, style = MaterialTheme.typography.headlineSmall, color = Ios.colors.label)
                VSpace(16.dp)
                CircularProgressIndicator(color = Ios.colors.gray)
                VSpace(8.dp)
                FootnoteText("Keep your Apple Watch and phone close together.")
            }
        }
    }
}
