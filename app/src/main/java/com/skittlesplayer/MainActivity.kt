package com.skittlesplayer

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import androidx.core.view.isVisible
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.media3.ui.DefaultTimeBar
import androidx.media3.ui.PlayerView
import java.util.Locale

@OptIn(UnstableApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var prefs: PlayerPrefs
    private lateinit var playerView: PlayerView
    private lateinit var emptyView: View
    private lateinit var emptyText: TextView
    private lateinit var titleText: TextView
    private lateinit var qualityText: TextView
    private lateinit var panel: SettingsPanel

    private var player: ExoPlayer? = null

    // What is (or should be) playing. Survives player rebuilds and onStop/onStart.
    private var currentUri: Uri? = null
    private var currentMime: String? = null
    private var currentTitle = ""
    private var resumePositionMs = 0L
    private var resumePlayWhenReady = true
    private var savedTrackParams: TrackSelectionParameters? = null
    private var errorMessage: String? = null
    private var pendingMedia: Media? = null

    private val handler = Handler(Looper.getMainLooper())

    private val positionSaver = object : Runnable {
        override fun run() {
            savePosition()
            handler.postDelayed(this, SAVE_INTERVAL_MS)
        }
    }

    private val infoUpdater = object : Runnable {
        override fun run() {
            if (panel.isOpen && panel.submenu == getString(R.string.info)) {
                panel.setValue(INFO_BUFFERED_ROW, bufferedLabel())
                handler.postDelayed(this, 1_000)
            }
        }
    }

    private val pickMedia =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            val data = result.data
            val uri = data?.data
            if (result.resultCode != RESULT_OK || uri == null) return@registerForActivityResult
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // Not a persistable grant (e.g. ACTION_GET_CONTENT); a one-time grant is enough.
            }
            openMedia(Media(uri, data.type ?: contentResolver.getType(uri), null))
        }

    private val requestStorage =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            pendingMedia?.let { openMedia(it, checkPermission = false) }
            pendingMedia = null
        }

    private val playerListener = object : Player.Listener {
        override fun onPlaybackStateChanged(playbackState: Int) {
            if (playbackState == Player.STATE_ENDED) {
                currentUri?.let(prefs::clearPosition)
            }
            if (playbackState == Player.STATE_READY && errorMessage != null) {
                errorMessage = null
                updateEmptyState()
            }
            updateKeepScreenOn()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            updateKeepScreenOn()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            handler.removeCallbacks(positionSaver)
            if (isPlaying) handler.postDelayed(positionSaver, SAVE_INTERVAL_MS) else savePosition()
        }

        override fun onTracksChanged(tracks: Tracks) = updateQuality()

        override fun onVideoSizeChanged(videoSize: VideoSize) = updateQuality()

        override fun onPlayerError(error: PlaybackException) {
            val p = player ?: return
            if (error.errorCode == PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW) {
                p.seekToDefaultPosition()
                p.prepare()
                return
            }
            errorMessage = "${getString(R.string.play_error)}\n${errorReason(error)}"
            updateEmptyState()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = PlayerPrefs(this)

        playerView = findViewById(R.id.player_view)
        emptyView = findViewById(R.id.empty_view)
        emptyText = findViewById(R.id.empty_text)
        titleText = playerView.findViewById(R.id.title_text)
        qualityText = playerView.findViewById(R.id.quality_text)
        panel = SettingsPanel(
            container = findViewById(R.id.settings_panel),
            header = findViewById(R.id.panel_header),
            scroll = findViewById(R.id.panel_scroll),
            list = findViewById(R.id.panel_list),
        )
        panel.onClosed = {
            handler.removeCallbacks(infoUpdater)
            playerView.descendantFocusability = ViewGroup.FOCUS_AFTER_DESCENDANTS
        }

        playerView.resizeMode = prefs.aspect.resizeMode
        extractMedia(intent)?.let { openMedia(it) }
        updateEmptyState()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // singleTask: a file opened from another app replaces whatever is playing. A plain
        // launcher intent carries no media and leaves playback alone.
        val media = extractMedia(intent) ?: return
        setIntent(intent)
        openMedia(media)
    }

    override fun onStart() {
        super.onStart()
        initPlayer()
    }

    override fun onStop() {
        releasePlayer()
        super.onStop()
    }

    // ---------------------------------------------------------------------------------------
    // Incoming media

    private data class Media(val uri: Uri, val mimeType: String?, val title: String?)

    private fun extractMedia(intent: Intent?): Media? {
        intent ?: return null
        val title = intent.getStringExtra(Intent.EXTRA_TITLE) ?: intent.getStringExtra("title")
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.data?.let { Media(it, intent.type, title) }
            Intent.ACTION_SEND -> {
                val stream = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
                if (stream != null) {
                    Media(stream, intent.type?.takeUnless { it.startsWith("text/") }, title)
                } else {
                    intent.getStringExtra(Intent.EXTRA_TEXT)
                        ?.let { URL_PATTERN.find(it)?.value }
                        ?.let { Media(Uri.parse(it), null, title) }
                }
            }
            else -> null
        }
    }

    private fun openMedia(media: Media, checkPermission: Boolean = true) {
        if (checkPermission && needsStoragePermission(media.uri)) {
            pendingMedia = media
            requestStorage.launch(storagePermissions())
            return
        }
        savePosition()
        currentUri = media.uri
        currentMime = media.mimeType
        currentTitle = media.title ?: displayName(media.uri)
        resumePositionMs = prefs.position(media.uri)
        resumePlayWhenReady = true
        savedTrackParams = null
        errorMessage = null
        panel.close()
        updateEmptyState()
        player?.let(::load)
    }

    private fun storagePermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 33) {
            arrayOf(Manifest.permission.READ_MEDIA_VIDEO, Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

    private fun needsStoragePermission(uri: Uri): Boolean =
        Build.VERSION.SDK_INT >= 23 && uri.scheme == "file" &&
            storagePermissions().none {
                ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
            }

    private fun displayName(uri: Uri): String {
        if (uri.scheme == "content") {
            try {
                contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { cursor ->
                        val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        if (column >= 0 && cursor.moveToFirst()) {
                            val name = cursor.getString(column)
                            if (!name.isNullOrBlank()) return name
                        }
                    }
            } catch (_: Exception) {
                // Some providers refuse queries; fall back to the URI.
            }
        }
        return uri.lastPathSegment?.substringAfterLast('/')?.takeIf { it.isNotBlank() }
            ?: uri.host
            ?: uri.toString()
    }

    private fun openPicker() {
        val mimeTypes = arrayOf("video/*", "audio/*")
        val candidates = listOf(
            Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("*/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes),
            // Many Fire TV builds ship without the system document picker.
            Intent(Intent.ACTION_GET_CONTENT)
                .addCategory(Intent.CATEGORY_OPENABLE)
                .setType("video/*")
                .putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes),
        )
        for (candidate in candidates) {
            try {
                pickMedia.launch(candidate)
                return
            } catch (_: ActivityNotFoundException) {
                // Try the next one.
            }
        }
        Toast.makeText(this, R.string.no_picker, Toast.LENGTH_LONG).show()
    }

    // ---------------------------------------------------------------------------------------
    // Player lifecycle

    private fun initPlayer() {
        if (player != null) return
        val mode = prefs.bufferMode
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                mode.minBufferMs,
                mode.maxBufferMs,
                mode.bufferForPlaybackMs,
                mode.bufferForPlaybackAfterRebufferMs,
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setBackBuffer(BACK_BUFFER_MS, true)
            .build()
        val renderersFactory = DefaultRenderersFactory(this)
            .setEnableDecoderFallback(true)
            .setExtensionRendererMode(DefaultRenderersFactory.EXTENSION_RENDERER_MODE_PREFER)
        val trackSelector = DefaultTrackSelector(this).apply {
            setParameters(
                buildUponParameters()
                    .setExceedRendererCapabilitiesIfNecessary(true)
                    .setExceedAudioConstraintsIfNecessary(true)
                    .setExceedVideoConstraintsIfNecessary(true)
                    .setPreferredAudioLanguage(prefs.audioLanguage)
            )
        }
        val httpFactory = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(15_000)
            .setReadTimeoutMs(20_000)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build()

        val exoPlayer = ExoPlayer.Builder(this, renderersFactory)
            .setLoadControl(loadControl)
            .setTrackSelector(trackSelector)
            .setMediaSourceFactory(DefaultMediaSourceFactory(DefaultDataSource.Factory(this, httpFactory)))
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setSeekBackIncrementMs(SEEK_MS)
            .setSeekForwardIncrementMs(SEEK_MS)
            .build()
        exoPlayer.addListener(playerListener)
        exoPlayer.setPlaybackSpeed(prefs.speed)
        player = exoPlayer
        playerView.player = exoPlayer
        load(exoPlayer)
    }

    private fun load(p: ExoPlayer) {
        val uri = currentUri ?: return
        p.trackSelectionParameters = savedTrackParams
            ?: p.trackSelectionParameters.buildUpon()
                .clearOverrides()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                .setPreferredAudioLanguage(prefs.audioLanguage)
                .build()
        val item = MediaItem.Builder()
            .setUri(uri)
            .setMimeType(streamMimeType(uri, currentMime))
            .build()
        p.setMediaItem(item, resumePositionMs)
        p.playWhenReady = resumePlayWhenReady
        p.prepare()
        titleText.text = currentTitle
        updateQuality()
    }

    private fun releasePlayer() {
        val p = player ?: return
        savePosition()
        val ended = p.playbackState == Player.STATE_ENDED
        resumePositionMs = if (ended) 0 else p.currentPosition
        resumePlayWhenReady = p.playWhenReady && !ended
        savedTrackParams = p.trackSelectionParameters
        handler.removeCallbacks(positionSaver)
        p.removeListener(playerListener)
        p.release()
        player = null
        playerView.player = null
        updateKeepScreenOn()
    }

    /** Applies a new buffer mode: a fresh player that continues from the same spot. */
    private fun rebuildPlayer() {
        releasePlayer()
        initPlayer()
    }

    private fun savePosition() {
        val p = player ?: return
        val uri = currentUri ?: return
        if (p.isCurrentMediaItemLive || p.currentMediaItem == null) return
        val position = p.currentPosition
        val duration = p.duration
        val nearEnd = duration != C.TIME_UNSET && duration - position < END_MARGIN_MS
        if (p.playbackState == Player.STATE_ENDED || position < MIN_SAVE_MS || nearEnd) {
            prefs.clearPosition(uri)
        } else {
            prefs.savePosition(uri, position)
        }
    }

    private fun errorReason(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Couldn't connect to the server"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "The server refused the request"
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "File not found"
        PlaybackException.ERROR_CODE_IO_NO_PERMISSION -> "No permission to read this file"
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "This file format isn't supported"
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_MALFORMED,
        PlaybackException.ERROR_CODE_PARSING_MANIFEST_UNSUPPORTED -> "This stream format isn't supported"
        PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
        PlaybackException.ERROR_CODE_DECODER_QUERY_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
        PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "This device can't decode this video"
        PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
        PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "Audio output failed"
        PlaybackException.ERROR_CODE_DRM_UNSPECIFIED -> "Protected (DRM) content isn't supported"
        else -> error.errorCodeName
    }

    private fun updateKeepScreenOn() {
        val p = player
        val active = p != null && p.playWhenReady &&
            (p.playbackState == Player.STATE_READY || p.playbackState == Player.STATE_BUFFERING)
        if (active) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    private fun updateEmptyState() {
        val hasMedia = currentUri != null
        playerView.useController = hasMedia
        val message = errorMessage
        emptyView.isVisible = !hasMedia || message != null
        emptyText.text = if (message != null) {
            "$message\n\n${getString(R.string.empty_message)}"
        } else {
            getString(R.string.empty_message)
        }
    }

    private fun updateQuality() {
        val text = MediaFormats.quality(selectedFormat(C.TRACK_TYPE_VIDEO), selectedFormat(C.TRACK_TYPE_AUDIO))
        qualityText.text = text
        qualityText.isVisible = text.isNotEmpty()
    }

    // ---------------------------------------------------------------------------------------
    // Remote

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        val first = down && event.repeatCount == 0
        when (event.keyCode) {
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                if (down) handleMediaKey(event.keyCode, first)
                return true
            }
            KeyEvent.KEYCODE_MENU -> {
                if (first) if (panel.isOpen) panel.close() else openPanel()
                return true
            }
            KeyEvent.KEYCODE_BACK -> if (panel.isOpen) {
                if (first) panel.back()
                return true
            }
        }

        if (panel.isOpen) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DPAD_LEFT -> {
                    if (first && panel.submenu != null) panel.back()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT -> return true
            }
            return super.dispatchKeyEvent(event)
        }

        if (currentUri == null || errorMessage != null) {
            if (isSelectKey(event.keyCode)) {
                if (first) openPicker()
                return true
            }
            return super.dispatchKeyEvent(event)
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> {
                // From the seek bar, UP moves focus back to the buttons; anywhere else it opens settings.
                if (currentFocus !is DefaultTimeBar) {
                    if (first) openPanel()
                    return true
                }
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (!playerView.isControllerFullyVisible) {
                    if (down) {
                        if (event.keyCode == KeyEvent.KEYCODE_DPAD_LEFT) player?.seekBack() else player?.seekForward()
                        playerView.showController()
                    }
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    private fun isSelectKey(keyCode: Int) = keyCode == KeyEvent.KEYCODE_DPAD_CENTER ||
        keyCode == KeyEvent.KEYCODE_ENTER || keyCode == KeyEvent.KEYCODE_NUMPAD_ENTER ||
        keyCode == KeyEvent.KEYCODE_BUTTON_A

    private fun handleMediaKey(keyCode: Int, firstPress: Boolean) {
        val p = player ?: return
        when (keyCode) {
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> p.seekForward()
            KeyEvent.KEYCODE_MEDIA_REWIND -> p.seekBack()
            else -> {
                if (!firstPress) return
                val play = when (keyCode) {
                    KeyEvent.KEYCODE_MEDIA_PLAY -> true
                    KeyEvent.KEYCODE_MEDIA_PAUSE -> false
                    else -> !p.playWhenReady || p.playbackState == Player.STATE_ENDED
                }
                if (play) {
                    if (p.playbackState == Player.STATE_IDLE) p.prepare()
                    if (p.playbackState == Player.STATE_ENDED) p.seekTo(0)
                    p.play()
                } else {
                    p.pause()
                }
            }
        }
        if (!panel.isOpen) playerView.showController()
    }

    // ---------------------------------------------------------------------------------------
    // Settings panel

    private fun openPanel() {
        if (currentUri == null) return
        playerView.hideController()
        playerView.descendantFocusability = ViewGroup.FOCUS_BLOCK_DESCENDANTS
        panel.open(::mainRows)
    }

    private fun mainRows(): List<SettingsPanel.Row> = listOf(
        SettingsPanel.Row(getString(R.string.speed), R.drawable.ic_speed, speedLabel(prefs.speed), onClick = ::showSpeed),
        SettingsPanel.Row(getString(R.string.audio), R.drawable.ic_audio, currentAudioLabel(), onClick = ::showAudio),
        SettingsPanel.Row(getString(R.string.subtitles), R.drawable.ic_subtitles, currentSubtitleLabel(), onClick = ::showSubtitles),
        SettingsPanel.Row(getString(R.string.screen), R.drawable.ic_aspect, prefs.aspect.label, onClick = ::showAspect),
        SettingsPanel.Row(getString(R.string.buffer), R.drawable.ic_bolt, prefs.bufferMode.label, onClick = ::showBuffer),
        SettingsPanel.Row(getString(R.string.restart), R.drawable.ic_restart) {
            player?.let {
                // Also retries a file that failed to play.
                if (it.playbackState == Player.STATE_IDLE) it.prepare()
                it.seekTo(0)
                it.play()
            }
            panel.close()
        },
        SettingsPanel.Row(getString(R.string.open_file), R.drawable.ic_folder) {
            panel.close()
            openPicker()
        },
        SettingsPanel.Row(getString(R.string.info), R.drawable.ic_info, onClick = ::showInfo),
    )

    private fun showSpeed() {
        val current = prefs.speed
        panel.showSubmenu(getString(R.string.speed), SPEEDS.map { speed ->
            SettingsPanel.Row(speedLabel(speed), checked = speed == current) {
                prefs.speed = speed
                player?.setPlaybackSpeed(speed)
                panel.showMain()
            }
        })
    }

    private fun showAudio() {
        val tracks = trackOptions(C.TRACK_TYPE_AUDIO)
        if (tracks.isEmpty()) {
            panel.showSubmenu(getString(R.string.audio), listOf(SettingsPanel.Row(getString(R.string.none))))
            return
        }
        panel.showSubmenu(getString(R.string.audio), tracks.mapIndexed { i, track ->
            SettingsPanel.Row(MediaFormats.audioTrackLabel(track.format, i + 1), checked = track.selected) {
                val language = track.format.language?.takeUnless { it == C.LANGUAGE_UNDETERMINED }
                player?.let { p ->
                    p.trackSelectionParameters = p.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                        .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.index))
                        .setPreferredAudioLanguage(language)
                        .build()
                }
                if (language != null) prefs.audioLanguage = language
                panel.showMain()
            }
        })
    }

    private fun showSubtitles() {
        val p = player
        val disabled = p == null || C.TRACK_TYPE_TEXT in p.trackSelectionParameters.disabledTrackTypes
        val tracks = trackOptions(C.TRACK_TYPE_TEXT)
        val off = SettingsPanel.Row(getString(R.string.off), checked = disabled || tracks.none { it.selected }) {
            player?.let {
                it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                    .clearOverridesOfType(C.TRACK_TYPE_TEXT)
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                    .build()
            }
            panel.showMain()
        }
        panel.showSubmenu(getString(R.string.subtitles), listOf(off) + tracks.mapIndexed { i, track ->
            SettingsPanel.Row(MediaFormats.subtitleTrackLabel(track.format, i + 1), checked = !disabled && track.selected) {
                player?.let {
                    it.trackSelectionParameters = it.trackSelectionParameters.buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                        .setOverrideForType(TrackSelectionOverride(track.group.mediaTrackGroup, track.index))
                        .build()
                }
                panel.showMain()
            }
        })
    }

    private fun showAspect() {
        val current = prefs.aspect
        panel.showSubmenu(getString(R.string.screen), AspectMode.entries.map { mode ->
            SettingsPanel.Row(mode.label, checked = mode == current) {
                prefs.aspect = mode
                playerView.resizeMode = mode.resizeMode
                panel.showMain()
            }
        })
    }

    private fun showBuffer() {
        val current = prefs.bufferMode
        panel.showSubmenu(getString(R.string.buffer), BufferMode.entries.map { mode ->
            SettingsPanel.Row(mode.label, checked = mode == current) {
                if (mode != prefs.bufferMode) {
                    prefs.bufferMode = mode
                    rebuildPlayer()
                }
                panel.showMain()
            }
        })
    }

    private fun showInfo() {
        val video = selectedFormat(C.TRACK_TYPE_VIDEO)
        val audio = selectedFormat(C.TRACK_TYPE_AUDIO)
        val dash = "—"
        val resolution = video?.takeIf { it.width > 0 && it.height > 0 }?.let { "${it.width} × ${it.height}" }
        val videoCodec = video?.let { listOfNotNull(MediaFormats.codecName(it.sampleMimeType), MediaFormats.hdrLabel(it)).distinct().joinToString(" · ") }
        val fps = video?.frameRate?.takeIf { it > 0 }?.let { String.format(Locale.US, "%.3f", it).trimEnd('0').trimEnd('.') + " fps" }
        val audioText = audio?.let {
            listOfNotNull(
                MediaFormats.codecName(it.sampleMimeType),
                MediaFormats.channelsLabel(it.channelCount),
                it.sampleRate.takeIf { rate -> rate > 0 }?.let { rate -> "${rate / 1000.0} kHz".replace(".0 ", " ") },
            ).joinToString(" · ")
        }
        panel.showSubmenu(getString(R.string.info), listOf(
            SettingsPanel.Row("Resolution", value = resolution ?: dash),
            SettingsPanel.Row("Video codec", value = videoCodec?.ifEmpty { null } ?: dash),
            SettingsPanel.Row("Frame rate", value = fps ?: dash),
            SettingsPanel.Row("Audio", value = audioText?.ifEmpty { null } ?: dash),
            SettingsPanel.Row("Buffered", value = bufferedLabel()),
        ))
        handler.removeCallbacks(infoUpdater)
        handler.postDelayed(infoUpdater, 1_000)
    }

    private fun bufferedLabel(): String {
        val p = player ?: return "—"
        return String.format(Locale.US, "%.1f s ahead", p.totalBufferedDuration / 1000.0)
    }

    private fun currentAudioLabel(): String? {
        val tracks = trackOptions(C.TRACK_TYPE_AUDIO)
        val index = tracks.indexOfFirst { it.selected }
        return if (index >= 0) MediaFormats.audioTrackLabel(tracks[index].format, index + 1) else null
    }

    private fun currentSubtitleLabel(): String {
        val p = player ?: return getString(R.string.off)
        if (C.TRACK_TYPE_TEXT in p.trackSelectionParameters.disabledTrackTypes) return getString(R.string.off)
        val tracks = trackOptions(C.TRACK_TYPE_TEXT)
        val index = tracks.indexOfFirst { it.selected }
        return if (index >= 0) MediaFormats.subtitleTrackLabel(tracks[index].format, index + 1) else getString(R.string.off)
    }

    // ---------------------------------------------------------------------------------------
    // Tracks

    private class TrackOption(val group: Tracks.Group, val index: Int, val format: Format, val selected: Boolean)

    private fun trackOptions(type: Int): List<TrackOption> {
        val p = player ?: return emptyList()
        return p.currentTracks.groups
            .filter { it.type == type }
            .flatMap { group ->
                (0 until group.length)
                    .filter { group.isTrackSupported(it, true) }
                    .map { TrackOption(group, it, group.getTrackFormat(it), group.isTrackSelected(it)) }
            }
    }

    private fun selectedFormat(type: Int): Format? = trackOptions(type).firstOrNull { it.selected }?.format

    private fun speedLabel(speed: Float): String =
        if (speed == speed.toInt().toFloat()) "${speed.toInt()}x" else "${speed}x"

    private fun streamMimeType(uri: Uri, mimeType: String?): String? {
        if (uri.scheme.equals("rtsp", ignoreCase = true)) return MimeTypes.APPLICATION_RTSP
        return when (mimeType?.lowercase(Locale.US)) {
            "application/x-mpegurl", "application/vnd.apple.mpegurl", "audio/x-mpegurl", "audio/mpegurl" ->
                MimeTypes.APPLICATION_M3U8
            "application/dash+xml" -> MimeTypes.APPLICATION_MPD
            "application/vnd.ms-sstr+xml" -> MimeTypes.APPLICATION_SS
            // Progressive files: let the extractors sniff the container.
            else -> null
        }
    }

    private companion object {
        val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)
        val URL_PATTERN = Regex("""(?i)\b(https?|rtsp)://[^\s"'<>]+""")
        const val SEEK_MS = 10_000L
        const val BACK_BUFFER_MS = 30_000
        const val SAVE_INTERVAL_MS = 5_000L
        const val MIN_SAVE_MS = 5_000L
        const val END_MARGIN_MS = 10_000L
        const val INFO_BUFFERED_ROW = 4
    }
}
