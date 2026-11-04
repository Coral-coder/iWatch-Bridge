package com.coralcoder.iwatchbridge.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Explore
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.coralcoder.iwatchbridge.service.BridgeService
import com.coralcoder.iwatchbridge.ui.screens.AllWatchesScreen
import com.coralcoder.iwatchbridge.ui.screens.BridgeScreen
import com.coralcoder.iwatchbridge.ui.screens.FaceGalleryScreen
import com.coralcoder.iwatchbridge.ui.screens.MyWatchScreen
import com.coralcoder.iwatchbridge.ui.screens.PairingFlow
import com.coralcoder.iwatchbridge.ui.screens.SettingsDetailScreen
import com.coralcoder.iwatchbridge.ui.screens.WatchInfoScreen
import com.coralcoder.iwatchbridge.ui.theme.BridgeTheme
import com.coralcoder.iwatchbridge.ui.theme.Ios

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        runCatching { BridgeService.start(this) }
        setContent { BridgeTheme { App() } }
    }
}

private enum class Tab(val title: String, val icon: ImageVector) {
    MY_WATCH("My Watch", Icons.Filled.Watch),
    FACES("Face Gallery", Icons.Filled.GridView),
    BRIDGE("Bridge", Icons.Filled.Explore),
}

@Composable
private fun App() {
    val nav = rememberNavController()
    var tab by rememberSaveable { mutableIntStateOf(0) }
    NavHost(nav, startDestination = "tabs", modifier = Modifier.fillMaxSize().background(Ios.colors.groupedBackground)) {
        composable("tabs") {
            Column(Modifier.fillMaxSize()) {
                Box(Modifier.weight(1f)) {
                    when (Tab.entries[tab]) {
                        Tab.MY_WATCH -> MyWatchScreen(
                            onAllWatches = { nav.navigate("allWatches") },
                            onPair = { nav.navigate("pair") },
                            onSetting = { nav.navigate("settings/$it") },
                        )
                        Tab.FACES -> FaceGalleryScreen()
                        Tab.BRIDGE -> BridgeScreen()
                    }
                }
                TabBar(tab) { tab = it }
            }
        }
        composable("allWatches") {
            AllWatchesScreen(onBack = { nav.popBackStack() }, onAdd = { nav.navigate("pair") }, onInfo = { nav.navigate("watchInfo/$it") })
        }
        composable("watchInfo/{id}") { e ->
            WatchInfoScreen(e.arguments?.getString("id").orEmpty(), onBack = { nav.popBackStack() })
        }
        composable("pair") { PairingFlow(onClose = { nav.popBackStack() }) }
        composable("settings/{key}") { e ->
            SettingsDetailScreen(e.arguments?.getString("key").orEmpty(), onBack = { nav.popBackStack() })
        }
    }
}

@Composable
private fun TabBar(selected: Int, onSelect: (Int) -> Unit) {
    Column(Modifier.background(Ios.colors.cell.copy(alpha = 0.94f)).windowInsetsPadding(WindowInsets.navigationBars)) {
        HorizontalDivider(thickness = 0.5.dp, color = Ios.colors.separator)
        Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Tab.entries.forEachIndexed { i, t ->
                val color = if (i == selected) Ios.colors.tint else Ios.colors.gray
                Column(Modifier.weight(1f).clickable { onSelect(i) }, horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(t.icon, t.title, tint = color, modifier = Modifier.size(26.dp))
                    Text(t.title, color = color, fontSize = 10.sp)
                }
            }
        }
    }
}
