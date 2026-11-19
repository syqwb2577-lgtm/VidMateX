package app.vidmatex.player

/**
 * Pure track-seam model and selection policy for the preview player (§3). The Android
 * controller maps Media3 `Tracks.Group` data into [TrackOption]s; this policy decides
 * selection state, defaults, and index validity without touching framework classes so
 * the rules are JVM-testable.
 */
enum class SeamTrackType { AUDIO, SUBTITLE }

/**
 * One selectable track exposed by the player. [index] refers to the Media3 group's
 * track index at the time the snapshot was built; [key] is stable for diffing.
 */
data class TrackOption(
    val key: String,
    val index: Int,
    val type: SeamTrackType,
    val label: String,
    val language: String? = null,
    val selected: Boolean = false,
    /** False when the format is not renderable (e.g. an unsupported text MIME). */
    val supported: Boolean = true,
)

object TrackSelectionPolicy {
    /** Sentinel "off" selection for subtitle tracks (captions disabled). */
    const val OFF_INDEX = -1

    /**
     * Return a copy of [options] with exactly one track selected by [index], or none
     * when [index] is [OFF_INDEX]. Out-of-range or unsupported indices leave the list
     * unchanged (the controller surfaces the rejection through state instead of crashing).
     *
     * Single-group only: with multiple Media3 track groups the same [index] can appear in
     * more than one group, so selection state must be keyed by [TrackOption.key] — use
     * [selectByKey] in that case.
     */
    fun select(options: List<TrackOption>, index: Int): List<TrackOption> {
        if (index == OFF_INDEX) return options.map { it.copy(selected = false) }
        val target = options.firstOrNull { it.index == index } ?: return options
        if (!target.supported) return options
        return options.map { it.copy(selected = it.index == index) }
    }

    /**
     * Return a copy of [options] with exactly one track selected by its stable [key]
     * (the only unambiguous identifier when multiple track groups each expose track
     * index 0). Unknown or unsupported keys leave the list unchanged.
     */
    fun selectByKey(options: List<TrackOption>, key: String): List<TrackOption> {
        val target = options.firstOrNull { it.key == key } ?: return options
        if (!target.supported) return options
        return options.map { it.copy(selected = it.key == key) }
    }

    /** Current selected index, or [OFF_INDEX] when nothing (or an unsupported track) is on. */
    fun selectedIndex(options: List<TrackOption>): Int {
        val chosen = options.firstOrNull { it.selected && it.supported }
        return chosen?.index ?: OFF_INDEX
    }

    /**
     * Best default track: a supported track whose language matches [preferredLanguage],
     * otherwise the first supported track, otherwise null (off).
     */
    fun defaultOption(options: List<TrackOption>, preferredLanguage: String? = null): TrackOption? {
        val supported = options.filter { it.supported }
        if (supported.isEmpty()) return null
        if (!preferredLanguage.isNullOrBlank()) {
            supported.firstOrNull {
                it.language?.equals(preferredLanguage, ignoreCase = true) == true
            }?.let { return it }
        }
        return supported.first()
    }

    /** Human label fallback when a track exposes neither label nor language. */
    fun displayLabel(option: TrackOption): String =
        option.label.ifBlank { option.language ?: "Track ${option.index + 1}" }
}
