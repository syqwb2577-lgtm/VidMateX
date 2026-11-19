package app.vidmatex.feature.downloads

import androidx.work.Constraints
import androidx.work.NetworkType

/**
 * Pure WorkManager constraint factory (androidx.work types; not referenced from JVM tests).
 * Network-connected constraint on every media download; unmetered (Wi-Fi) when the user
 * enabled `requireWifi` in settings. Retry/backoff behavior stays in `RetryPolicy` and the
 * request's backoff criteria; constraints only gate when the worker may run.
 */
internal object DownloadWorkConstraints {

    fun build(requireWifi: Boolean): Constraints = Constraints.Builder()
        .setRequiredNetworkType(if (requireWifi) NetworkType.UNMETERED else NetworkType.CONNECTED)
        .build()
}
