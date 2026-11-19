package app.vidmatex.source.adapters

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.common.SettingsRepository
import app.vidmatex.core.model.MediaTrack
import app.vidmatex.core.model.SourceType
import app.vidmatex.core.model.SubtitleFormat
import app.vidmatex.core.model.SubtitleTrack
import app.vidmatex.core.model.VideoMetadata
import app.vidmatex.core.network.BackendApi
import app.vidmatex.core.network.ExtractRequest
import app.vidmatex.core.network.ExtractResponse
import app.vidmatex.core.network.RetrofitFactory
import app.vidmatex.core.network.SubtitleRequest
import app.vidmatex.core.network.safeApiCall

/**
 * Base for YouTube / Facebook / TikTok adapters. They delegate resolution to an
 * OPTIONAL, user-configured self-hosted resolver (e.g. a yt-dlp service running on the
 * operator's host). No platform private API is ever called from the device, no endpoint
 * or key ships in the APK, and platform pages are never scraped here (see
 * [SourceCompliance]; `PLATFORM_DOWNLOAD_AVAILABLE = false` on the device).
 *
 * Compliance boundary: a direct stream URL is only surfaced when the backend's
 * `ExtractResponse.streamUrls` actually carries one for the chosen track; the adapter
 * never fabricates a media URL from page metadata. When the backend reports
 * `downloadAvailable=false` (platform recognized but no compliant download method
 * configured), the backend message is surfaced together with the compliance note.
 *
 * Error mapping: [safeApiCall] maps timeouts / no-network / HTTP errors to plain-language
 * [AppResult.Error]s; [ResolverState.http] classifies the same status codes into typed
 * states (401/403 Unauthorized, 408/429 RateLimited, 5xx ServerError) for callers that
 * pattern-match. The non-secret backend URL comes from `SettingsRepository.backendBaseUrl`;
 * the optional shared secret comes from `resolverApiKey` and is never logged.
 */
abstract class BackedSourceAdapter(
    private val retrofitFactory: RetrofitFactory,
    private val settingsRepository: SettingsRepository,
    override val sourceType: SourceType,
    private val hostPatterns: List<String>,
) : SourceAdapter {

    override fun matches(url: String): Boolean {
        val host = runCatching { java.net.URI(url).host?.lowercase().orEmpty() }.getOrDefault("")
        return hostPatterns.any { host.contains(it) }
    }

    /** Builds an authenticated BackendApi from current settings, or a recoverable Error. */
    protected suspend fun backend(): AppResult<BackendApi> {
        val settings = settingsRepository.current()
        val baseUrl = settings.backendBaseUrl.trim()
        if (baseUrl.isBlank()) {
            return AppResult.Error(
                "Resolver not configured: set endpoint in Settings, or use direct video URLs.",
                recoverable = true,
            )
        }
        val apiKey = settings.resolverApiKey.trim()
        val api = retrofitFactory.backendApi(baseUrl, apiKey)
            ?: return AppResult.Error(
                "Resolver URL is invalid (must be an https URL). Check Settings.",
                recoverable = true,
            )
        return AppResult.Success(api)
    }

    /** Single extract call; shared by resolve/listFormats/listSubtitles/streamUrl. */
    private suspend fun extract(url: String): AppResult<ExtractResponse> {
        val api = when (val b = backend()) {
            is AppResult.Success -> b.value
            is AppResult.Error -> return b
        }
        return safeApiCall { api.extract(ExtractRequest(url)) }
    }

    override suspend fun resolve(url: String): AppResult<VideoMetadata> =
        extract(url).map { it.metadata }

    override suspend fun listFormats(metadata: VideoMetadata): AppResult<List<MediaTrack>> {
        return when (val resp = extract(metadata.sourceUrl)) {
            is AppResult.Success -> {
                val r = resp.value
                if (!r.downloadAvailable) {
                    AppResult.Error(unavailableMessage(r), recoverable = true)
                } else {
                    AppResult.Success(r.formats)
                }
            }
            is AppResult.Error -> resp
        }
    }

    override suspend fun listSubtitles(metadata: VideoMetadata): AppResult<List<SubtitleTrack>> =
        extract(metadata.sourceUrl).map { it.subtitles }

    override suspend fun streamUrl(metadata: VideoMetadata, track: MediaTrack): AppResult<String> {
        return when (val resp = extract(metadata.sourceUrl)) {
            is AppResult.Success -> {
                val r = resp.value
                if (!r.downloadAvailable) {
                    AppResult.Error(unavailableMessage(r), recoverable = true)
                } else {
                    // Only a URL explicitly supplied by the compliant backend is returned;
                    // no media URL is constructed or guessed from the metadata.
                    r.streamUrls[track.trackId]?.let { AppResult.Success(it) }
                        ?: AppResult.Error("No direct stream URL for the selected track.", recoverable = true)
                }
            }
            is AppResult.Error -> resp
        }
    }

    /** Backend message (if any) plus the platform compliance note; never a fabricated URL. */
    private fun unavailableMessage(r: ExtractResponse): String {
        val backend = r.message?.takeIf { it.isNotBlank() }
        val note = SourceCompliance.note(sourceType)
        return if (backend == null) note else "$backend $note"
    }

    override suspend fun fetchSubtitle(
        metadata: VideoMetadata,
        track: SubtitleTrack,
        format: SubtitleFormat,
    ): AppResult<String> {
        val api = when (val b = backend()) {
            is AppResult.Success -> b.value
            is AppResult.Error -> return b
        }
        return safeApiCall {
            api.subtitle(SubtitleRequest.forFormat(metadata.sourceUrl, track.trackId, format)).content
        }
    }
}

class YouTubeAdapter(retrofitFactory: RetrofitFactory, settingsRepository: SettingsRepository) :
    BackedSourceAdapter(retrofitFactory, settingsRepository, SourceType.YOUTUBE,
        listOf("youtube.com", "youtu.be", "youtube-nocookie.com"))

class FacebookAdapter(retrofitFactory: RetrofitFactory, settingsRepository: SettingsRepository) :
    BackedSourceAdapter(retrofitFactory, settingsRepository, SourceType.FACEBOOK,
        listOf("facebook.com", "fb.watch", "fb.com", "m.facebook.com"))

class TikTokAdapter(retrofitFactory: RetrofitFactory, settingsRepository: SettingsRepository) :
    BackedSourceAdapter(retrofitFactory, settingsRepository, SourceType.TIKTOK,
        listOf("tiktok.com", "vm.tiktok.com"))
