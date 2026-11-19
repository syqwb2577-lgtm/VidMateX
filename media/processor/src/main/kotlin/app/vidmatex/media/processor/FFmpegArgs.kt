package app.vidmatex.media.processor

import app.vidmatex.core.model.EmbedStyle

/**
 * Builds FFmpeg argument lists from an allowlist of tokens. No user string is ever
 * concatenated into a command; only app-controlled file paths and numeric/enum style
 * fields enter the argument vector. Paths that appear inside a filter graph are
 * single-quote-escaped here.
 *
 * Supported subtitle inputs for hard burn ([subtitlesFilter], libass):
 * - **SRT** — passed directly; UTF-8 decoded via `charenc=UTF-8`.
 * - **ASS** — passed directly (style overrides applied through `force_style`).
 * - **VTT** — NOT accepted by the `subtitles` filter directly. The parser layer
 *   (`SubtitleParser.convert`) must convert VTT → SRT (or ASS) before burn-in; this
 *   builder never receives a `.vtt` path for a hard-burn vector.
 *
 * Soft embed uses the MP4-only `mov_text` codec; the subtitle source must be SRT/ASS and
 * the output container must be MP4.
 */
class FFmpegArgs {
    private val args = mutableListOf<String>()

    fun input(path: String) = apply { args += "-i"; args += path }
    fun overwrite() = apply { args += "-y" }
    fun map(spec: String) = apply { args += "-map"; args += spec }
    fun videoCodec(codec: String) = apply { args += "-c:v"; args += codec }
    fun audioCodec(codec: String) = apply { args += "-c:a"; args += codec }
    fun subtitleCodec(codec: String) = apply { args += "-c:s"; args += codec }
    fun metadataLanguage(spec: String, lang: String) = apply { args += "-metadata:s:$spec"; args += "language=$lang" }
    fun audioBitrate(kbps: Int) = apply { args += "-b:a"; args += "${kbps}k" }
    fun format(fmt: String) = apply { args += "-f"; args += fmt }
    fun shortest() = apply { args += "-shortest" }
    fun movFlags(flags: String) = apply { args += "-movflags"; args += flags }
    /** x264 CRF quality (allowlisted numeric). Lower = better quality; 18 is visually lossless. */
    fun crf(value: Int) = apply { args += "-crf"; args += "${value.coerceIn(0, 51)}" }
    /** x264 encoding preset (allowlisted fixed token). */
    fun preset(preset: Preset) = apply { args += "-preset"; args += preset.token }
    enum class Preset(val token: String) {
        ULTRAFAST("ultrafast"), SUPERFAST("superfast"), VERYFAST("veryfast"),
        FASTER("faster"), FAST("fast"), MEDIUM("medium"), SLOW("slow"),
        SLOWER("slower"), VERYSLOW("veryslow"),
    }
    /** Copy all streams (allowlisted fixed token). */
    fun copyCodecs() = apply { args += "-c"; args += "copy" }
    /** Drop the video stream (allowlisted fixed token). */
    fun noVideo() = apply { args += "-vn" }
    /** Drop the audio stream (allowlisted fixed token). */
    fun noAudio() = apply { args += "-an" }
    /**
     * Segment/split muxer: emit `pattern`-numbered parts every [seconds] seconds using
     * `-f segment`. [seconds] is coerced to a positive integer; the pattern
     * (e.g. `chunk_%03d.mp3`) is an app-controlled output path. Stream copy is added
     * separately via [copyCodecs] when the source codec is segment-friendly; the Whisper
     * audio path re-encodes to 16 kHz mono MP3 instead (see `WhisperChunkVector`).
     */
    fun segment(seconds: Long, pattern: String) = apply {
        args += "-f"; args += "segment"
        args += "-segment_time"; args += seconds.coerceAtLeast(1L).toString()
        args += "-reset_timestamps"; args += "1"
        args += pattern
    }
    /** Restrict log output. `level` is restricted to an allowlisted literal. */
    fun logLevel(level: LogLevel) = apply { args += "-loglevel"; args += level.token }

    enum class LogLevel(val token: String) { QUIET("quiet"), ERROR("error"), WARNING("warning"), INFO("info") }

