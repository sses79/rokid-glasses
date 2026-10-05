package dev.tim.gesturelab

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GestureEngineTest {

    /** A schematic upright hand whose palm anchor sits near (cx, cy). */
    private fun hand(cx: Float, cy: Float, fingersOpen: Boolean = true, pinch: Boolean = false): List<Point> {
        val p = MutableList(GestureEngine.LANDMARK_COUNT) { Point(cx, cy) }
        fun set(i: Int, dx: Float, dy: Float) { p[i] = Point(cx + dx, cy + dy) }
        set(0, 0f, 0.14f)
        val knuckles = listOf(5 to -0.05f, 9 to 0f, 13 to 0.04f, 17 to 0.08f)
        for ((mcp, dx) in knuckles) {
            set(mcp, dx, -0.06f)
            set(mcp + 1, dx, -0.12f)
            set(mcp + 2, dx, if (fingersOpen) -0.17f else -0.08f)
            set(mcp + 3, dx, if (fingersOpen) -0.21f else -0.03f)
        }
        set(1, -0.05f, 0.08f); set(2, -0.09f, 0.03f); set(3, -0.12f, -0.02f); set(4, -0.14f, -0.07f)
        if (pinch) {
            set(8, -0.09f, -0.10f)
            set(4, -0.095f, -0.095f)
        }
        return p
    }

    private fun run(engine: GestureEngine, fps: Int, durationMs: Long, at: (Long) -> List<Point>?): List<HandState> {
        val states = mutableListOf<HandState>()
        var t = 0L
        while (t < durationMs) {
            states += engine.update(at(t), 1f, t)
            t += 1000L / fps
        }
        return states
    }

    private fun List<HandState>.eventNames() = flatMap { it.events }.map { it::class.simpleName }

    @Test
    fun pinchStartsAndEndsOnceAndReportsWhereTheHandWas() {
        for (fps in listOf(30, 15, 8)) {
            val states = run(GestureEngine(), fps, 3000) { t -> hand(0.3f, 0.6f, pinch = t in 1000..1999) }
            assertEquals("at $fps fps", listOf("Found", "PinchStart", "PinchEnd"), states.eventNames())
            val start = states.flatMap { it.events }.filterIsInstance<GestureEvent.PinchStart>().single()
            assertTrue("x=${start.x}", abs(start.x - 0.31f) < 0.03f)
            assertTrue("y=${start.y}", abs(start.y - 0.607f) < 0.03f)
        }
    }

    @Test
    fun movingTheHandSidewaysMovesTheCursorSidewaysOnly() {
        val states = run(GestureEngine(), 30, 2000) { t -> hand(0.3f + t * 0.0002f, 0.5f) }
        val first = states.first()
        val last = states.last()
        assertTrue("x moved ${last.x - first.x}", last.x - first.x > 0.3f)
        assertTrue("y moved ${last.y - first.y}", abs(last.y - first.y) < 0.01f)
    }

    @Test
    fun fastSidewaysMoveProducesNoGestureEvent() {
        val states = run(GestureEngine(), 30, 2000) { t ->
            hand(if (t < 1000) 0.3f else (0.3f + (t - 1000) * 0.0015f).coerceAtMost(0.7f), 0.5f)
        }
        assertEquals(listOf("Found"), states.eventNames())
    }

    @Test
    fun emojiGestureAppearsOnlyAfterItHasBeenHeld() {
        val engine = GestureEngine()
        assertEquals(null, engine.update(hand(0.5f, 0.5f), 1f, 0).gesture)
        assertEquals(null, engine.update(hand(0.5f, 0.5f), 1f, 100).gesture)
        assertEquals(EmojiGesture.RAISED_HAND, engine.update(hand(0.5f, 0.5f), 1f, 200).gesture)
        // One odd frame does not replace it.
        assertEquals(EmojiGesture.RAISED_HAND, engine.update(hand(0.5f, 0.5f, fingersOpen = false), 1f, 233).gesture)
        assertEquals(EmojiGesture.RAISED_HAND, engine.update(hand(0.5f, 0.5f), 1f, 266).gesture)
    }

    @Test
    fun oneNoisyFrameDoesNotDropAHeldPinch() {
        val states = run(GestureEngine(), 15, 3000) { t -> hand(0.5f, 0.5f, pinch = t !in 1500..1560) }
        assertEquals(listOf("Found", "PinchStart"), states.eventNames())
    }

    @Test
    fun losingTheHandMidPinchReleasesIt() {
        val states = run(GestureEngine(), 30, 3000) { t -> if (t < 1500) hand(0.5f, 0.5f, pinch = t > 500) else null }
        assertEquals(listOf("Found", "PinchStart", "PinchEnd", "Lost"), states.eventNames())
    }
}
