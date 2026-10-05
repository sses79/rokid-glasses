package dev.tim.gesturelab

import org.junit.Assert.assertEquals
import org.junit.Test

/** Hand shapes follow the browser project's `tests/gesture-smoke.mjs`, with middle joints added. */
class EmojiClassifierTest {

    private fun hand(extended: Boolean = true, edit: (MutableList<Point>) -> Unit = {}): List<Point> {
        val p = MutableList(21) { Point(0f, 0f) }
        p[0] = Point(0f, 3f)
        p[2] = Point(0.4f, 2f)
        p[4] = Point(0.4f, 2f) // thumb tucked in unless a test moves it
        p[5] = Point(-1f, 1f)
        p[9] = Point(-0.35f, 0.8f)
        p[13] = Point(0.35f, 0.8f)
        p[17] = Point(1f, 1f)
        listOf(8, 12, 16, 20).forEachIndexed { i, tip -> p[tip] = Point(-1f + 0.65f * i, if (extended) -2f else 1.5f) }
        edit(p)
        // Middle joints: halfway along a straight finger; out past the knuckle on a
        // curled one, so the fingertip doubles back toward the palm.
        for ((mcp, tip) in listOf(5 to 8, 9 to 12, 13 to 16, 17 to 20)) {
            val straight = p[tip].y < p[mcp].y - 0.5f || kotlin.math.abs(p[tip].x - p[mcp].x) > 1.5f
            p[mcp + 1] = if (straight) {
                Point((p[mcp].x + p[tip].x) / 2, (p[mcp].y + p[tip].y) / 2)
            } else {
                Point(p[mcp].x, p[mcp].y - 0.7f)
            }
        }
        return p
    }

    private fun classify(points: List<Point>) = EmojiClassifier.classify(points, 1f)

    @Test fun raisedHand() = assertEquals(EmojiGesture.RAISED_HAND, classify(hand()))

    @Test fun fist() = assertEquals(EmojiGesture.FIST, classify(hand(extended = false)))

    @Test fun thumbsUp() = assertEquals(EmojiGesture.THUMBS_UP, classify(hand(extended = false) { it[4] = Point(2f, 0f) }))

    @Test fun thumbsDown() = assertEquals(EmojiGesture.THUMBS_DOWN, classify(hand(extended = false) { it[4] = Point(0.5f, 5f) }))

    @Test fun victory() = assertEquals(
        EmojiGesture.VICTORY,
        classify(hand(extended = false) { it[8] = Point(-0.8f, -2f); it[12] = Point(-0.2f, -2f) }),
    )

    @Test fun loveYou() = assertEquals(
        EmojiGesture.LOVE_YOU,
        classify(hand(extended = false) { it[4] = Point(2f, 0f); it[8] = Point(-1f, -2f); it[20] = Point(0.95f, -2f) }),
    )

    @Test fun ok() = assertEquals(EmojiGesture.OK, classify(hand { it[4] = Point(2f, 0f); it[8] = Point(1.9f, 0.1f) }))

    @Test fun leftArrowIsAFistWithTheThumbPointingLeft() =
        assertEquals(EmojiGesture.LEFT_ARROW, classify(hand(extended = false) { it[4] = Point(-2.5f, 2f) }))

    @Test fun rightArrowIsAFistWithTheThumbPointingRight() =
        assertEquals(EmojiGesture.RIGHT_ARROW, classify(hand(extended = false) { it[4] = Point(3f, 2f) }))

    @Test fun pointingWithTheIndexFingerMatchesNothing() =
        assertEquals(null, classify(hand(extended = false) { it[8] = Point(-1f, -2f) }))

    @Test fun threeFingersMatchNothing() = assertEquals(
        null,
        classify(hand(extended = false) { it[8] = Point(-1f, -2f); it[12] = Point(-0.35f, -2f); it[16] = Point(0.3f, -2f) }),
    )

    @Test
    fun curledFingersSeenFromBehindAreNotARaisedHand() {
        // As the glasses see a hand with only the index finger up: curled fingertips still project
        // beyond their knuckles, but the fingers bend back at the middle joint.
        val pointing = hand().toMutableList()
        for ((mcp, tip) in listOf(9 to 12, 13 to 16, 17 to 20)) {
            pointing[mcp + 1] = Point(pointing[mcp].x, pointing[mcp].y - 0.9f)
            pointing[tip] = Point(pointing[mcp].x + 0.1f, pointing[mcp].y - 0.5f)
        }
        assertEquals(null, classify(pointing))
    }

    @Test
    fun aspectCorrectionKeepsShapeOnANonSquareFrame() {
        // The same raised hand as seen in a 3:4 portrait frame: x is stretched in normalised units.
        val squeezed = hand().map { Point(it.x / 0.75f, it.y) }
        assertEquals(EmojiGesture.RAISED_HAND, EmojiClassifier.classify(squeezed, 0.75f))
    }
}
