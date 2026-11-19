package app.vidmatex.player

// =====================================================================================
// DEVICE-GAP MARKER (never executed in this environment)
// -------------------------------------------------------------------------------------
// This source tree has NO Android SDK, Gradle distribution, or JDK 17, so this file has
// NEVER been compiled or run on a device/emulator. No assertion below has executed.
// It is a source-ready skeleton; the commented steps are the REQUIRED manual verification
// procedure once `./gradlew :app:connectedDebugAndroidTest` can run (CI emulator or
// physical device). Do not mark these cases green without an on-device run.
//
// MANUAL / INSTRUMENTED STEPS TO PERFORM ON A DEVICE OR EMULATOR (API 24–36):
//  1. Install debug APK, open a completed download's Preview (History → Preview or
//     Downloads → Preview). Feed a local SRT sidecar through
//     `Media3PlayerController.prepare(url, subtitleUri=…)`; assert PlayerView renders a
//     caption cue after seeking to a segment with text.
//  2. Captions seam: tap the CC toggle; assert `TRACK_TYPE_TEXT` selection flips
//     (`Media3PlayerController.state.captionsEnabled`) and cues hide/show.
//  3. Track selectors: open the audio menu and subtitle menu; select entries and assert
//     `selectAudioTrack(index)` / `selectSubtitleTrack(index)` update
//     `state.audioTracks` / `state.subtitleTracks`; choosing Off disables text tracks
//     without dropping them.
//  4. Speed menu: select 0.25x / 1.5x / 2x and assert `state.speed` equals the snapped
//     ladder value and ExoPlayer `playbackParameters.speed` follows it.
//  5. Subtitle delay: tap +/- (500 ms steps) and assert `state.subtitleDelayMs` clamps
//     within ±1 h; reset returns to 0. Renderer-level offset on embedded (non-sidecar)
//     tracks is device-verified-pending (no public Media3 1.3.1 text-delay API); record
//     observed cue timing in the device report.
//  6. Errors: point the player at an unreachable https URL and an unsupported container;
//     assert the card maps to TIMEOUT/NETWORK and UNSUPPORTED_FORMAT respectively; the
//     Retry button re-prepares and Dismiss clears `state.error`.
//  7. Resume: play ~20 s, leave the screen (ON_STOP pauses), reopen the same item and
//     assert the saved position seeds a seek; complete a video (>95%) and assert the row
//     is marked completed and not resumed.
//  8. Fullscreen/orientation: rotate handset; assert no activity recreate (manifest
//     `configChanges`), surface maximizes on the fullscreen toggle; record system-bar
//     behavior (true immersive fullscreen remains pending per audit checklist §player).
//  9. History card states: FAILED/CANCELLED shows Retry + Delete; COMPLETED shows
//     Open/Share/Preview; progress percent/speed/bytes/ETA render; error expands on tap.
// 10. POST_NOTIFICATIONS on API 33+: grant/deny flows show the rationale snackbar only
//     when shouldShowRequestPermissionRationale is true; Settings action opens app
//     details. Denial must not block the foreground worker.
//
// Source-ready pure coverage (executable under `testDebugUnitTest` on the JVM) lives in
// `app/src/test/kotlin/app/vidmatex/player/`: speed ladder, subtitle delay, position
// store retention, error-category mapping, track-selection policy, orientation policy.
// =====================================================================================

import androidx.compose.ui.test.junit4.createComposeRule
import org.junit.Rule
import org.junit.Test

class VideoPreviewInstrumentedTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun subtitlePlayback_skeleton() {
        // TODO(device/CI): setContent { VideoPreviewScreen(mediaUri = localFile, subtitlePath = srtPath) }
        // TODO(device/CI): onNodeWithContentDescription(stringResource(R.string.preview_cc_on))
        // TODO(device/CI):   .performClick(); assert a caption text node appears after seek.
        // TODO(device/CI): assert speed menu items drive controller.setSpeed; delay buttons
        // TODO(device/CI):   change state.subtitleDelayMs within clamp; error category card
        // TODO(device/CI):   matches network/unsupported fixtures listed in steps 1–8 above.
    }
}
