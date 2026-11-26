package com.android.systemui.statusbar.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper

class MediaSessionTrackHelper private constructor(context: Context) {

    interface MediaMetadataListener {
        fun onMediaMetadataChanged() {}
        fun onPlaybackStateChanged() {}
    }

    private val appContext = context.applicationContext
    private val sessionManager =
        appContext.getSystemService(Context.MEDIA_SESSION_SERVICE) as? MediaSessionManager
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = LinkedHashSet<MediaMetadataListener>()

    private var currentController: MediaController? = null
    private var controllerCallback: MediaController.Callback? = null

    // The current controller's metadata and state, as its callbacks deliver them. Reading them from
    // the controller is a binder call, and listeners read them on every change.
    @Volatile private var currentMetadata: MediaMetadata? = null
    @Volatile private var currentPlaybackState: PlaybackState? = null
    private var sessionsListening = false

    private val sessionsChangedListener = MediaSessionManager.OnActiveSessionsChangedListener {
        refreshActiveSessions()
    }

    private fun pickController(): MediaController? {
        val sessions =
            try {
                sessionManager?.getActiveSessions(null) ?: emptyList()
            } catch (_: Exception) {
                emptyList()
            }
        return sessions.maxByOrNull {
            when (it.playbackState?.state) {
                PlaybackState.STATE_PLAYING -> 5
                PlaybackState.STATE_BUFFERING -> 4
                PlaybackState.STATE_FAST_FORWARDING,
                PlaybackState.STATE_REWINDING -> 3
                PlaybackState.STATE_PAUSED -> 2
                else -> 0
            }
        }
    }

    private fun attachCallback(ctrl: MediaController?) {
        if (currentController === ctrl) return
        controllerCallback?.let { cb -> currentController?.unregisterCallback(cb) }
        currentController = ctrl
        controllerCallback = null
        currentMetadata = ctrl?.metadata
        currentPlaybackState = ctrl?.playbackState
        if (ctrl == null) return

        val cb =
            object : MediaController.Callback() {
                override fun onMetadataChanged(metadata: MediaMetadata?) {
                    currentMetadata = metadata
                    notifyListeners()
                }

                override fun onPlaybackStateChanged(state: PlaybackState?) {
                    currentPlaybackState = state
                    notifyListeners()
                }

                override fun onSessionDestroyed() {
                    refreshActiveSessions()
                }
            }
        ctrl.registerCallback(cb, mainHandler)
        controllerCallback = cb
    }

    fun refreshActiveSessions() {
        val active = pickController()
        attachCallback(active)
        notifyListeners()
    }

    private fun notifyListeners() {
        val listenersCopy = synchronized(listeners) { ArrayList(listeners) }
        for (listener in listenersCopy) {
            listener.onMediaMetadataChanged()
            listener.onPlaybackStateChanged()
        }
    }

    fun addMediaMetadataListener(listener: MediaMetadataListener) {
        synchronized(listeners) {
            val wasEmpty = listeners.isEmpty()
            listeners.add(listener)
            if (wasEmpty && !sessionsListening) {
                try {
                    sessionManager?.addOnActiveSessionsChangedListener(
                        sessionsChangedListener,
                        null,
                        mainHandler,
                    )
                    sessionsListening = true
                } catch (_: Exception) {}
                val active = pickController()
                attachCallback(active)
            }
        }
        listener.onMediaMetadataChanged()
        listener.onPlaybackStateChanged()
    }

    fun removeMediaMetadataListener(listener: MediaMetadataListener) {
        synchronized(listeners) {
            listeners.remove(listener)
            if (listeners.isEmpty() && sessionsListening) {
                try {
                    sessionManager?.removeOnActiveSessionsChangedListener(sessionsChangedListener)
                } catch (_: Exception) {}
                sessionsListening = false
                currentController?.let { ctrl ->
                    controllerCallback?.let { cb -> ctrl.unregisterCallback(cb) }
                }
                currentController = null
                controllerCallback = null
                currentMetadata = null
                currentPlaybackState = null
            }
        }
    }

    fun getCurrentMediaMetadata(): MediaMetadata? = currentMetadata

    fun getMediaControllerPlaybackState(): PlaybackState? = currentPlaybackState

    fun isMediaPlaying(): Boolean = currentPlaybackState?.state == PlaybackState.STATE_PLAYING

    fun getMediaBitmap(): Bitmap? =
        currentMetadata?.let {
            it.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: it.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
        }

    fun getCurrentMediaController(): MediaController? = currentController ?: pickController()

    companion object {
        @Volatile private var instance: MediaSessionTrackHelper? = null

        @JvmStatic
        fun getInstance(context: Context): MediaSessionTrackHelper =
            instance
                ?: synchronized(this) {
                    instance ?: MediaSessionTrackHelper(context).also { instance = it }
                }
    }
}
