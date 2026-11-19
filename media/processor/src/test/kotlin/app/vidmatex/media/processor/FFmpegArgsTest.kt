package app.vidmatex.media.processor

import app.vidmatex.core.model.EmbedStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for [FFmpegArgs] builder (JVM-pure: no Android imports). Verifies that every
 * builder method emits the expected allowlisted token, and that the three production
 * vectors (audio extraction, soft embed, hard burn, mux) contain the required markers:
 * - extract audio: `libmp3lame` + 192k, video dropped (`-vn`)
 * - soft embed: `mov_text`, `-map`, stream copy, `+faststart`, language metadata
 * - hard burn: `subtitles=` filter, `charenc=UTF-8`, every `force_style` key,
 *   `libx264`, `crf 18`, `preset medium`, audio copy
 * - mux: `-map 0:v`, `-map 1:a`, stream copy, `-shortest`
 *
 * Argument lists are `List<String>` tokens; paths are never shell-escaped into a
 * command string, and filter-graph paths are quoted/escaped by [FFmpegArgs.escapeFilterPath].
 *
 * Written but NOT executed here (no Android SDK / JDK 17 in this environment).
 * Status: COMPLETE_NOT_RUN — run via `./gradlew testDebugUnitTest` in CI.
 */
class FFmpegArgsTest {

    // ------------------------------------------------------------- audio extraction

    @Test
    fun `extract audio vector drops video and uses libmp3lame 192k`() {
        val args = FFmpegArgs()
            .overwrite()
            .logLevel(FFmpegArgs.LogLevel.ERROR)
            .input("/tmp/video.mp4")
            .noVideo()
            .audioCodec("libmp3lame")
            .audioBitrate(192)
            .output("/tmp/audio.mp3")
            .build()

        assertTrue(args.contains("-y"))
        assertTrue(args.contains("-loglevel"))
        assertTrue(args.contains("error"))
        assertTrue(args.contains("-i"))
        assertTrue(args.contains("/tmp/video.mp4"))
        assertTrue("video stream must be dropped", args.contains("-vn"))
        assertTrue(args.contains("-c:a"))
        assertTrue("MP3 encoder", args.contains("libmp3lame"))
        assertTrue(args.contains("-b:a"))
        assertTrue("192k bitrate", args.contains("192k"))
        assertEquals("/tmp/audio.mp3", args.last())
    }

    // ------------------------------------------------------------------ soft embed

    @Test
    fun `soft embed vector contains map copy mov_text faststart and language metadata`() {
        val args = FFmpegArgs()
            .overwrite()
            .input("/tmp/video.mp4")
            .input("/tmp/sub.srt")
            .map("0")
            .map("1")
            .videoCodec("copy")
            .audioCodec("copy")
            .subtitleCodec("mov_text")
            .metadataLanguage("s:0", "ar")
            .movFlags("+faststart")
            .output("/tmp/out.mp4")
            .build()

        assertTrue(args.contains("-i"))
        assertTrue(args.contains("/tmp/video.mp4"))
        assertTrue(args.contains("/tmp/sub.srt"))
        assertTrue(args.contains("-map"))
        assertTrue(args.contains("0"))
        assertTrue(args.contains("1"))
        assertTrue(args.contains("-c:v"))
        assertTrue(args.contains("-c:a"))
        assertTrue("stream copy", args.contains("copy"))
        assertTrue(args.contains("-c:s"))
        assertTrue("soft subtitle track codec", args.contains("mov_text"))
        assertTrue(args.contains("-metadata:s:s:0"))
        assertTrue(args.contains("language=ar"))
        assertTrue(args.contains("-movflags"))
        assertTrue("faststart for progressive MP4", args.contains("+faststart"))
        assertEquals("/tmp/out.mp4", args.last())
    }

    @Test
    fun `soft embed omits language metadata when language blank`() {
        val args = FFmpegArgs()
            .overwrite()
            .input("/tmp/video.mp4")
            .input("/tmp/sub.ass")
            .map("0")
            .map("1")
            .videoCodec("copy")
            .audioCodec("copy")
            .subtitleCodec("mov_text")
            .movFlags("+faststart")
            .output("/tmp/out.mp4")
            .build()

        assertFalse(args.any { it.startsWith("language=") })
    }

