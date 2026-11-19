#!/usr/bin/env bash
# ---------------------------------------------------------------------------
# validate_ffmpeg_args.sh — Host-side validation of VidMateX FFmpeg argument
# vectors against the system FFmpeg build (Ubuntu 22.04, ffmpeg 4.4.2).
#
# This script does NOT build the Android app and never invokes Gradle. It runs
# the SAME argument vectors emitted by the Kotlin builders:
#
#   FFmpegArgs.extractAudio  (FFmpegProcessorImpl.extractAudioToMp3)
#   FFmpegArgs.softEmbed     (FFmpegProcessorImpl.embedSoftSubtitles)
#   FFmpegArgs.hardBurn(SRT) (FFmpegProcessorImpl.burnHardSubtitles)
#   FFmpegArgs.hardBurn(ASS) (FFmpegProcessorImpl.burnHardSubtitles)
#   FFmpegArgs.mux           (FFmpegProcessorImpl.muxVideoAudio)
#   Whisper chunk vector     (SpeechToTextImpls.splitIfNeeded)
#
# Each vector is a fixed allowlist of tokens + app-controlled paths, mirroring
# the List<String> built on device; there is no shell-string concatenation of
# user input.
#
# Usage: bash scripts/validate_ffmpeg_args.sh [keep_dir]
# Exit: 0 when every scenario PASSes, 1 otherwise.
# ---------------------------------------------------------------------------
set -u
set -o pipefail

FFMPEG="${FFMPEG_BIN:-/usr/bin/ffmpeg}"
FFPROBE_STRIP="${FFPROBE_STRIP:-}"
WORKDIR="$(mktemp -d -t vidmatex-ffvalidate-XXXXXX)"
FAIL=0

cleanup() {
    if [[ "${1:-}" != "keep" ]]; then
        rm -rf "$WORKDIR"
    else
        echo "[info] workdir kept at $WORKDIR"
    fi
}
trap 'cleanup "${KEEP:-}"' EXIT

pass() { echo "RESULT:PASS: $1"; }
fail() { echo "RESULT:FAIL: $1"; FAIL=1; }

probe() { # stderr stream listing for marker checks
    "$FFMPEG" -hide_banner -i "$1" 2>&1 || true
}

check_nonempty() { # file label
    if [[ -s "$1" ]]; then pass "$2 produced non-empty file ($(stat -c%s "$1") bytes)"; else fail "$2 output $1 missing or empty"; fi
}

has_marker() { # file grep-pattern label
    if probe "$1" | grep -qE "$2"; then pass "$3"; else fail "$3 (pattern '$2' not found in ffmpeg -i output)"; fi
}

lacks_marker() {
    if probe "$1" | grep -qE "$2"; then fail "$3 (unexpected pattern '$2')"; else pass "$3"; fi
}

echo "=================================================================="
echo "VidMateX FFmpeg argument-vector host validation"
echo "workdir: $WORKDIR"
"$FFMPEG" -version | head -1
echo "------------------------------------------------------------------"
echo "SCOPE: argument-vector + ffmpeg syntax/behavior validation on the host"
echo "       system ffmpeg. This does NOT execute the Android AAR/JNI libraries"
echo "       (dev.ffmpegkit-maintained:ffmpeg-kit-full-gpl:8.1.9), ML Kit, or"
echo "       any network service; those remain device/external-service gaps."
echo "       ABI note: the shipped AAR carries arm64-v8a + x86_64 only; 32-bit"
echo "       ARM (armeabi-v7a) has no native libraries and cannot run FFmpegKit."
echo "=================================================================="

# ------------------------------------------------------------ fixtures
# 2-second 320x240 testsrc video with a silent AAC stereo track.
echo "[setup] synthesizing 2s 320x240 testsrc video + silent AAC"
"$FFMPEG" -y -hide_banner -loglevel error \
    -f lavfi -i testsrc=size=320x240:rate=15:duration=2 \
    -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100:duration=2 \
    -c:v libx264 -pix_fmt yuv420p -c:a aac -shortest \
    "$WORKDIR/video.mp4"
