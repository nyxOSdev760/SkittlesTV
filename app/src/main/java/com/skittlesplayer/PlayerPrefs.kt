package com.skittlesplayer

import android.content.Context
import android.net.Uri
import androidx.media3.ui.AspectRatioFrameLayout

enum class BufferMode(
    val label: String,
    val minBufferMs: Int,
    val maxBufferMs: Int,
    val bufferForPlaybackMs: Int,
    val bufferForPlaybackAfterRebufferMs: Int,
) {
    SMOOTH("Smooth", 60_000, 180_000, 2_500, 5_000),
    BALANCED("Balanced", 30_000, 90_000, 1_500, 3_000),
    FAST_START("Fast start", 15_000, 50_000, 500, 1_500),
}

enum class AspectMode(val label: String, val resizeMode: Int) {
    FIT("Fit", AspectRatioFrameLayout.RESIZE_MODE_FIT),
    FILL("Fill", AspectRatioFrameLayout.RESIZE_MODE_FILL),
    ZOOM("Zoom", AspectRatioFrameLayout.RESIZE_MODE_ZOOM),
    FIXED_WIDTH("Fixed width", AspectRatioFrameLayout.RESIZE_MODE_FIXED_WIDTH),
}

/**
 * Settings plus the last playback position per media URI. Media files themselves are never
 * copied or stored: only "uri -> position" is kept.
 */
class PlayerPrefs(context: Context) {

    private val settings = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val positions = context.getSharedPreferences("positions", Context.MODE_PRIVATE)

    var speed: Float
        get() = settings.getFloat(KEY_SPEED, 1f)
        set(value) = settings.edit().putFloat(KEY_SPEED, value).apply()

    var aspect: AspectMode
        get() = enumOrDefault(settings.getString(KEY_ASPECT, null), AspectMode.FIT)
        set(value) = settings.edit().putString(KEY_ASPECT, value.name).apply()

    var bufferMode: BufferMode
        get() = enumOrDefault(settings.getString(KEY_BUFFER, null), BufferMode.SMOOTH)
        set(value) = settings.edit().putString(KEY_BUFFER, value.name).apply()

    var audioLanguage: String?
        get() = settings.getString(KEY_AUDIO_LANGUAGE, null)
        set(value) = settings.edit().putString(KEY_AUDIO_LANGUAGE, value).apply()

    fun position(uri: Uri): Long =
        positions.getString(uri.toString(), null)?.substringBefore('|')?.toLongOrNull() ?: 0L

    fun savePosition(uri: Uri, positionMs: Long) {
        val editor = positions.edit()
        val all = positions.all
        if (all.size >= MAX_POSITIONS && !all.containsKey(uri.toString())) {
            // Forget the entry that was touched longest ago.
            all.minByOrNull { (it.value as? String)?.substringAfter('|')?.toLongOrNull() ?: 0L }
                ?.let { editor.remove(it.key) }
        }
        editor.putString(uri.toString(), "$positionMs|${System.currentTimeMillis()}").apply()
    }

    fun clearPosition(uri: Uri) {
        positions.edit().remove(uri.toString()).apply()
    }

    private inline fun <reified T : Enum<T>> enumOrDefault(name: String?, default: T): T =
        enumValues<T>().firstOrNull { it.name == name } ?: default

    private companion object {
        const val KEY_SPEED = "speed"
        const val KEY_ASPECT = "aspect"
        const val KEY_BUFFER = "buffer_mode"
        const val KEY_AUDIO_LANGUAGE = "audio_language"
        const val MAX_POSITIONS = 300
    }
}
