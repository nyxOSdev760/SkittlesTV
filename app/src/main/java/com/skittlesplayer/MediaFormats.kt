package com.skittlesplayer

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import java.util.Locale

/** Human-readable labels for formats, codecs and languages. */
object MediaFormats {

    /** e.g. "1080p · HDR10 · 5.1" for the title overlay. */
    fun quality(video: Format?, audio: Format?): String {
        val parts = mutableListOf<String>()
        if (video != null) {
            resolutionLabel(video)?.let(parts::add)
            hdrLabel(video)?.let(parts::add)
        }
        if (audio != null) {
            channelsLabel(audio.channelCount)?.let(parts::add)
            if (video == null) codecName(audio.sampleMimeType)?.let(parts::add)
        }
        return parts.joinToString(" · ")
    }

    fun resolutionLabel(format: Format): String? {
        val w = format.width
        val h = format.height
        if (w <= 0 || h <= 0) return null
        val long = maxOf(w, h)
        val short = minOf(w, h)
        return when {
            long >= 3800 || short >= 2100 -> "4K"
            long >= 2500 || short >= 1400 -> "1440p"
            long >= 1900 || short >= 1000 -> "1080p"
            long >= 1260 || short >= 700 -> "720p"
            else -> "${short}p"
        }
    }

    fun hdrLabel(format: Format): String? {
        if (format.sampleMimeType == MimeTypes.VIDEO_DOLBY_VISION) return "Dolby Vision"
        return when (format.colorInfo?.colorTransfer) {
            C.COLOR_TRANSFER_ST2084 -> "HDR10"
            C.COLOR_TRANSFER_HLG -> "HLG"
            else -> null
        }
    }

    fun channelsLabel(count: Int): String? = when {
        count == Format.NO_VALUE || count <= 0 -> null
        count == 1 -> "Mono"
        count == 2 -> "Stereo"
        count == 3 -> "2.1"
        count == 6 -> "5.1"
        count == 8 -> "7.1"
        else -> "$count ch"
    }

    fun codecName(mimeType: String?): String? = when (mimeType) {
        null -> null
        MimeTypes.VIDEO_H264 -> "H.264"
        MimeTypes.VIDEO_H265 -> "HEVC"
        MimeTypes.VIDEO_AV1 -> "AV1"
        MimeTypes.VIDEO_VP9 -> "VP9"
        MimeTypes.VIDEO_VP8 -> "VP8"
        MimeTypes.VIDEO_DOLBY_VISION -> "Dolby Vision"
        MimeTypes.VIDEO_MPEG2 -> "MPEG-2"
        MimeTypes.VIDEO_MP4V -> "MPEG-4"
        MimeTypes.VIDEO_H263 -> "H.263"
        MimeTypes.AUDIO_AAC -> "AAC"
        MimeTypes.AUDIO_AC3 -> "AC-3"
        MimeTypes.AUDIO_E_AC3 -> "E-AC-3"
        MimeTypes.AUDIO_E_AC3_JOC -> "E-AC-3 Atmos"
        MimeTypes.AUDIO_AC4 -> "AC-4"
        MimeTypes.AUDIO_TRUEHD -> "TrueHD"
        MimeTypes.AUDIO_DTS -> "DTS"
        MimeTypes.AUDIO_DTS_HD -> "DTS-HD"
        MimeTypes.AUDIO_DTS_EXPRESS -> "DTS Express"
        MimeTypes.AUDIO_MPEG -> "MP3"
        MimeTypes.AUDIO_MPEG_L2 -> "MP2"
        MimeTypes.AUDIO_OPUS -> "Opus"
        MimeTypes.AUDIO_VORBIS -> "Vorbis"
        MimeTypes.AUDIO_FLAC -> "FLAC"
        MimeTypes.AUDIO_ALAC -> "ALAC"
        MimeTypes.AUDIO_RAW -> "PCM"
        MimeTypes.APPLICATION_SUBRIP -> "SRT"
        MimeTypes.TEXT_SSA -> "SSA"
        MimeTypes.TEXT_VTT -> "WebVTT"
        MimeTypes.APPLICATION_TTML -> "TTML"
        MimeTypes.APPLICATION_PGS -> "PGS"
        MimeTypes.APPLICATION_VOBSUB -> "VobSub"
        MimeTypes.APPLICATION_DVBSUBS -> "DVB"
        MimeTypes.APPLICATION_CEA608, MimeTypes.APPLICATION_CEA708 -> "CC"
        MimeTypes.APPLICATION_TX3G -> "Timed text"
        else -> mimeType.substringAfter('/').removePrefix("x-").uppercase(Locale.US)
    }

    fun languageName(code: String?): String? {
        if (code.isNullOrBlank() || code == C.LANGUAGE_UNDETERMINED) return null
        val name = Locale.forLanguageTag(code).getDisplayName(Locale.getDefault())
        if (name.isBlank()) return code
        return name.replaceFirstChar { it.titlecase(Locale.getDefault()) }
    }

    fun audioTrackLabel(format: Format, position: Int): String {
        val name = languageName(format.language) ?: format.label ?: "Track $position"
        return listOfNotNull(name, channelsLabel(format.channelCount), codecName(format.sampleMimeType))
            .joinToString(" · ")
    }

    fun subtitleTrackLabel(format: Format, position: Int): String {
        val language = languageName(format.language)
        val parts = mutableListOf(language ?: format.label ?: "Track $position")
        if (language != null && !format.label.isNullOrBlank() && format.label != language) {
            parts += format.label!!
        }
        if (format.selectionFlags and C.SELECTION_FLAG_FORCED != 0) parts += "Forced"
        if (format.roleFlags and C.ROLE_FLAG_DESCRIBES_MUSIC_AND_SOUND != 0) parts += "SDH"
        codecName(format.sampleMimeType ?: format.codecs)?.let(parts::add)
        return parts.joinToString(" · ")
    }
}