    // ------------------------------------------------------------------ hard burn

    @Test
    fun `hard burn vector contains subtitles filter charenc UTF-8 and all force style keys`() {
        val style = EmbedStyle(fontSize = 28, fontName = "Arial", bold = true)
        val args = FFmpegArgs()
            .overwrite()
            .input("/tmp/video.mp4")
            .subtitlesFilter("/tmp/sub.srt", style)
            .videoCodec("libx264")
            .crf(18)
            .preset(FFmpegArgs.Preset.MEDIUM)
            .audioCodec("copy")
            .output("/tmp/out.mp4")
            .build()

        val vfIdx = args.indexOf("-vf")
        assertTrue(vfIdx >= 0)
        val filter = args[vfIdx + 1]

        assertTrue("libass subtitles filter", filter.contains("subtitles="))
        assertTrue("UTF-8 decoding for Arabic/CJK", filter.contains("charenc=UTF-8"))
        assertTrue("force_style block", filter.contains("force_style="))
        // Every EmbedStyle field must map to a force_style key.
        assertTrue(filter.contains("FontName=Arial"))
        assertTrue(filter.contains("Fontsize=28"))
        assertTrue(filter.contains("PrimaryColour="))
        assertTrue(filter.contains("OutlineColour="))
        assertTrue(filter.contains("BackColour="))
        assertTrue(filter.contains("Outline="))
        assertTrue(filter.contains("Alignment="))
        assertTrue(filter.contains("MarginV="))
        assertTrue(filter.contains("Bold=1"))

        assertTrue(args.contains("-c:v"))
        assertTrue("H.264 re-encode for burn-in", args.contains("libx264"))
        assertTrue(args.contains("-crf"))
        assertTrue("visually-lossless quality", args.contains("18"))
        assertTrue(args.contains("-preset"))
        assertTrue(args.contains("medium"))
        assertTrue(args.contains("-c:a"))
        assertTrue("audio copied, not re-encoded", args.contains("copy"))
        assertEquals("/tmp/out.mp4", args.last())
    }

    @Test
    fun `hard burn works with ASS subtitle source path`() {
        val style = EmbedStyle(fontName = "Noto Sans Arabic")
        val args = FFmpegArgs()
            .overwrite()
            .input("/tmp/video.mp4")
            .subtitlesFilter("/tmp/sub.ass", style)
            .videoCodec("libx264")
            .crf(18)
            .preset(FFmpegArgs.Preset.MEDIUM)
            .audioCodec("copy")
            .output("/tmp/out.mp4")
            .build()

        val filter = args[args.indexOf("-vf") + 1]
        assertTrue(filter.contains("subtitles="))
        assertTrue(filter.contains("/tmp/sub.ass"))
        assertTrue(filter.contains("charenc=UTF-8"))
        assertTrue(filter.contains("FontName=Noto Sans Arabic"))
    }

    // ------------------------------------------------------------------------- mux

    @Test
    fun `mux vector maps video and audio copies with shortest`() {
        val args = FFmpegArgs()
            .overwrite()
            .input("/tmp/v.mp4")
            .input("/tmp/a.m4a")
            .map("0:v")
            .map("1:a")
            .videoCodec("copy")
            .audioCodec("copy")
            .shortest()
            .output("/tmp/out.mp4")
            .build()

        assertTrue(args.contains("-map"))
        assertTrue(args.contains("0:v"))
        assertTrue(args.contains("1:a"))
        assertTrue(args.contains("-c:v"))
        assertTrue(args.contains("-c:a"))
        assertTrue("no re-encode", args.contains("copy"))
        assertTrue(args.contains("-shortest"))
        assertEquals("/tmp/out.mp4", args.last())
    }

    // ------------------------------------------------------- remaining builder API

    @Test
    fun `noAudio drops audio stream`() {
        val args = FFmpegArgs().input("/tmp/v.mp4").noAudio().output("/tmp/out.mp4").build()
        assertTrue(args.contains("-an"))
    }

