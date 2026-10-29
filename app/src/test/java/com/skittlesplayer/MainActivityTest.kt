package com.skittlesplayer

import android.Manifest
import android.app.Application
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import java.io.File

// SDK 30 matches Fire OS 8.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class MainActivityTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private var controller: ActivityController<MainActivity>? = null

    @Before
    fun setUp() {
        shadowOf(app).grantPermissions(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    @After
    fun tearDown() {
        controller?.pause()?.stop()?.destroy()
    }

    private fun launch(intent: Intent? = null): MainActivity {
        val c = Robolectric.buildActivity(MainActivity::class.java, intent).setup()
        controller = c
        idle()
        return c.get()
    }

    private fun videoIntent(): Intent {
        val file = File(app.cacheDir, "My Movie.mkv").apply { writeBytes(ByteArray(64)) }
        return Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(file), "video/x-matroska")
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun MainActivity.press(keyCode: Int) {
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, keyCode))
        dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, keyCode))
        idle()
    }

    private val MainActivity.panel: View get() = findViewById(R.id.settings_panel)
    private val MainActivity.rows: LinearLayout get() = findViewById(R.id.panel_list)
    private fun MainActivity.label(i: Int) = rows.getChildAt(i).findViewById<TextView>(R.id.row_label).text.toString()
    private fun MainActivity.value(i: Int) = rows.getChildAt(i).findViewById<TextView>(R.id.row_value).text.toString()
    private fun MainActivity.checked(i: Int) = rows.getChildAt(i).findViewById<View>(R.id.row_check).visibility == View.VISIBLE

    @Test
    fun launchedWithoutMedia_showsHint() {
        val activity = launch()
        assertEquals(View.VISIBLE, activity.findViewById<View>(R.id.empty_view).visibility)
        assertEquals(
            "Open a video from any app, or press OK to browse",
            activity.findViewById<TextView>(R.id.empty_text).text.toString(),
        )
    }

    @Test
    fun viewIntent_showsFileNameAsTitle() {
        val activity = launch(videoIntent())
        assertEquals("My Movie.mkv", activity.findViewById<TextView>(R.id.title_text).text.toString())
    }

    @Test
    fun menuKey_opensPanelWithAllRows() {
        val activity = launch(videoIntent())
        assertEquals(View.GONE, activity.panel.visibility)
        activity.press(KeyEvent.KEYCODE_MENU)
        assertEquals(View.VISIBLE, activity.panel.visibility)
        val labels = (0 until activity.rows.childCount).map { activity.label(it) }
        assertEquals(
            listOf("Speed", "Audio", "Subtitles", "Screen", "Buffer", "Restart", "Open file", "Info"),
            labels,
        )
        assertEquals("1x", activity.value(0))
        assertEquals("Fit", activity.value(3))
        assertEquals("Smooth", activity.value(4))
        assertTrue(activity.rows.getChildAt(0).isFocused)
    }

    @Test
    fun dpadUp_opensPanel() {
        val activity = launch(videoIntent())
        activity.press(KeyEvent.KEYCODE_DPAD_UP)
        assertEquals(View.VISIBLE, activity.panel.visibility)
    }

    @Test
    fun choosingSpeed_updatesValueAndIsRemembered() {
        val activity = launch(videoIntent())
        activity.press(KeyEvent.KEYCODE_MENU)
        activity.rows.getChildAt(0).performClick()
        idle()

        // Back row + 6 speeds, current one checked and focused.
        assertEquals(7, activity.rows.childCount)
        assertEquals("Speed", activity.label(0))
        assertEquals(View.GONE, activity.findViewById<View>(R.id.panel_header).visibility)
        assertTrue(activity.checked(3))
        assertTrue(activity.rows.getChildAt(3).isFocused)

        activity.rows.getChildAt(5).performClick() // 1.5x
        idle()
        assertEquals(8, activity.rows.childCount)
        assertEquals("1.5x", activity.value(0))
        assertEquals(1.5f, PlayerPrefs(app).speed)
    }

    @Test
    fun back_leavesSubmenuThenClosesPanel() {
        val activity = launch(videoIntent())
        activity.press(KeyEvent.KEYCODE_MENU)
        activity.rows.getChildAt(3).performClick() // Screen
        idle()
        assertEquals(5, activity.rows.childCount)

        activity.press(KeyEvent.KEYCODE_BACK)
        assertEquals(8, activity.rows.childCount)
        assertTrue(activity.rows.getChildAt(3).isFocused)

        activity.press(KeyEvent.KEYCODE_BACK)
        assertEquals(View.GONE, activity.panel.visibility)
        assertFalse(activity.isFinishing)
    }

    @Test
    fun screenAndBufferChoices_arePersisted() {
        val activity = launch(videoIntent())
        activity.press(KeyEvent.KEYCODE_MENU)
        activity.rows.getChildAt(3).performClick()
        idle()
        activity.rows.getChildAt(3).performClick() // Zoom
        idle()
        activity.rows.getChildAt(4).performClick()
        idle()
        activity.rows.getChildAt(3).performClick() // Fast start (rebuilds the player)
        idle()
        assertEquals("Zoom", activity.value(3))
        assertEquals("Fast start", activity.value(4))
        assertEquals(AspectMode.ZOOM, PlayerPrefs(app).aspect)
        assertEquals(BufferMode.FAST_START, PlayerPrefs(app).bufferMode)
    }

    @Test
    fun infoSubmenu_listsDetails() {
        val activity = launch(videoIntent())
        activity.press(KeyEvent.KEYCODE_MENU)
        activity.rows.getChildAt(7).performClick()
        idle()
        assertEquals("Info", activity.label(0))
        assertEquals(
            listOf("Resolution", "Video codec", "Frame rate", "Audio", "Buffered"),
            (1 until activity.rows.childCount).map { activity.label(it) },
        )
        assertTrue(activity.value(5).endsWith("s ahead"))
    }

    @Test
    fun sendIntent_withUrlInText_opensStream() {
        val intent = Intent(Intent.ACTION_SEND)
            .setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "Watch this: http://127.0.0.1:9/live/stream.m3u8 !")
        val activity = launch(intent)
        // Nothing listens on port 9 so the stream itself fails; the link must still be picked up.
        assertEquals("stream.m3u8", activity.findViewById<TextView>(R.id.title_text).text.toString())
    }

    @Test
    fun newIntent_replacesCurrentMedia() {
        val activity = launch(videoIntent())
        val other = File(app.cacheDir, "Second.mp4").apply { writeBytes(ByteArray(64)) }
        controller!!.newIntent(Intent(Intent.ACTION_VIEW).setDataAndType(Uri.fromFile(other), "video/mp4"))
        idle()
        assertEquals("Second.mp4", activity.findViewById<TextView>(R.id.title_text).text.toString())
    }
}