check_nonempty "$WORKDIR/video.mp4" "fixture video.mp4"

# Separate audio source for the mux scenario.
"$FFMPEG" -y -hide_banner -loglevel error \
    -f lavfi -i sine=frequency=330:duration=2 \
    -c:a aac -b:a 128k "$WORKDIR/audio.m4a"
check_nonempty "$WORKDIR/audio.m4a" "fixture audio.m4a"

# VP8/WebM fixture for the WebM soft-embed (webvtt) scenario; WebM cannot carry
# H.264 via stream copy, so this small VP8+Vorbis file stands in for a WebM source.
"$FFMPEG" -y -hide_banner -loglevel error \
    -f lavfi -i testsrc=size=320x240:rate=15:duration=2 \
    -f lavfi -i anullsrc=channel_layout=stereo:sample_rate=44100:duration=2 \
    -c:v libvpx -b:v 200k -c:a libvorbis -shortest \
    "$WORKDIR/video.webm"
check_nonempty "$WORKDIR/video.webm" "fixture video.webm (vp8/vorbis)"

# SRT: English cue + Arabic cue (UTF-8).
cat > "$WORKDIR/en_ar.srt" <<'SRT'
1
00:00:00,500 --> 00:00:01,500
Hello, world — English cue line.

2
00:00:01,000 --> 00:00:02,000
مرحبا بالعالم — سطر تجريبي باللغة العربية
SRT

# VTT: same content in WebVTT form. libass cannot consume VTT directly; the
# app parser converts VTT -> SRT before hard burn (see docs/FFMPEG.md).
cat > "$WORKDIR/en_ar.vtt" <<'VTT'
WEBVTT

00:00:00.500 --> 00:00:01.500
Hello from WebVTT

00:00:01.000 --> 00:00:02.000
مرحبا من ويب في تي
VTT

# Minimal ASS file with English + Arabic Dialogue lines.
cat > "$WORKDIR/en_ar.ass" <<'ASS'
[Script Info]
ScriptType: v4.00+
PlayResX: 320
PlayResY: 240
WrapStyle: 0
ScaledBorderAndShadow: yes

[V4+ Styles]
Format: Name, Fontname, Fontsize, PrimaryColour, OutlineColour, BackColour, Bold, Alignment, MarginV
Style: Default,Noto Sans Arabic,24,&H00FFFFFF,&H00000000,&H80000000,-1,2,40

[Events]
Format: Layer, Start, End, Text
Dialogue: 0,0:00:00.50,0:00:01.50,English ASS cue line
Dialogue: 0,0:00:01.00,0:00:02.00,سطر عربي من ملف ASS
ASS

# App-side contract: VTT must be converted to SRT before burn-in. This tiny
# heredoc conversion mirrors SubtitleParser.convert(VTT -> SRT) timestamp
# normalization (dot -> comma, WEBVTT header dropped).
python3 - "$WORKDIR/en_ar.vtt" "$WORKDIR/converted_from_vtt.srt" <<'PY'
import re, sys
src, dst = sys.argv[1], sys.argv[2]
lines = open(src, encoding="utf-8").read().splitlines()
body = [l for l in lines if not l.strip().startswith("WEBVTT")]
ts = re.compile(r"(\d{2}:\d{2}:\d{2})\.(\d{3})\s*-->\s*(\d{2}:\d{2}:\d{2})\.(\d{3})")
out, idx, i = [], 1, 0
while i < len(body):
    m = ts.search(body[i])
    if m:
        out += [str(idx), f"{m.group(1)},{m.group(2)} --> {m.group(3)},{m.group(4)}"]
        i += 1
        text = []
        while i < len(body) and body[i].strip():
            text.append(body[i].strip()); i += 1
        out += text + [""]
        idx += 1
    i += 1
open(dst, "w", encoding="utf-8").write("\n".join(out) + "\n")
PY
[[ -s "$WORKDIR/converted_from_vtt.srt" ]] && pass "VTT -> SRT conversion fixture created" || fail "VTT -> SRT conversion fixture"

