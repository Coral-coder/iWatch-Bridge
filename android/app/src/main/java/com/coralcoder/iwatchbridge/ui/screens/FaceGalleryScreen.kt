package com.coralcoder.iwatchbridge.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.coralcoder.iwatchbridge.ui.components.LargeTitle
import com.coralcoder.iwatchbridge.ui.components.ListPadding
import com.coralcoder.iwatchbridge.ui.components.SectionLabel
import com.coralcoder.iwatchbridge.ui.components.WatchIllustration
import com.coralcoder.iwatchbridge.ui.theme.Ios

/** Watch faces grouped like the iPhone's Face Gallery. Faces render on the watch itself; adding one goes through face sync. */
private val Gallery = listOf(
    "New Watch Faces" to listOf("Flow", "Waypoint", "Exactograph", "Hermès Rhythm"),
    "Activity" to listOf("Activity Digital", "Activity Analog", "Infograph"),
    "Photos" to listOf("Photos", "Portraits", "Kaleidoscope"),
    "Modular" to listOf("Modular", "Modular Compact", "Modular Ultra", "Modular Duo"),
    "Artistic" to listOf("Gradient", "Solar Dial", "Nike Globe", "Snoopy", "Unity"),
    "Simple" to listOf("Simple", "Numerals", "Numerals Duo", "California", "Utility", "Chronograph"),
)

private val FaceColors = listOf(0xFF0B0B0C, 0xFF1D2A44, 0xFF2B1B3A, 0xFF0D2B1E, 0xFF3A1E12, 0xFF102A33).map { Color(it) }

@Composable
fun FaceGalleryScreen() {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = ListPadding) {
        item { LargeTitle("Face Gallery") }
        Gallery.forEachIndexed { gi, (group, faces) ->
            item { SectionLabel(group) }
            item {
                LazyRow(contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    items(faces) { name ->
                        Column(Modifier.width(110.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                            WatchIllustration(
                                Modifier.size(110.dp).clip(RoundedCornerShape(16.dp)).background(Ios.colors.cell).padding(8.dp),
                                faceColor = FaceColors[(gi + name.length) % FaceColors.size],
                                showRings = group == "Activity",
                            )
                            Text(name, style = MaterialTheme.typography.labelMedium, color = Ios.colors.label, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
                        }
                    }
                }
            }
        }
    }
}
