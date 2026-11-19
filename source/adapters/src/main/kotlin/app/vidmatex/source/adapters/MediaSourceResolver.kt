package app.vidmatex.source.adapters

import app.vidmatex.core.common.AppResult
import app.vidmatex.core.common.UrlValidator
import app.vidmatex.core.model.SourceType
import javax.inject.Inject
import javax.inject.Singleton

/** Picks the compliant adapter for a URL. Unknown hosts are rejected explicitly. */
@Singleton
class MediaSourceResolver @Inject constructor(
    private val youTube: YouTubeAdapter,
    private val facebook: FacebookAdapter,
    private val tikTok: TikTokAdapter,
    private val directUrl: DirectUrlAdapter,
) {
    // Direct URL first (fast, no backend call); platform adapters delegate to the
    // optional self-hosted resolver. Ordering is an optimization — each adapter's
    // matches() is host-pattern specific, so correctness does not depend on order.
    private val all: List<SourceAdapter> get() = listOf(directUrl, youTube, facebook, tikTok)

    fun detect(url: String): SourceType = UrlValidator.detectSource(url)

    fun adapterFor(url: String): AppResult<SourceAdapter> {
        if (!UrlValidator.isValid(url)) {
            return AppResult.Error("That doesn't look like a valid link.")
        }
        val normalized = UrlValidator.normalize(url)
        // Pure host-pattern selection (AdapterSelection, JVM-testable with fakes); the
        // android.util.Patterns validity gate above cannot run on the plain JVM.
        return AdapterSelection.select(normalized, all)
    }
}
