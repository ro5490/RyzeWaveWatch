package au.buzz.ryzewave.notify

import android.content.Context
import android.media.AudioManager
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.util.Log
import au.buzz.ryzewave.core.WatchEvent

/** Handles hardware-confirmed P32 D1 07/08/09 button notifications. */
class WatchMusicController(private val context: Context) {
    fun onEvent(event: WatchEvent) {
        if (event !is WatchEvent.MusicControl) return
        if (!WatchNotificationListener.isAccessGranted(context)) {
            Log.w(TAG, "Music button ignored: enable Notification Access for SmartTrax")
            return
        }
        val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
        val sessions = try {
            manager.getActiveSessions(WatchNotificationListener.component(context))
        } catch (e: SecurityException) {
            Log.w(TAG, "Cannot access active media sessions", e)
            return
        }
        val controller = sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: sessions.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PAUSED }
            ?: sessions.firstOrNull()
        if (controller == null) {
            Log.i(TAG, "Music button ignored: no active media session")
            return
        }
        val controls = controller.transportControls
        when (event.action) {
            0x07 -> if (controller.playbackState?.state == PlaybackState.STATE_PLAYING) controls.pause() else controls.play()
            0x08 -> controls.skipToNext()
            0x09 -> controls.skipToPrevious()
            0x0D, 0x0E -> {
                val audio = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                audio.adjustStreamVolume(AudioManager.STREAM_MUSIC,
                    if (event.action == 0x0D) AudioManager.ADJUST_RAISE else AudioManager.ADJUST_LOWER, 0)
            }
            else -> return
        }
        Log.i(TAG, "Music action ${event.action} sent to ${controller.packageName}")
    }
    companion object { private const val TAG = "WatchMusic" }
}
