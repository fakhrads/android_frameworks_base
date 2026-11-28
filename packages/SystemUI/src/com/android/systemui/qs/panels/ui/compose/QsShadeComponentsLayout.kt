/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.panels.ui.compose

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.android.systemui.qs.panels.ui.model.QsShadeComponent

/** Lays out brightness, volume, tiles, and media according to [components]. */
@Composable
fun QsShadeComponentsColumn(
    components: List<QsShadeComponent>,
    brightness: @Composable () -> Unit,
    tiles: @Composable () -> Unit,
    media: @Composable () -> Unit,
    mediaInRow: Boolean,
    verticalArrangement: Arrangement.Vertical,
    horizontalArrangement: Arrangement.Horizontal,
    modifier: Modifier = Modifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.CenterHorizontally,
    volume: @Composable () -> Unit = {},
) {
    Column(
        verticalArrangement = verticalArrangement,
        horizontalAlignment = horizontalAlignment,
        modifier = modifier,
    ) {
        if (mediaInRow) {
            val firstOfPair =
                components.firstOrNull {
                    it == QsShadeComponent.TILES_GRID || it == QsShadeComponent.MEDIA
                }
            val tilesMediaRow = @Composable {
                Row(
                    horizontalArrangement = horizontalArrangement,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(modifier = Modifier.weight(1f)) {
                        key(QsShadeComponent.TILES_GRID) { tiles() }
                    }
                    Box(modifier = Modifier.weight(1f)) {
                        key(QsShadeComponent.MEDIA) { media() }
                    }
                }
            }
            forEachGroupingSliders(components, brightness, volume) { component ->
                when (component) {
                    QsShadeComponent.TILES_GRID,
                    QsShadeComponent.MEDIA -> {
                        if (component == firstOfPair) {
                            tilesMediaRow()
                        }
                    }
                    QsShadeComponent.BRIGHTNESS -> {
                        key(QsShadeComponent.BRIGHTNESS) { brightness() }
                    }
                    QsShadeComponent.VOLUME -> {
                        key(QsShadeComponent.VOLUME) { volume() }
                    }
                }
            }
        } else {
            forEachGroupingSliders(components, brightness, volume) { component ->
                key(component) {
                    when (component) {
                        QsShadeComponent.BRIGHTNESS -> brightness()
                        QsShadeComponent.VOLUME -> volume()
                        QsShadeComponent.TILES_GRID -> tiles()
                        QsShadeComponent.MEDIA -> media()
                    }
                }
            }
        }
    }
}

/**
 * Space between the brightness and volume sliders when they are next to each other. With the
 * column's spacing between every component, the two sliders stood apart like separate sections
 * (about 34 dp of empty space between two 40 dp tracks, counting their own padding), so a
 * slider pair gets less, as one group.
 */
private val SliderPairSpacing = 8.dp

private fun QsShadeComponent.isSlider() =
    this == QsShadeComponent.BRIGHTNESS || this == QsShadeComponent.VOLUME

/**
 * Calls [content] for each of [components] in order, but puts a brightness and a volume slider
 * that are next to each other in a column of their own, [SliderPairSpacing] apart.
 */
@Composable
private fun forEachGroupingSliders(
    components: List<QsShadeComponent>,
    brightness: @Composable () -> Unit,
    volume: @Composable () -> Unit,
    content: @Composable (QsShadeComponent) -> Unit,
) {
    var i = 0
    while (i < components.size) {
        val component = components[i]
        val next = components.getOrNull(i + 1)
        if (component.isSlider() && next != null && next.isSlider() && next != component) {
            key(component, next) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(SliderPairSpacing),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    for (slider in listOf(component, next)) {
                        key(slider) {
                            if (slider == QsShadeComponent.BRIGHTNESS) brightness() else volume()
                        }
                    }
                }
            }
            i += 2
        } else {
            content(component)
            i++
        }
    }
}

/** Lays out brightness, volume, tiles, and media according to [components]. */
@Composable
fun QqsShadeComponentsLayout(
    components: List<QsShadeComponent>,
    tiles: @Composable () -> Unit,
    media: @Composable () -> Unit,
    mediaInRow: Boolean,
    verticalArrangement: Arrangement.Vertical,
    horizontalArrangement: Arrangement.Horizontal,
    modifier: Modifier = Modifier,
    brightness: @Composable () -> Unit = {},
    volume: @Composable () -> Unit = {},
) {
    val firstOfTilesMedia =
        components.firstOrNull {
            it == QsShadeComponent.TILES_GRID || it == QsShadeComponent.MEDIA
        }
    if (mediaInRow) {
        val tilesMediaRow = @Composable {
            Row(
                horizontalArrangement = horizontalArrangement,
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Box(modifier = Modifier.weight(1f)) {
                    key(QsShadeComponent.TILES_GRID) { tiles() }
                }
                Box(modifier = Modifier.weight(1f)) { key(QsShadeComponent.MEDIA) { media() } }
            }
        }
        Column(verticalArrangement = verticalArrangement, modifier = modifier) {
            components.forEach { component ->
                when (component) {
                    QsShadeComponent.BRIGHTNESS -> {
                        key(QsShadeComponent.BRIGHTNESS) { brightness() }
                    }
                    QsShadeComponent.VOLUME -> {
                        key(QsShadeComponent.VOLUME) { volume() }
                    }
                    QsShadeComponent.TILES_GRID,
                    QsShadeComponent.MEDIA -> {
                        if (component == firstOfTilesMedia) {
                            tilesMediaRow()
                        }
                    }
                }
            }
        }
    } else {
        Column(verticalArrangement = verticalArrangement, modifier = modifier) {
            components.forEach { component ->
                key(component) {
                    when (component) {
                        QsShadeComponent.BRIGHTNESS -> brightness()
                        QsShadeComponent.VOLUME -> volume()
                        QsShadeComponent.TILES_GRID -> tiles()
                        QsShadeComponent.MEDIA -> media()
                    }
                }
            }
        }
    }
}