    /**
     * Burn-in filter. Style fields are numeric/enum only; path is escaped.
     * `charenc=UTF-8` is required for non-ASCII scripts (Arabic, CJK). `FontName`
     * defaults to a generic sans-serif so libass can shape Arabic; callers that need a
     * specific bundled font should extend [EmbedStyle] (owned by core:model).
     *
     * Inputs: SRT or ASS. VTT must be converted to SRT/ASS by the parser before use
     * (libass has no native WebVTT demuxer). Force-style keys cover font size, font
     * colour, outline colour/width, back colour, alignment (`Alignment`) and vertical
     * position (`MarginV`), bold.
     */
    fun subtitlesFilter(subtitlePath: String, style: EmbedStyle) = apply {
        val escaped = escapeFilterPath(subtitlePath)
        // Fallback to Android's default sans-serif family (includes Arabic glyphs on
        // most devices). Callers burning Arabic/RTL text SHOULD set EmbedStyle.fontName
        // to a font with full Arabic glyph coverage (e.g. "Noto Naskh Arabic") and ship
        // it via `fontsdir` if needed; libass shapes RTL text per the Unicode bidi
        // algorithm, so no explicit RTL flag is required.
        val fontName = style.fontName.takeIf { it.isNotBlank() } ?: FALLBACK_FONT_NAME
        val forceStyle = buildString {
            append("FontName=$fontName,")
            append("Fontsize=${style.fontSize},")
            append("PrimaryColour=${style.fontColor},")
            append("OutlineColour=${style.outlineColor},")
            append("BackColour=${style.backgroundColor},")
            append("Outline=${style.outlineWidth},")
            append("Alignment=${style.alignment},")
            append("MarginV=${style.marginV},")
            append("Bold=${if (style.bold) 1 else 0}")
        }
        args += "-vf"
        // charenc is a filter option (not part of force_style) so libass decodes UTF-8.
        args += "subtitles='$escaped':force_style='$forceStyle':charenc=UTF-8"
    }

    fun output(path: String) = apply { args += path }

    fun build(): List<String> = args.toList()

    companion object {
        /**
         * Default libass font when [EmbedStyle.fontName] is blank. Android's
         * `sans-serif` family (Roboto/Noto fallback) includes Arabic glyphs on most
         * API 24+ devices; callers burning Arabic text should still set an explicit
         * Arabic-capable font for consistent shaping.
         */
        const val FALLBACK_FONT_NAME = "sans-serif"

        /** Escape a path for placement inside single quotes in an FFmpeg filter expression. */
        fun escapeFilterPath(path: String): String =
            path.replace("\\", "\\\\").replace("'", "'\\''").replace(":", "\\:")

        /**
         * Soft-embed subtitle codec for a target container extension, or null when the
         * container has no supported soft-subtitle muxer on the maintained build:
         * - `mp4` / `m4v` / `mov` → `mov_text` (MPEG-4 Timed Text); input must be SRT/VTT
         *   (mov_text does not carry ASS styling; ASS callers should use MKV or hard burn);
         * - `webm` → `webvtt` (WebVTT in WebM; input MUST be converted to VTT first);
         * - `mkv` / `mka` → `srt` (Matroska SRT passthrough) by default, or `ass` when the
         *   caller passes [subtitleFormat]=ASS via [softSubtitleCodecFor] (Matroska carries
         *   full ASS styling);
         * everything else (raw `.mp3`, `.m4a`, unknown) → null; the caller must use
         * hard burn or a sidecar file instead of fabricating a stream.
         *
         * Limits (documented, not enforced here): WebM `webvtt` muxing requires the input
         * to be WebVTT (convert SRT→VTT via [SubtitleParser.convert] before embedding); MP4
         * `mov_text` strips ASS override tags. Media3/ExoPlayer detects `mov_text` (MP4),
         * `srt`/`ass` (MKV) and `webvtt` (WebM) tracks as selectable subtitle streams.
         */
        fun softSubtitleCodecFor(containerExt: String): String? =
            softSubtitleCodecFor(containerExt, subtitleFormat = null)

        /**
         * Format-aware overload: for MKV, selects `ass` when [subtitleFormat] is ASS
         * (case-insensitive), otherwise `srt`. Other containers ignore [subtitleFormat]
         * (mov_text/webvtt are container-mandated). Returns null for unsupported containers.
         */
        fun softSubtitleCodecFor(containerExt: String, subtitleFormat: String?): String? {
            val ext = containerExt.lowercase().removePrefix(".")
            return when (ext) {
                "mp4", "m4v", "mov" -> "mov_text"
                "webm" -> "webvtt"
                "mkv", "mka" -> if (subtitleFormat.equals("ass", ignoreCase = true)) "ass" else "srt"
                else -> null
            }
        }

        /**
         * FFmpeg muxer name for a remux target extension. Stream-copy remux (`-c copy`)
         * requires a muxer that accepts the source codecs; unsupported extensions fall
         * back to `mp4` and the caller validates the intent before invoking FFmpeg.
         */
        fun muxerForContainer(ext: String): String = when (ext.lowercase()) {
            "mp4", "m4v", "mov" -> "mp4"
            "webm" -> "webm"
            "mkv" -> "matroska"
            "mp3" -> "mp3"
            "m4a" -> "ipod"
            else -> "mp4"
        }

        /** True when [softSubtitleCodecFor] returns a codec for this extension. */
        fun supportsSoftEmbed(containerExt: String): Boolean =
            softSubtitleCodecFor(containerExt) != null
    }
}
