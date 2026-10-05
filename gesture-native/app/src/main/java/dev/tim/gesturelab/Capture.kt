package dev.tim.gesturelab

/** A gesture the Capture page asks the wearer to hold while recording. */
enum class CaptureGesture(val expects: EmojiGesture) {
    THUMBS_UP(EmojiGesture.THUMBS_UP),
    THUMBS_DOWN(EmojiGesture.THUMBS_DOWN),
    LEFT_ARROW(EmojiGesture.LEFT_ARROW),
    RIGHT_ARROW(EmojiGesture.RIGHT_ARROW),
    FIST(EmojiGesture.FIST),
    RAISED_HAND(EmojiGesture.RAISED_HAND),
    VICTORY(EmojiGesture.VICTORY),
    LOVE_YOU(EmojiGesture.LOVE_YOU),
    OK(EmojiGesture.OK);

    val id get() = expects.id
    val symbol get() = expects.symbol
    val label get() = expects.label
}

/** One detector result during a clip. [landmarks] is null when no hand was found. */
data class Observation(
    val elapsedMs: Long,
    val landmarks: List<Point>?,
    val state: HandState,
)

data class Verdict(val pass: Boolean, val detected: String)

object CaptureJudge {
    /** Share of tracked frames that must show the gesture for a clip to pass. */
    const val HELD_SHARE = 0.6f

    /** The start of a clip is the wearer changing from the previous gesture; it is not judged. */
    const val SETTLE_MS = 1000L

    /** Decides whether a clip shows the gesture it was meant to, from the detector's own output. */
    fun judge(gesture: CaptureGesture, observations: List<Observation>): Verdict {
        val tracked = observations.filter { it.elapsedMs >= SETTLE_MS && it.state.tracked }
        if (tracked.isEmpty()) return Verdict(false, "no hand")

        val expected = gesture.expects
        val share = tracked.count { it.state.gesture == expected }.toFloat() / tracked.size
        val most = tracked.groupingBy { it.state.gesture }.eachCount().maxByOrNull { it.value }!!.key
        return Verdict(share >= HELD_SHARE, "${(share * 100).toInt()}% match, mostly ${most?.label ?: "none"}")
    }
}
