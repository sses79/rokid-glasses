package dev.tim.gesturelab

import kotlin.math.hypot

/**
 * A hand landmark in normalised image coordinates (0..1, origin top-left).
 * [z] is MediaPipe's relative depth (smaller is nearer the camera); recorded for later use.
 */
data class Point(val x: Float, val y: Float, val z: Float = 0f)

sealed interface GestureEvent {
    data object Found : GestureEvent
    data object Lost : GestureEvent
    data class PinchStart(val x: Float, val y: Float) : GestureEvent
    data object PinchEnd : GestureEvent
}

data class HandState(
    val tracked: Boolean = false,
    val x: Float = 0.5f,
    val y: Float = 0.5f,
    val pinching: Boolean = false,
    val pinchRatio: Float = 1f,
    /** The emoji gesture currently held, once it has been stable for a moment. */
    val gesture: EmojiGesture? = null,
    val events: List<GestureEvent> = emptyList(),
)

data class GestureConfig(
    /** Thumb-to-index distance over hand size; below this the hand is pinching. */
    val pinchOn: Float = 0.30f,
    /** Above this a pinch is released. The gap between the two stops flicker. */
    val pinchOff: Float = 0.45f,
    val pinchHoldMs: Long = 60,
    /** A release must last longer than a start, so one noisy frame cannot drop a held pinch. */
    val pinchReleaseHoldMs: Long = 200,
    /** A new emoji gesture must persist this long before it replaces the current one. */
    val gestureHoldMs: Long = 150,
    val lostAfterMs: Long = 400,
    /** Weight of the newest sample in the cursor's moving average. */
    val smoothing: Float = 0.45f,
    /** How much cursor history is kept for [latchMs]. */
    val trailMs: Long = 350,
    /** A pinch is reported where the cursor was this long before it closed. */
    val latchMs: Long = 120,
)

/**
 * Turns per-frame MediaPipe hand landmarks into a smoothed cursor, a stable
 * emoji gesture and pinch events. Timing is in milliseconds so behaviour does
 * not depend on the camera frame rate. Not thread-safe: call from one thread.
 *
 * Motion gestures such as swipes are deliberately absent: on glasses the camera
 * moves with the head, and a head turn looks the same as a hand sweep.
 */
class GestureEngine(
    private val config: GestureConfig = GestureConfig(),
    private val emojiConfig: EmojiConfig = EmojiConfig(),
) {

    private data class Sample(val t: Long, val x: Float, val y: Float)

    private var tracked = false
    private var lastSeen = 0L
    private var x = 0.5f
    private var y = 0.5f
    private var pinching = false
    private var pinchPending = false
    private var pinchPendingSince = 0L
    private var gesture: EmojiGesture? = null
    private var gesturePending: EmojiGesture? = null
    private var gesturePendingSince = 0L
    private val trail = ArrayDeque<Sample>()
    private var last = HandState()

    fun reset() {
        tracked = false
        pinching = false
        pinchPending = false
        gesture = null
        gesturePending = null
        trail.clear()
        last = HandState()
    }

    /**
     * @param landmarks the 21 MediaPipe hand landmarks, or null when no hand was found
     * @param aspect image width divided by height, so distances are measured in square pixels
     */
    fun update(landmarks: List<Point>?, aspect: Float, t: Long): HandState {
        val events = mutableListOf<GestureEvent>()

        if (landmarks == null || landmarks.size < LANDMARK_COUNT) {
            if (tracked && t - lastSeen > config.lostAfterMs) {
                if (pinching) events += GestureEvent.PinchEnd
                reset()
                events += GestureEvent.Lost
            }
            last = last.copy(tracked = tracked, events = events)
            return last
        }

        fun dist(a: Int, b: Int): Float =
            hypot((landmarks[a].x - landmarks[b].x) * aspect, landmarks[a].y - landmarks[b].y)

        // The palm barely moves when the fingers pinch, so it makes a steadier
        // cursor than a fingertip.
        val rawX = (landmarks[WRIST].x + landmarks[INDEX_MCP].x + landmarks[PINKY_MCP].x) / 3f
        val rawY = (landmarks[WRIST].y + landmarks[INDEX_MCP].y + landmarks[PINKY_MCP].y) / 3f

        if (!tracked) {
            tracked = true
            x = rawX
            y = rawY
            events += GestureEvent.Found
        } else {
            x += (rawX - x) * config.smoothing
            y += (rawY - y) * config.smoothing
        }
        lastSeen = t
        trail.addLast(Sample(t, x, y))
        while (trail.isNotEmpty() && t - trail.first().t > config.trailMs) trail.removeFirst()

        val handSize = dist(WRIST, MIDDLE_MCP).coerceAtLeast(1e-4f)
        val pinchRatio = dist(THUMB_TIP, INDEX_TIP) / handSize

        val wantPinch = if (pinching) pinchRatio < config.pinchOff else pinchRatio < config.pinchOn
        if (wantPinch != pinchPending) {
            pinchPending = wantPinch
            pinchPendingSince = t
        }
        val hold = if (pinching) config.pinchReleaseHoldMs else config.pinchHoldMs
        if (pinchPending != pinching && t - pinchPendingSince >= hold) {
            pinching = pinchPending
            if (pinching) {
                val at = trail.lastOrNull { it.t <= t - config.latchMs } ?: trail.first()
                events += GestureEvent.PinchStart(at.x, at.y)
            } else {
                events += GestureEvent.PinchEnd
            }
        }

        val reading = EmojiClassifier.classify(landmarks, aspect, emojiConfig)
        if (reading != gesturePending) {
            gesturePending = reading
            gesturePendingSince = t
        }
        if (gesturePending != gesture && t - gesturePendingSince >= config.gestureHoldMs) gesture = gesturePending

        last = HandState(true, x, y, pinching, pinchRatio, gesture, events)
        return last
    }

    companion object {
        const val LANDMARK_COUNT = 21
        const val WRIST = 0
        const val THUMB_TIP = 4
        const val INDEX_MCP = 5
        const val INDEX_TIP = 8
        const val MIDDLE_MCP = 9
        const val PINKY_MCP = 17

        /** Landmark pairs joined when drawing the hand skeleton. */
        val BONES = listOf(
            0 to 1, 1 to 2, 2 to 3, 3 to 4,
            0 to 5, 5 to 6, 6 to 7, 7 to 8,
            5 to 9, 9 to 10, 10 to 11, 11 to 12,
            9 to 13, 13 to 14, 14 to 15, 15 to 16,
            13 to 17, 17 to 18, 18 to 19, 19 to 20,
            0 to 17,
        )
    }
}
