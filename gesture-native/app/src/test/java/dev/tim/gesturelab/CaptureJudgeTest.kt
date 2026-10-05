package dev.tim.gesturelab

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CaptureJudgeTest {

    private fun clip(gestureAt: (Long) -> EmojiGesture?): List<Observation> =
        (0L until 4000L step 70).map { t -> Observation(t, null, HandState(tracked = true, gesture = gestureAt(t))) }

    @Test
    fun theFirstSecondIsNotJudged() {
        // Still holding the previous gesture while changing over.
        val late = clip { t -> if (t < 1000) EmojiGesture.RAISED_HAND else EmojiGesture.VICTORY }
        val verdict = CaptureJudge.judge(CaptureGesture.VICTORY, late)
        assertTrue(verdict.detected, verdict.pass)
        assertTrue(verdict.detected, verdict.detected.startsWith("100%"))
    }

    @Test
    fun theWrongGestureAfterSettlingFails() {
        val wrong = clip { t -> if (t < 1000) EmojiGesture.VICTORY else EmojiGesture.RAISED_HAND }
        assertFalse(CaptureJudge.judge(CaptureGesture.VICTORY, wrong).pass)
    }

    @Test
    fun aHandThatOnlyAppearsBrieflyAtTheStartFails() {
        val gone = (0L until 4000L step 70).map { t ->
            Observation(t, null, HandState(tracked = t < 800, gesture = EmojiGesture.FIST))
        }
        assertFalse(CaptureJudge.judge(CaptureGesture.FIST, gone).pass)
    }
}
