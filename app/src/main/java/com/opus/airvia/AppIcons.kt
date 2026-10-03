package com.opus.airvia

import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource

/**
 * Glyphs the Compose core icon set doesn't ship (Speaker, volume, stop,
 * copy) as app vector drawables, wrapped to behave like Material icons.
 *
 * IDs are resolved by name at runtime: the manual build compiles Kotlin
 * before aapt2 generates the app's R class, so sources can't reference
 * R directly (same constraint Outro lives with).
 */
@Composable
private fun drawableId(name: String): Int {
    val ctx = LocalContext.current
    return remember(name) {
        ctx.resources.getIdentifier(name, "drawable", ctx.packageName)
    }
}

@Composable
fun SpeakerIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(drawableId("ic_speaker")), contentDescription = null, modifier = modifier, tint = tint)
}

@Composable
fun VolumeDownIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(drawableId("ic_volume_down")), contentDescription = "Volume down", modifier = modifier, tint = tint)
}

@Composable
fun VolumeUpIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(drawableId("ic_volume_up")), contentDescription = "Volume up", modifier = modifier, tint = tint)
}

@Composable
fun StopIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(drawableId("ic_stop")), contentDescription = null, modifier = modifier, tint = tint)
}

@Composable
fun CopyIcon(
    modifier: Modifier = Modifier,
    tint: Color = LocalContentColor.current,
) {
    Icon(painterResource(drawableId("ic_content_copy")), contentDescription = "Copy log", modifier = modifier, tint = tint)
}
