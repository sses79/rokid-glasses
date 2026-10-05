package dev.tim.gesturelab

import kotlin.math.abs
import kotlin.math.hypot

/**
 * Single-hand gestures, with the same ids as the browser project this began as
 * a port of (front-king `src/config.js`).
 */
enum class EmojiGesture(val id: String, val symbol: String, val label: String) {
    THUMBS_UP("emojiThumbsUp", "👍", "THUMBS UP"),
    VICTORY("emojiVictoryHand", "✌️", "VICTORY"),
    LOVE_YOU("emojiLoveYouGesture", "🤟", "LOVE YOU"),
    OK("emojiOKHand", "👌", "OK"),
    FIST("emojiFist", "✊", "FIST"),
    THUMBS_DOWN("emojiThumbsDown", "👎", "THUMBS DOWN"),
    // Plain arrows: the system emoji font draws these inside a filled square.
    LEFT_ARROW("emojiLeftArrow", "←", "LEFT"),
    RIGHT_ARROW("emojiRightArrow", "→", "RIGHT"),
    RAISED_HAND("emojiRaisedHand", "✋", "RAISED HAND"),
}

/**
 * Distances are ratios of hand size, so they hold at any distance from the camera.
 *
 * The rules started as a port of the browser project's and were then retuned on
 * clips recorded through the glasses (tests/manual-runs). The glasses see the
 * back of the wearer's own hand, often turned or tilted, which the browser
 * project's webcam view never has to handle.
 */
data class EmojiConfig(
    /** A finger counts as extended when its two segments line up at least this well (cosine). */
    val fingerStraightness: Float = 0.93f,
    val okDistance: Float = 0.35f,
    val thumbExtensionMargin: Float = 0.12f,
    val thumbClearMargin: Float = 0.15f,
)

object EmojiClassifier {

    private data class V(val x: Float, val y: Float)

    /** Typical palm width over wrist-to-middle-knuckle length. */
    private const val PALM_LENGTH_TO_WIDTH = 0.75f

    private fun dist(a: V, b: V) = hypot(a.x - b.x, a.y - b.y)

    /**
     * @param landmarks the 21 MediaPipe hand landmarks in normalised upright image coordinates
     * @param aspect image width divided by height, so distances are measured in square pixels
     * @return the matching gesture, or null when the hand shape fits none of them
     */
    fun classify(landmarks: List<Point>, aspect: Float, config: EmojiConfig = EmojiConfig()): EmojiGesture? {
        if (landmarks.size < 21) return null
        val lm = landmarks.map { V(it.x * aspect, it.y) }
        val wrist = lm[0]
        val thumbTip = lm[4]
        val thumbMcp = lm[2]
        val indexTip = lm[8]
        val indexMcp = lm[5]
        val middleTip = lm[12]
        val middleMcp = lm[9]
        val ringTip = lm[16]
        val ringMcp = lm[13]
        val pinkyTip = lm[20]
        val pinkyMcp = lm[17]

        // Palm width shrinks when the hand turns sideways and palm length when it
        // tips forward; taking the larger keeps the scale steady through both.
        val handSize = maxOf(dist(indexMcp, pinkyMcp), PALM_LENGTH_TO_WIDTH * dist(wrist, middleMcp)).takeIf { it > 0f } ?: 1f
        val palmCenter = V(
            (wrist.x + indexMcp.x + middleMcp.x + ringMcp.x + pinkyMcp.x) / 5f,
            (wrist.y + indexMcp.y + middleMcp.y + ringMcp.y + pinkyMcp.y) / 5f,
        )

        // Seen from behind, a curled fingertip still lies beyond its knuckle, so
        // "tip farther from the wrist than the knuckle" is not enough. A curled
        // finger does bend back on itself at the middle joint; test that instead.
        fun extended(mcp: V, pip: V, tip: V): Boolean {
            val ax = pip.x - mcp.x
            val ay = pip.y - mcp.y
            val bx = tip.x - pip.x
            val by = tip.y - pip.y
            val length = hypot(ax, ay) * hypot(bx, by)
            if (length <= 0f) return false
            return (ax * bx + ay * by) / length > config.fingerStraightness && dist(tip, wrist) > dist(pip, wrist)
        }

        val indexExtended = extended(indexMcp, lm[6], indexTip)
        val middleExtended = extended(middleMcp, lm[10], middleTip)
        val ringExtended = extended(ringMcp, lm[14], ringTip)
        val pinkyExtended = extended(pinkyMcp, lm[18], pinkyTip)
        val thumbExtended = dist(thumbTip, wrist) > dist(thumbMcp, wrist) + config.thumbExtensionMargin * handSize

        // OK: thumb and index tips touching, ring and little finger still up.
        if (dist(thumbTip, indexTip) < config.okDistance * handSize && ringExtended && pinkyExtended) return EmojiGesture.OK

        if (indexExtended && middleExtended && ringExtended && pinkyExtended) return EmojiGesture.RAISED_HAND

        // Fist family: every finger curled. A thumb held clear of the fist points
        // one of four ways; a tucked thumb is a plain fist.
        if (!indexExtended && !middleExtended && !ringExtended && !pinkyExtended) {
            val thumbClearlyOut =
                dist(thumbTip, palmCenter) > dist(thumbMcp, palmCenter) + config.thumbClearMargin * handSize
            if (!thumbClearlyOut) return EmojiGesture.FIST
            // The camera looks outward and nothing is mirrored, so left and right
            // are the wearer's own left and right.
            val dx = thumbTip.x - thumbMcp.x
            val dy = thumbTip.y - thumbMcp.y
            return if (abs(dx) > abs(dy)) {
                if (dx < 0) EmojiGesture.LEFT_ARROW else EmojiGesture.RIGHT_ARROW
            } else {
                if (dy < 0) EmojiGesture.THUMBS_UP else EmojiGesture.THUMBS_DOWN
            }
        }

        if (indexExtended && middleExtended && !ringExtended && !pinkyExtended) return EmojiGesture.VICTORY

        if (thumbExtended && indexExtended && pinkyExtended && !middleExtended && !ringExtended) return EmojiGesture.LOVE_YOU

        return null
    }
}