# ==================================================== Scenario 1: extract audio
echo
echo "--- Scenario 1: extractAudioToMp3 (libmp3lame 192k, -vn) ---"
echo "vector: -y -loglevel error -i video.mp4 -vn -c:a libmp3lame -b:a 192k out.mp3"
if "$FFMPEG" -y -hide_banner -loglevel error \
    -i "$WORKDIR/video.mp4" \
    -vn -c:a libmp3lame -b:a 192k \
    "$WORKDIR/audio.mp3"; then
    pass "audio extraction exit code 0"
else
    fail "audio extraction exit code non-zero"
fi
check_nonempty "$WORKDIR/audio.mp3" "audio.mp3"
has_marker "$WORKDIR/audio.mp3" "Audio:.*(mp3|MP3)" "audio.mp3 streams MP3 (libmp3lame)"

# ==================================================== Scenario 2: soft embed
echo
echo "--- Scenario 2: embedSoftSubtitles (mov_text, copy, +faststart) ---"
echo "vector: -y -loglevel error -i video.mp4 -i en_ar.srt -map 0 -map 1 -c:v copy -c:a copy -c:s mov_text -metadata:s:s:0 language=ar -movflags +faststart soft.mp4"
if "$FFMPEG" -y -hide_banner -loglevel error \
    -i "$WORKDIR/video.mp4" -i "$WORKDIR/en_ar.srt" \
    -map 0 -map 1 \
    -c:v copy -c:a copy -c:s mov_text \
    -metadata:s:s:0 language=ar \
    -movflags +faststart \
    "$WORKDIR/soft.mp4"; then
    pass "soft embed exit code 0"
else
    fail "soft embed exit code non-zero"
fi
check_nonempty "$WORKDIR/soft.mp4" "soft.mp4"
has_marker "$WORKDIR/soft.mp4" "Subtitle:.*mov_text" "soft.mp4 contains mov_text subtitle stream"
has_marker "$WORKDIR/soft.mp4" "Video:.*h264" "soft.mp4 keeps h264 video stream"
has_marker "$WORKDIR/soft.mp4" "Audio:.*aac" "soft.mp4 keeps aac audio stream"

# ============================================ Scenario 3: hard burn SRT (UTF-8/Arabic)
echo
echo "--- Scenario 3: burnHardSubtitles SRT (libass, charenc=UTF-8, crf 18, medium, audio copy) ---"
echo "vector: -y -loglevel error -i video.mp4 -vf subtitles='en_ar.srt':force_style='FontName=Noto Sans Arabic,Fontsize=24,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,BackColour=&H80000000,Outline=2,Alignment=2,MarginV=40,Bold=1':charenc=UTF-8 -c:v libx264 -crf 18 -preset medium -c:a copy hard_srt.mp4"
if "$FFMPEG" -y -hide_banner -loglevel error \
    -i "$WORKDIR/video.mp4" \
    -vf "subtitles='${WORKDIR}/en_ar.srt':force_style='FontName=Noto Sans Arabic,Fontsize=24,PrimaryColour=&H00FFFFFF,OutlineColour=&H00000000,BackColour=&H80000000,Outline=2,Alignment=2,MarginV=40,Bold=1':charenc=UTF-8" \
    -c:v libx264 -crf 18 -preset medium \
    -c:a copy \
    "$WORKDIR/hard_srt.mp4"; then
    pass "hard burn SRT exit code 0"
else
    fail "hard burn SRT exit code non-zero"
fi
check_nonempty "$WORKDIR/hard_srt.mp4" "hard_srt.mp4"
has_marker "$WORKDIR/hard_srt.mp4" "Video:.*h264" "hard_srt.mp4 re-encoded with libx264"
lacks_marker "$WORKDIR/hard_srt.mp4" "Subtitle:" "hard_srt.mp4 has no standalone subtitle stream (burned into frames)"

