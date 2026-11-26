/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.systemui.qs.ui.compose

import android.provider.Settings
import androidx.compose.runtime.Composable
import com.android.compose.settings.rememberSystemIntSetting

/**
 * Reads and observes a [Settings.System] boolean stored as 0/1.
 *
 * Scene-container QS can compose before Settings is ready, so this re-reads on subscribe and
 * updates when the setting changes.
 */
@Composable
fun rememberQsSystemBoolSetting(name: String, defaultValue: Boolean): Boolean {
    return rememberSystemIntSetting(name, if (defaultValue) 1 else 0) == 1
}
