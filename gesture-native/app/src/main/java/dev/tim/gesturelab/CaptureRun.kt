package dev.tim.gesturelab

import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * One capture session on disk:
 *
 *     runs/YYYY-MM-DD-HHMMSS/
 *       run.json     what was asked for, what the detector saw, pass or fail
 *       clips/       the frames the detector analysed, one video per gesture
 *
 * run.json keeps every frame's landmarks, so a clip can be replayed through
 * [GestureEngine] later without the video.
 */
class CaptureRun(root: File, private val detector: () -> JSONObject) {

    private val started = Date()
    val dir = File(root, "runs/" + SimpleDateFormat("yyyy-MM-dd-HHmmss", Locale.US).format(started))
    private val clips = JSONArray()

    fun clipFile(index: Int, gesture: CaptureGesture): File {
        val clipDir = File(dir, "clips").apply { mkdirs() }
        return File(clipDir, "%02d-%s.mp4".format(index + 1, gesture.id))
    }

    /** Adds a finished clip and rewrites run.json, so a crash loses at most the clip in progress. */
    fun add(
        gesture: CaptureGesture,
        video: File?,
        aspect: Float,
        durationMs: Long,
        observations: List<Observation>,
        verdict: Verdict,
    ) {
        val events = JSONArray()
        val frames = JSONArray()
        for (o in observations) {
            for (e in o.state.events) {
                val entry = JSONObject().put("type", e::class.simpleName).put("atMs", o.elapsedMs)
                if (e is GestureEvent.PinchStart) entry.put("x", e.x.toDouble()).put("y", e.y.toDouble())
                events.put(entry)
            }
            frames.put(
                JSONObject()
                    .put("elapsedMs", o.elapsedMs)
                    .put("tracked", o.state.tracked)
                    .put("gesture", o.state.gesture?.id ?: JSONObject.NULL)
                    .put("pinching", o.state.pinching)
                    .put("pinchRatio", round(o.state.pinchRatio))
                    .put("x", round(o.state.x))
                    .put("y", round(o.state.y))
                    .put(
                        "landmarks",
                        o.landmarks?.let { pts -> JSONArray().also { a -> pts.forEach { a.put(JSONArray().put(round(it.x)).put(round(it.y)).put(round(it.z))) } } }
                            ?: JSONObject.NULL,
                    ),
            )
        }
        clips.put(
            JSONObject()
                .put("file", video?.let { "clips/${it.name}" } ?: JSONObject.NULL)
                .put("kind", "gesture")
                .put("gesture", gesture.id)
                .put("detected", verdict.detected)
                .put("verdict", if (verdict.pass) "pass" else "fail")
                .put("aspect", aspect.toDouble())
                .put("durationMs", durationMs)
                .put("frames", observations.size)
                .put("events", events)
                .put("observations", frames),
        )
        write()
    }

    private fun write() {
        val iso = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
        val run = JSONObject()
            .put("date", SimpleDateFormat("yyyy-MM-dd", Locale.US).format(started))
            .put("capturedAt", iso.format(started))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}")
            .put("firmware", Build.VERSION.INCREMENTAL)
            .put("app", BuildConfig.VERSION_NAME)
            .put("mirroredUserView", false)
            .put("detector", detector())
            .put("clips", clips)
        dir.mkdirs()
        File(dir, "run.json").writeText(run.toString(1))
    }

    private fun round(v: Float): Double = Math.round(v * 10000f) / 10000.0
}
