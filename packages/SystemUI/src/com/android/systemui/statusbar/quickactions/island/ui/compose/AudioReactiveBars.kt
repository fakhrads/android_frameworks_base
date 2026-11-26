/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.systemui.statusbar.quickactions.island.ui.compose

import android.media.audiofx.Visualizer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs
import kotlin.math.sqrt

private val DefaultReactiveLevels = floatArrayOf(0.22f, 0.45f, 0.34f, 0.56f)

@Composable
fun AudioReactiveBars(
    isPlaying: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    barWidth: Dp = 2.dp,
    maxBarHeight: Dp = 11.dp,
    spacing: Dp = 1.5.dp,
    startPadding: Dp = 0.dp,
) {
    val levels = remember {
        mutableStateListOf(
            DefaultReactiveLevels[0],
            DefaultReactiveLevels[1],
            DefaultReactiveLevels[2],
            DefaultReactiveLevels[3],
        )
    }
    DisposableEffect(isPlaying) {
        if (!isPlaying) {
            DefaultReactiveLevels.forEachIndexed { index, level -> levels[index] = level }
            onDispose {}
        } else {
            val listener: (FloatArray) -> Unit = { newLevels ->
                for (index in newLevels.indices) {
                    levels[index] =
                        (levels[index] * 0.65f + newLevels[index] * 0.35f).coerceIn(0.1f, 1f)
                }
            }
            SharedWaveformVisualizer.subscribe(listener)
            onDispose { SharedWaveformVisualizer.unsubscribe(listener) }
        }
    }

    Row(
        modifier = modifier.padding(start = startPadding),
        horizontalArrangement = Arrangement.spacedBy(spacing),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(levels.size) { index ->
            Box(
                modifier = Modifier.size(width = barWidth, height = maxBarHeight),
                contentAlignment = Alignment.BottomCenter,
            ) {
                Box(
                    modifier =
                        Modifier.size(width = barWidth, height = maxBarHeight * levels[index])
                            .clip(RoundedCornerShape(8.dp))
                            .background(color)
                )
            }
        }
    }
}

private fun ByteArray.windowEnergy(start: Int, end: Int): Float {
    if (start >= end || start < 0 || end > size) return 0.1f

    var sum = 0f
    var count = 0
    for (index in start until end) {
        val normalized = abs(this[index].toInt()) / 128f
        sum += normalized * normalized
        count++
    }

    if (count == 0) return 0.1f
    return sqrt(sum / count).coerceIn(0.1f, 1f)
}

/**
 * One waveform [Visualizer] for every [AudioReactiveBars] in the process.
 *
 * Creating a visualizer opens an effect in the audio HAL (tens of milliseconds), and releasing it
 * closes the effect again. The bars leave and re-enter the composition whenever the shade opens
 * and closes, so the visualizer is created and released on a background thread, is shared, and is
 * kept for a few seconds after the last subscriber leaves. Its capture callbacks also run on that
 * thread; subscribers get the levels on the main thread.
 */
private object SharedWaveformVisualizer {
    private const val RELEASE_DELAY_MS = 3_000L

    private val mainHandler = Handler(Looper.getMainLooper())
    private val handler: Handler by lazy {
        val thread = HandlerThread("AudioReactiveBars", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        Handler(thread.looper)
    }

    // Touched only on the main thread.
    private val subscribers = mutableListOf<(FloatArray) -> Unit>()

    // Touched only on [handler]'s thread.
    private var visualizer: Visualizer? = null

    private val releaseRunnable = Runnable { releaseVisualizer() }

    private val captureListener =
        object : Visualizer.OnDataCaptureListener {
            override fun onWaveFormDataCapture(
                visualizer: Visualizer?,
                waveform: ByteArray?,
                samplingRate: Int,
            ) {
                val safeWaveform = waveform ?: return
                if (safeWaveform.isEmpty()) return

                val quarter = safeWaveform.size / 4
                val secondQuarter = quarter * 2
                val thirdQuarter = quarter * 3
                val newLevels =
                    floatArrayOf(
                        safeWaveform.windowEnergy(0, quarter),
                        safeWaveform.windowEnergy(quarter, secondQuarter),
                        safeWaveform.windowEnergy(secondQuarter, thirdQuarter),
                        safeWaveform.windowEnergy(thirdQuarter, safeWaveform.size),
                    )
                mainHandler.post { subscribers.forEach { it(newLevels) } }
            }

            override fun onFftDataCapture(
                visualizer: Visualizer?,
                fft: ByteArray?,
                samplingRate: Int,
            ) = Unit
        }

    /** Main thread. */
    fun subscribe(listener: (FloatArray) -> Unit) {
        subscribers += listener
        if (subscribers.size == 1) {
            handler.removeCallbacks(releaseRunnable)
            handler.post { ensureVisualizer() }
        }
    }

    /** Main thread. */
    fun unsubscribe(listener: (FloatArray) -> Unit) {
        subscribers -= listener
        if (subscribers.isEmpty()) handler.postDelayed(releaseRunnable, RELEASE_DELAY_MS)
    }

    private fun ensureVisualizer() {
        if (visualizer != null) return
        // Created on this thread, so its capture callbacks are delivered here too.
        visualizer =
            runCatching {
                    Visualizer(0).apply {
                        val captureSizes = Visualizer.getCaptureSizeRange()
                        captureSize = captureSizes[1].coerceAtMost(256)
                        setDataCaptureListener(
                            captureListener,
                            Visualizer.getMaxCaptureRate() / 2,
                            true,
                            false,
                        )
                        enabled = true
                    }
                }
                .getOrNull()
    }

    private fun releaseVisualizer() {
        val current = visualizer ?: return
        visualizer = null
        runCatching { current.enabled = false }
        runCatching { current.release() }
    }
}
