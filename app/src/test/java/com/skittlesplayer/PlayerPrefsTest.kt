package com.skittlesplayer

import android.app.Application
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class PlayerPrefsTest {

    private val prefs = PlayerPrefs(ApplicationProvider.getApplicationContext<Application>())

    @Test
    fun positionsAreStoredPerUri() {
        val a = Uri.parse("content://media/external/video/1")
        val b = Uri.parse("http://example.com/b.mp4")
        prefs.savePosition(a, 61_000)
        prefs.savePosition(b, 7_500)
        assertEquals(61_000L, prefs.position(a))
        assertEquals(7_500L, prefs.position(b))
        prefs.clearPosition(a)
        assertEquals(0L, prefs.position(a))
    }

    @Test
    fun defaults() {
        assertEquals(1f, prefs.speed)
        assertEquals(AspectMode.FIT, prefs.aspect)
        assertEquals(BufferMode.SMOOTH, prefs.bufferMode)
    }

    @Test
    fun qualityLabel() {
        val video = Format.Builder().setWidth(1920).setHeight(1080).setSampleMimeType(MimeTypes.VIDEO_H264).build()
        val audio = Format.Builder().setChannelCount(6).setSampleMimeType(MimeTypes.AUDIO_E_AC3).build()
        assertEquals("1080p · 5.1", MediaFormats.quality(video, audio))

        val hdr = Format.Builder().setWidth(3840).setHeight(2160)
            .setColorInfo(ColorInfo.Builder().setColorTransfer(C.COLOR_TRANSFER_ST2084).build())
            .build()
        assertEquals("4K · HDR10 · Stereo", MediaFormats.quality(hdr, Format.Builder().setChannelCount(2).build()))
    }

    @Test
    fun audioTrackLabel() {
        val format = Format.Builder().setLanguage("en").setChannelCount(6).setSampleMimeType(MimeTypes.AUDIO_AC3).build()
        assertEquals("English · 5.1 · AC-3", MediaFormats.audioTrackLabel(format, 1))
    }
}