    @Test
    fun `copyCodecs emits generic stream copy`() {
        val args = FFmpegArgs().input("/tmp/v.mkv").copyCodecs().output("/tmp/out.mp4").build()
        assertTrue(args.contains("-c"))
        assertTrue(args.contains("copy"))
    }

    @Test
    fun `format sets container muxer token`() {
        val args = FFmpegArgs().format("mp3").output("/tmp/a.mp3").build()
        assertTrue(args.contains("-f"))
        assertTrue(args.contains("mp3"))
    }

    @Test
    fun `logLevel emits every allowlisted literal`() {
        FFmpegArgs.LogLevel.values().forEach { level ->
            val args = FFmpegArgs().logLevel(level).build()
            assertTrue(args.contains("-loglevel"))
            assertTrue("token for $level", args.contains(level.token))
        }
    }

    @Test
    fun `preset emits every allowlisted x264 preset token`() {
        FFmpegArgs.Preset.values().forEach { preset ->
            val args = FFmpegArgs().preset(preset).build()
            assertTrue(args.contains("-preset"))
            assertTrue("token for $preset", args.contains(preset.token))
        }
    }

    @Test
    fun `crf is clamped to valid x264 range 0 51`() {
        val high = FFmpegArgs().crf(999).build()
        val idx = high.indexOf("-crf")
        assertTrue(idx >= 0)
        assertEquals("out-of-range CRF coerced to 51", "51", high[idx + 1])

        val low = FFmpegArgs().crf(-10).build()
        val lowIdx = low.indexOf("-crf")
        assertEquals("negative CRF coerced to 0", "0", low[lowIdx + 1])
    }

    @Test
    fun `escapeFilterPath escapes backslash quote and colon for filter graph`() {
        val escaped = FFmpegArgs.escapeFilterPath("C:\\dir\\file's name.srt")
        // Backslashes doubled, single quotes escaped, Windows drive colon escaped.
        assertTrue(escaped.contains("\\\\"))
        assertTrue(escaped.contains("'\\''"))
        assertFalse("raw colon must be escaped", escaped.contains("C:"))
    }

    @Test
    fun `subtitles filter path is escaped inside single quotes`() {
        val style = EmbedStyle()
        val args = FFmpegArgs()
            .input("/tmp/v.mp4")
            .subtitlesFilter("/data/My Dir/srt's.srt", style)
            .output("/tmp/out.mp4")
            .build()
        val filter = args[args.indexOf("-vf") + 1]
        assertTrue(filter.startsWith("subtitles='"))
        assertTrue("embedded quote escaped", filter.contains("'\\''"))
        assertFalse("unquoted raw path with space should not appear verbatim",
            filter.contains("/data/My Dir/srt's.srt"))
    }

    @Test
    fun `no shell metacharacters ever appear as separate args`() {
        // Every arg is a discrete token; a concatenated shell command would contain
        // ";", "&&", "|" as standalone tokens.
        val args = FFmpegArgs()
            .input("/tmp/v; rm -rf /") // malicious path is just a filename token
            .output("/tmp/out")
            .build()
        // The malicious string remains one arg, never split or interpreted.
        assertTrue(args.contains("/tmp/v; rm -rf /"))
        assertFalse(args.contains(";"))
        assertFalse(args.contains("&&"))
        assertFalse(args.contains("|"))
    }

    @Test
    fun `build returns an immutable list copy`() {
        val builder = FFmpegArgs().input("/tmp/v.mp4").output("/tmp/out.mp4")
        val once = builder.build()
        val twice = builder.build()
        assertEquals(once, twice)
    }

    // ------------------------------------------------------- exact order + new ops
    @Test
    fun `extract vector exact token list and order`() {
        val args = FFmpegArgs()
            .overwrite()
            .logLevel(FFmpegArgs.LogLevel.ERROR)
            .input("/tmp/video.mp4")
            .noVideo()
            .audioCodec("libmp3lame")
            .audioBitrate(192)
            .output("/tmp/audio.mp3")
            .build()
        assertEquals(
            listOf("-y", "-loglevel", "error", "-i", "/tmp/video.mp4",
                "-vn", "-c:a", "libmp3lame", "-b:a", "192k", "/tmp/audio.mp3"),
            args,
        )
    }