# ============================================ Scenario 4: hard burn ASS
echo
echo "--- Scenario 4: burnHardSubtitles ASS (libass direct ASS input) ---"
echo "vector: -y -loglevel error -i video.mp4 -vf subtitles='en_ar.ass':force_style='...':charenc=UTF-8 -c:v libx264 -crf 18 -preset medium -c:a copy hard_ass.mp4"
if "$FFMPEG" -y -hide_banner -loglevel error \
    -i "$WORKDIR/video.mp4" \
    -vf "subtitles='${WORKDIR}/en_ar.ass':force_style='FontName=Noto Sans Arabic,Fontsize=24,Outline=2,Alignment=2,MarginV=40,Bold=1':charenc=UTF-8" \
    -c:v libx264 -crf 18 -preset medium \
    -c:a copy \
    "$WORKDIR/hard_ass.mp4"; then
    pass "hard burn ASS exit code 0"
else
    fail "hard burn ASS exit code non-zero"
fi
check_nonempty "$WORKDIR/hard_ass.mp4" "hard_ass.mp4"
has_marker "$WORKDIR/hard_ass.mp4" "Video:.*h264" "hard_ass.mp4 re-encoded with libx264"
lacks_marker "$WORKDIR/hard_ass.mp4" "Subtitle:" "hard_ass.mp4 has no standalone subtitle stream"

# ============================================ Scenario 4b: VTT converted to SRT
echo
echo "--- Scenario 4b: hard burn of VTT-converted SRT (conversion contract) ---"
if "$FFMPEG" -y -hide_banner -loglevel error \
    -i "$WORKDIR/video.mp4" \
    -vf "subtitles='${WORKDIR}/converted_from_vtt.srt':charenc=UTF-8" \
    -c:v libx264 -crf 18 -preset medium -c:a copy \
    "$WORKDIR/hard_vtt_converted.mp4"; then
    pass "burn of VTT-converted SRT exit code 0"
else
    fail "burn of VTT-converted SRT exit code non-zero"
fi
check_nonempty "$WORKDIR/hard_vtt_converted.mp4" "hard_vtt_converted.mp4"

# ==================================================== Scenario 5: mux
echo
echo "--- Scenario 5: muxVideoAudio (map 0:v / map 1:a, copy, -shortest) ---"
echo "vector: -y -loglevel error -i video.mp4 -i audio.m4a -map 0:v -map 1:a -c:v copy -c:a copy -shortest mux.mp4"
if "$FFMPEG" -y -hide_banner -loglevel error \
    -i "$WORKDIR/video.mp4" -i "$WORKDIR/audio.m4a" \
    -map 0:v -map 1:a \
    -c:v copy -c:a copy \
    -shortest \
    "$WORKDIR/mux.mp4"; then
    pass "mux exit code 0"
else
    fail "mux exit code non-zero"
fi
check_nonempty "$WORKDIR/mux.mp4" "mux.mp4"
has_marker "$WORKDIR/mux.mp4" "Stream #.*Video:" "mux.mp4 carries a video stream"
has_marker "$WORKDIR/mux.mp4" "Stream #.*Audio:" "mux.mp4 carries an audio stream"
lacks_marker "$WORKDIR/mux.mp4" "Subtitle:" "mux.mp4 carries no subtitle stream"

# ============================================ Scenario 6: Whisper chunking vector
echo
echo "--- Scenario 6: Whisper chunk split (16 kHz mono MP3, segment_time 600) ---"
echo "vector: -y -i chunk_src.wav -vn -acodec libmp3lame -b:a 64k -ar 16000 -ac 1 -f segment -segment_time 600 chunk_%03d.mp3"
"$FFMPEG" -y -hide_banner -loglevel error \
    -f lavfi -i sine=frequency=440:duration=12 \
    -ar 16000 -ac 1 "$WORKDIR/chunk_src.wav"
check_nonempty "$WORKDIR/chunk_src.wav" "chunk source wav (12s)"
if "$FFMPEG" -y -hide_banner -loglevel error \
    -i "$WORKDIR/chunk_src.wav" \
    -vn -acodec libmp3lame -b:a 64k -ar 16000 -ac 1 \
    -f segment -segment_time 600 \
    "$WORKDIR/chunk_%03d.mp3"; then
    pass "Whisper chunking vector exit code 0"
else
    fail "Whisper chunking vector exit code non-zero"
