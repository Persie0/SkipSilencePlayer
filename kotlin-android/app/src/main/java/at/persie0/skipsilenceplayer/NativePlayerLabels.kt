package at.persie0.skipsilenceplayer

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

/** Play-localized labels for persisted player choices and on-screen state. */
private val labels: Map<String, Int> = mapOf(
    "Ask" to R.string.choice_ask,
    "Always" to R.string.choice_always,
    "Never" to R.string.choice_never,
    "Fit" to R.string.choice_fit,
    "Fill" to R.string.choice_fill,
    "Crop" to R.string.choice_crop,
    "Original" to R.string.choice_original,
    "Top" to R.string.choice_top,
    "Center" to R.string.choice_center,
    "Bottom" to R.string.choice_bottom,
    "Conservative" to R.string.choice_conservative,
    "Balanced" to R.string.choice_balanced,
    "Aggressive" to R.string.choice_aggressive,
    "No video selected" to R.string.choice_no_video_selected,
    "No subtitle cues found" to R.string.choice_no_subtitle_cues_found,
    "Subtitle match" to R.string.choice_subtitle_match,
    "No subtitle match" to R.string.choice_no_subtitle_match,
    "Sleep timer finished" to R.string.choice_sleep_timer_finished,
    "No videos found in folder" to R.string.choice_no_videos_found_in_folder,
    "Resume" to R.string.choice_resume,
    "Start over" to R.string.choice_start_over
)

@Composable
internal fun localizePlayerLabel(label: String): String {
    val resource = labels[label] ?: return label
    return stringResource(resource)
}
