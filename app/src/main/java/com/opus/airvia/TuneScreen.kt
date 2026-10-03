package com.opus.airvia

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/** One launchable app, for the per-app capture picker. */
data class AppEntry(val packageName: String, val label: String)

/**
 * The Tune tab: 5-band EQ + preamp (applied live to the cast stream),
 * now-playing metadata access, and the per-app capture picker.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun TuneScreen(
    eqGains: DoubleArray,
    eqPreamp: Double,
    onEqChange: (DoubleArray, Double) -> Unit,
    nowPlaying: NowPlayingInfo?,
    metaAccessGranted: Boolean,
    onGrantMetaAccess: () -> Unit,
    apps: List<AppEntry>,
    selectedApps: Set<String>,
    onToggleApp: (String, Boolean) -> Unit,
    onCaptureAllApps: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        item { Spacer(Modifier.height(4.dp)) }

        // --- EQ ------------------------------------------------------
        item {
            Text(
                "Equalizer",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        for (preset in Eq.PRESETS) {
                            val selected = preset.preampDb == eqPreamp &&
                                preset.gains.indices.all { preset.gains[it] == eqGains[it] }
                            FilterChip(
                                selected = selected,
                                onClick = { onEqChange(preset.gains.copyOf(), preset.preampDb) },
                                label = { Text(preset.name) },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    for (band in 0 until DspProcessor.BAND_COUNT) {
                        EqBandRow(
                            label = freqLabel(DspProcessor.BAND_FREQS[band]),
                            db = eqGains[band],
                            onChange = { db ->
                                val g = eqGains.copyOf()
                                g[band] = db
                                onEqChange(g, eqPreamp)
                            },
                        )
                    }
                    EqBandRow(
                        label = "Preamp",
                        db = eqPreamp,
                        onChange = { db -> onEqChange(eqGains.copyOf(), db) },
                    )
                    Text(
                        "Shapes the sound on every speaker, live. Applies to " +
                            "AirPlay, DLNA and Chromecast alike (it runs on the " +
                            "phone's captured audio).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        // --- Now playing ----------------------------------------------
        item {
            Text(
                "Now playing",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant,
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    if (!metaAccessGranted) {
                        Text(
                            "Speakers can show the song title, artist and album " +
                                "art — Android asks you to allow notification " +
                                "access once so Airvia can read what's playing.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        Spacer(Modifier.height(10.dp))
                        Button(onClick = onGrantMetaAccess) {
                            Text("Allow notification access")
                        }
                    } else if (nowPlaying != null) {
                        Text(
                            nowPlaying.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        Text(
                            listOf(nowPlaying.artist, nowPlaying.album)
                                .filter { it.isNotBlank() }
                                .joinToString(" · ")
                                .ifEmpty { "—" },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(4.dp))
                        Text(
                            "This is what your AirPlay speakers display.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    } else {
                        Text(
                            "Nothing playing right now. Start music in any app " +
                                "and its title and artwork will appear on the speaker.",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }

        // --- Per-app capture -------------------------------------------
        item {
            Text(
                "Capture from",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
        }
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (selectedApps.isEmpty()) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
                ),
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (selectedApps.isEmpty()) {
                                "All apps"
                            } else {
                                "${selectedApps.size} app(s) selected"
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Medium,
                        )
                        Text(
                            if (selectedApps.isEmpty()) {
                                "Every app's audio is cast."
                            } else {
                                "Only the ticked apps are cast. Takes effect " +
                                    "on the next cast."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (selectedApps.isNotEmpty()) {
                        TextButton(onClick = onCaptureAllApps) { Text("Reset") }
                    }
                }
            }
        }
        items(apps, key = { it.packageName }) { app ->
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = app.packageName in selectedApps,
                    onCheckedChange = { checked -> onToggleApp(app.packageName, checked) },
                )
                Spacer(Modifier.width(8.dp))
                Column {
                    Text(
                        app.label,
                        style = MaterialTheme.typography.bodyLarge,
                    )
                    Text(
                        app.packageName,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

@Composable
private fun EqBandRow(label: String, db: Double, onChange: (Double) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(64.dp),
        )
        Slider(
            value = db.toFloat(),
            onValueChange = { onChange(it.toDouble()) },
            valueRange = -12f..12f,
            steps = 23,
            modifier = Modifier.weight(1f),
        )
        Text(
            (if (db > 0) "+" else "") + "%.0f dB".format(db),
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.width(52.dp),
        )
    }
}

private fun freqLabel(hz: Int): String =
    if (hz >= 1000) "${hz / 1000} kHz" else "$hz Hz"