fi
CHUNK_COUNT=$(find "$WORKDIR" -maxdepth 1 -name 'chunk_[0-9][0-9][0-9].mp3' | wc -l)
if [[ "$CHUNK_COUNT" -ge 1 ]]; then
    pass "produced $CHUNK_COUNT chunk mp3 file(s) (16 kHz mono)"
else
    fail "expected >=1 chunk mp3, found 0"
fi
if [[ "$CHUNK_COUNT" -ge 1 ]]; then
    has_marker "$WORKDIR/chunk_000.mp3" "Audio:.*(mp3|MP3)" "chunk_000.mp3 streams MP3"
fi

# ============================================ Scenario 7: remux stream copy
echo
echo "--- Scenario 7: remuxContainer (stream copy mp4 -> mkv, -c copy -f matroska) ---"
echo "vector: -y -loglevel error -i video.mp4 -c copy -f matroska remux.mkv"
if "$FFMPEG" -y -hide_banner -loglevel error \
    -i "$WORKDIR/video.mp4" \
    -c copy -f matroska \
    "$WORKDIR/remux.mkv"; then
    pass "remux copy exit code 0"
else
    fail "remux copy exit code non-zero"
fi
check_nonempty "$WORKDIR/remux.mkv" "remux.mkv"
has_marker "$WORKDIR/remux.mkv" "Stream #.*Video:" "remux.mkv keeps a video stream (copy)"
has_marker "$WORKDIR/remux.mkv" "Stream #.*Audio:" "remux.mkv keeps an audio stream (copy)"

# ============================================ Scenario 8: soft embed WebM/webvtt
echo
echo "--- Scenario 8: soft embed WebVTT into WebM (-c:s webvtt, streams copy; no +faststart) ---"
echo "vector: -y -loglevel error -i video.webm -i en_ar.vtt -map 0 -map 1 -c:v copy -c:a copy -c:s webvtt soft.webm"
if "$FFMPEG" -y -hide_banner -loglevel error \
    -i "$WORKDIR/video.webm" -i "$WORKDIR/en_ar.vtt" \
    -map 0 -map 1 -c:v copy -c:a copy -c:s webvtt \
    "$WORKDIR/soft.webm"; then
    pass "webm soft embed exit code 0"
else
    fail "webm soft embed exit code non-zero"
fi
check_nonempty "$WORKDIR/soft.webm" "soft.webm"
has_marker "$WORKDIR/soft.webm" "Subtitle:.*(webvtt|WebVTT)" "soft.webm contains webvtt subtitle stream"
has_marker "$WORKDIR/soft.webm" "Video:.*(vp8|VP8|vp9|VP9)" "soft.webm keeps a webm video stream (copy)"

# ============================================ Scenario 9: video segment split
echo
echo "--- Scenario 9: splitByTimeSegment (segment muxer, stream copy, 1 s parts) ---"
echo "vector: -y -loglevel error -i video.mp4 -c copy -f segment -segment_time 1 -reset_timestamps 1 vchunk_%03d.mp4"
if "$FFMPEG" -y -hide_banner -loglevel error \
    -i "$WORKDIR/video.mp4" \
    -c copy -f segment -segment_time 1 -reset_timestamps 1 \
    "$WORKDIR/vchunk_%03d.mp4"; then
    pass "video segment split exit code 0"
else
    fail "video segment split exit code non-zero"
fi
VCHUNK_COUNT=$(find "$WORKDIR" -maxdepth 1 -name 'vchunk_[0-9][0-9][0-9].mp4' | wc -l)
if [[ "$VCHUNK_COUNT" -ge 1 ]]; then
    pass "produced $VCHUNK_COUNT video segment(s) via stream copy"
else
    fail "expected >=1 video segment, found 0"
fi

# ==================================================== summary
echo
echo "=================================================================="
if [[ "$FAIL" -eq 0 ]]; then
    echo "OVERALL: ALL SCENARIOS PASS (host ffmpeg $($FFMPEG -version | head -1 | grep -oE 'version [0-9.]+'))"
    exit 0
else
    echo "OVERALL: FAILURES PRESENT — see RESULT:FAIL lines above"
    exit 1
fi
