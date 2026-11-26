/*
 * SPDX-FileCopyrightText: DerpFest AOSP
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.compose.settings

import android.content.BroadcastReceiver
import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Settings values for Compose UI that are shared by the whole process.
 *
 * Each setting is read once and then observed by a single [ContentObserver] for the rest of the
 * process. Changes, and a switch of the foreground user, are re-read on a background thread. A
 * composable reads the value as snapshot state, so composing or disposing it makes no binder call.
 *
 * Reading the setting in each composable instead costs a read, an observer registration and a
 * second read when it enters the composition, and an unregistration when it leaves. Quick settings
 * compose and dispose every tile each time the shade opens and closes, so that adds up to hundreds
 * of synchronous binder calls on the main thread within a single frame.
 *
 * Values are read for [UserHandle.USER_CURRENT].
 */
object SharedSettings {

    private const val TAG = "SharedSettings"

    private val mainHandler = Handler(Looper.getMainLooper())

    private val backgroundHandler: Handler by lazy {
        val thread = HandlerThread("SharedSettings", Process.THREAD_PRIORITY_BACKGROUND)
        thread.start()
        Handler(thread.looper)
    }

    private val entries = ConcurrentHashMap<String, Entry<*>>()

    @Volatile private var userSwitchReceiverRegistered = false

    private class Entry<T>(
        private val resolver: ContentResolver,
        private val default: T,
        private val read: (ContentResolver) -> T,
    ) {
        // Written only on the main thread.
        val state: MutableState<T>

        init {
            // The first read is synchronous so the first frame already uses the right value. It
            // happens once per setting for the life of the process.
            val initial = runCatching { read(resolver) }
            state = mutableStateOf(initial.getOrDefault(default))
            // Settings can be unavailable this early in boot: try again off the main thread.
            if (initial.isFailure) backgroundHandler.post { refresh() }
        }

        /** Re-reads the value. Runs on the background thread. */
        fun refresh() {
            val value = runCatching { read(resolver) }.getOrDefault(default)
            mainHandler.post { if (state.value != value) state.value = value }
        }
    }

    /**
     * Returns the shared state for the setting at [uri], creating and observing it on first use.
     * [key] identifies the setting together with its type and default, since the same [uri] can
     * be read with different defaults.
     */
    @Suppress("UNCHECKED_CAST")
    fun <T> state(
        context: Context,
        key: String,
        uri: Uri,
        default: T,
        read: (ContentResolver) -> T,
    ): State<T> {
        entries[key]?.let {
            return it.state as State<T>
        }
        val appContext = context.applicationContext ?: context
        val resolver = appContext.contentResolver
        val entry =
            entries.computeIfAbsent(key) {
                Entry(resolver, default, read).also { created ->
                    val observer =
                        object : ContentObserver(backgroundHandler) {
                            override fun onChange(selfChange: Boolean) {
                                created.refresh()
                            }
                        }
                    try {
                        resolver.registerContentObserver(uri, false, observer, UserHandle.USER_ALL)
                    } catch (e: RuntimeException) {
                        // Keep the value read above rather than failing the composition.
                        Log.w(TAG, "Could not observe $uri", e)
                    }
                }
            }
        registerUserSwitchReceiver(appContext)
        return entry.state as State<T>
    }

    private fun registerUserSwitchReceiver(context: Context) {
        if (userSwitchReceiverRegistered) return
        synchronized(this) {
            if (userSwitchReceiverRegistered) return
            context.registerReceiver(
                object : BroadcastReceiver() {
                    override fun onReceive(context: Context, intent: Intent) {
                        // Values are read for the current user, and nothing changes in the
                        // settings themselves when the user switches.
                        entries.values.forEach { it.refresh() }
                    }
                },
                IntentFilter(Intent.ACTION_USER_SWITCHED),
                /* broadcastPermission= */ null,
                backgroundHandler,
                Context.RECEIVER_NOT_EXPORTED,
            )
            userSwitchReceiverRegistered = true
        }
    }

    fun systemInt(context: Context, name: String, default: Int): State<Int> =
        state(context, "system/int/$name/$default", Settings.System.getUriFor(name), default) {
            Settings.System.getIntForUser(it, name, default, UserHandle.USER_CURRENT)
        }

    fun secureInt(context: Context, name: String, default: Int): State<Int> =
        state(context, "secure/int/$name/$default", Settings.Secure.getUriFor(name), default) {
            Settings.Secure.getIntForUser(it, name, default, UserHandle.USER_CURRENT)
        }

    fun secureString(context: Context, name: String): State<String?> =
        state(context, "secure/string/$name", Settings.Secure.getUriFor(name), null) {
            Settings.Secure.getStringForUser(it, name, UserHandle.USER_CURRENT)
        }
}

/** The current value of the [Settings.System] integer [name], shared across the process. */
@Composable
fun rememberSystemIntSetting(name: String, default: Int): Int {
    val context = LocalContext.current
    return remember(name, default) { SharedSettings.systemInt(context, name, default) }.value
}

/** The current value of the [Settings.Secure] integer [name], shared across the process. */
@Composable
fun rememberSecureIntSetting(name: String, default: Int): Int {
    val context = LocalContext.current
    return remember(name, default) { SharedSettings.secureInt(context, name, default) }.value
}

/** The current value of the [Settings.Secure] string [name], shared across the process. */
@Composable
fun rememberSecureStringSetting(name: String): String? {
    val context = LocalContext.current
    return remember(name) { SharedSettings.secureString(context, name) }.value
}