    @Test
    fun `segment vector emits muxer segment time reset timestamps and pattern in order`() {
        val args = FFmpegArgs().input("/in.wav").segment(120L, "/tmp/chunk_%03d.mp3").build()
        assertEquals("segment", args[args.indexOf("-f") + 1])
        assertEquals("120", args[args.indexOf("-segment_time") + 1])
        assertEquals("1", args[args.indexOf("-reset_timestamps") + 1])
        assertEquals("/tmp/chunk_%03d.mp3", args.last())
    }

    @Test
    fun `segment coerces non-positive duration to one second`() {
        val args = FFmpegArgs().input("/in.wav").segment(0L, "/c_%03d.mp3").build()
        assertEquals("1", args[args.indexOf("-segment_time") + 1])
    }

    @Test
    fun `softSubtitleCodecFor maps containers and rejects non-embeddable types`() {
        assertEquals("mov_text", FFmpegArgs.softSubtitleCodecFor("mp4"))
        assertEquals("mov_text", FFmpegArgs.softSubtitleCodecFor("M4V"))
        assertEquals("webvtt", FFmpegArgs.softSubtitleCodecFor("webm"))
        assertEquals("srt", FFmpegArgs.softSubtitleCodecFor("mkv"))
        assertNull(FFmpegArgs.softSubtitleCodecFor("mp3"))
        assertNull(FFmpegArgs.softSubtitleCodecFor("txt"))
        assertTrue(FFmpegArgs.supportsSoftEmbed("webm"))
        assertFalse(FFmpegArgs.supportsSoftEmbed("mp3"))
    }

    @Test
    fun `muxerForContainer maps known containers with mp4 fallback`() {
        assertEquals("mp4", FFmpegArgs.muxerForContainer("mp4"))
        assertEquals("webm", FFmpegArgs.muxerForContainer("webm"))
        assertEquals("matroska", FFmpegArgs.muxerForContainer("mkv"))
        assertEquals("mp4", FFmpegArgs.muxerForContainer("unknown"))
    }

    @Test
    fun `remux copy vector uses generic stream copy and format token`() {
        val args = FFmpegArgs().input("/in.mkv").copyCodecs().format("mp4").output("/out.mp4").build()
        assertEquals("copy", args[args.indexOf("-c") + 1])
        assertEquals("mp4", args[args.indexOf("-f") + 1])
    }

    @Test
    fun `path with spaces remains a single discrete argument token`() {
        val args = FFmpegArgs().input("/data/My Dir/video file.mp4").output("/out x.mp4").build()
        assertEquals("/data/My Dir/video file.mp4", args[args.indexOf("-i") + 1])
        assertEquals("/out x.mp4", args.last())
    }

    @Test
    fun `filter escaping keeps spaces and escapes colon inside one quoted filter token`() {
        val args = FFmpegArgs()
            .input("/v.mp4")
            .subtitlesFilter("/data/My Dir/clip: srt.srt", EmbedStyle())
            .audioCodec("copy")
            .output("/hard.mp4")
            .build()
        val vf = args[args.indexOf("-vf") + 1]
        assertTrue(vf.startsWith("subtitles='/data/My Dir/clip\\: srt.srt':force_style="))
        assertTrue(vf.endsWith(":charenc=UTF-8"))
    }

