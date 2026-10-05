package dev.tim.gesturelab

import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Replays captured clips through the current [GestureEngine], using the
 * landmarks saved in each run.json. No camera or video is needed.
 *
 * - `tests/manual-runs/` (pulled from the glasses, untracked): reported only.
 * - `app/src/test/resources/fixtures/` (promoted): every clip must pass.
 */
class ReplayRunsTest {

    private data class Replayed(val gesture: String, val recorded: String, val verdict: Verdict, val frames: Int)

    private fun replay(runFile: File): List<Replayed> {
        val clips = JSONObject(runFile.readText()).getJSONArray("clips")
        return (0 until clips.length()).mapNotNull { c ->
            val clip = clips.getJSONObject(c)
            // Runs recorded before a gesture was renamed or removed are skipped.
            val gesture = CaptureGesture.entries.firstOrNull { it.id == clip.getString("gesture") } ?: return@mapNotNull null
            val aspect = clip.getDouble("aspect").toFloat()
            val frames = clip.getJSONArray("observations")
            val engine = GestureEngine()
            val observations = (0 until frames.length()).map { f ->
                val frame = frames.getJSONObject(f)
                val points = frame.optJSONArray("landmarks")?.let { a ->
                    (0 until a.length()).map { i ->
                        val p = a.getJSONArray(i)
                        Point(p.getDouble(0).toFloat(), p.getDouble(1).toFloat(), p.optDouble(2, 0.0).toFloat())
                    }
                }
                val t = frame.getLong("elapsedMs")
                Observation(t, points, engine.update(points, aspect, t))
            }
            Replayed(gesture.id, clip.getString("verdict"), CaptureJudge.judge(gesture, observations), observations.size)
        }
    }

    private fun report(title: String, runFile: File, results: List<Replayed>) {
        println("$title ${runFile.parentFile?.name}/${runFile.name}")
        for (r in results) {
            val now = if (r.verdict.pass) "pass" else "fail"
            val change = if (now == r.recorded) "" else "   <-- was ${r.recorded} when recorded"
            println("  %-12s %-4s %-28s %3d frames%s".format(r.gesture, now, r.verdict.detected, r.frames, change))
        }
    }

    @Test
    fun manualRunsReplay() {
        // Unit tests run from the app module directory.
        val runs = File("../tests/manual-runs").listFiles { f -> File(f, "run.json").isFile }?.sorted().orEmpty()
        if (runs.isEmpty()) println("no captured runs in tests/manual-runs (record on the glasses, then ./pull-runs.sh)")
        for (run in runs) {
            val file = File(run, "run.json")
            report("run", file, replay(file))
        }
    }

    @Test
    fun promotedFixturesStillPass() {
        val dir = File(javaClass.classLoader!!.getResource("fixtures")!!.toURI())
        for (file in dir.listFiles { f -> f.extension == "json" }!!.sorted()) {
            val results = replay(file)
            report("fixture", file, results)
            val failed = results.filterNot { it.verdict.pass }
            assertTrue("${file.name}: ${failed.map { "${it.gesture} (${it.verdict.detected})" }}", failed.isEmpty())
        }
    }
}