    // ------------------------------------------------- format-aware soft codec (MKV ASS)
    @Test
    fun `format-aware soft codec picks ass for mkv when subtitle is ass`() {
        assertEquals("ass", FFmpegArgs.softSubtitleCodecFor("mkv", "ass"))
        assertEquals("ass", FFmpegArgs.softSubtitleCodecFor("MKV", "ASS"))
        assertEquals("srt", FFmpegArgs.softSubtitleCodecFor("mkv", "srt"))
        assertEquals("srt", FFmpegArgs.softSubtitleCodecFor("mkv", null))
        // Container-mandated codecs ignore the subtitle format.
        assertEquals("mov_text", FFmpegArgs.softSubtitleCodecFor("mp4", "ass"))
        assertEquals("webvtt", FFmpegArgs.softSubtitleCodecFor("webm", "srt"))
        assertNull(FFmpegArgs.softSubtitleCodecFor("mp3", "srt"))
        // Single-arg overload defaults MKV to srt (backward compatible).
        assertEquals("srt", FFmpegArgs.softSubtitleCodecFor("mkv"))
    }

    @Test
    fun `soft embed planner vector uses ass codec and language metadata for mkv`() {
        val job = FFmpegJob.softEmbed(
            videoPath = "/v.mkv",
            subtitlePath = "/sub.ass",
            outputPath = "/out.mkv",
            languageTag = "ar",
            targetContainer = "mkv",
            subtitleFormat = "ass",
        )
        val args = FFmpegJobPlanner.vector(job)
        assertTrue(args.contains("-c:s"))
        assertTrue("MKV ASS track", args.contains("ass"))
        assertTrue(args.contains("-metadata:s:s:0"))
        assertTrue(args.contains("language=ar"))
        // MKV/WebM soft embeds must NOT emit +faststart (MP4-only flag).
        assertFalse(args.contains("+faststart"))
    }

    // ------------------------------------------------- Arabic / RTL force_style
    @Test
    fun `arabic rtl force_style carries explicit arabic font and charenc UTF-8`() {
        val style = EmbedStyle(fontName = "Noto Naskh Arabic", fontSize = 32, bold = false)
        val args = FFmpegArgs()
            .input("/v.mp4")
            .subtitlesFilter("/sub_ar.srt", style)
            .videoCodec("libx264")
            .audioCodec("copy")
            .output("/out.mp4")
            .build()
        val vf = args[args.indexOf("-vf") + 1]
        assertTrue(vf.contains("FontName=Noto Naskh Arabic"))
        assertTrue(vf.contains("Fontsize=32"))
        assertTrue(vf.contains("Bold=0"))
        assertTrue(vf.contains("charenc=UTF-8"))
    }

    @Test
    fun `blank font name falls back to sans-serif default`() {
        val style = EmbedStyle(fontName = "   ")
        val args = FFmpegArgs().input("/v.mp4").subtitlesFilter("/s.srt", style).output("/o.mp4").build()
        val vf = args[args.indexOf("-vf") + 1]
        assertTrue(vf.contains("FontName=${FFmpegArgs.FALLBACK_FONT_NAME}"))
    }

    // ------------------------------------------------- path escaping edge cases
    @Test
    fun `path with spaces apostrophe and arabic chars stays one quoted filter token`() {
        val weird = "/data/مجلد عربي/Omar's video (2024).srt"
        val args = FFmpegArgs().input("/v.mp4").subtitlesFilter(weird, EmbedStyle()).output("/o.mp4").build()
        val vf = args[args.indexOf("-vf") + 1]
        // The raw path must not appear unescaped; the apostrophe must be escaped.
        assertFalse(vf.contains(weird))
        assertTrue(vf.contains("'\\''"))
        // Arabic chars survive verbatim inside the quoted path.
        assertTrue(vf.contains("مجلد"))
        assertTrue(vf.startsWith("subtitles='"))
    }

    @Test
    fun `hard burn planner vector includes subtitle input and style`() {
        val style = EmbedStyle(fontSize = 26)
        val job = FFmpegJob.hardBurn("/v.mp4", "/s.srt", "/out.mp4", style)
        val args = FFmpegJobPlanner.vector(job, style)
        assertTrue(args.contains("-i"))
        // The subtitle file is referenced inside the -vf filter, not as a separate -i input.
        val vf = args[args.indexOf("-vf") + 1]
        assertTrue(vf.contains("subtitles="))
        assertTrue(vf.contains("/s.srt"))
        assertTrue(vf.contains("charenc=UTF-8"))
        assertTrue(vf.contains("Fontsize=26"))
    }
}
