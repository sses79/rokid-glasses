package dev.tim.gesturelab

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class MainActivity : ComponentActivity() {

    private lateinit var view: PlaygroundView
    private lateinit var tracker: HandTracker
    private lateinit var cameraThread: ExecutorService
    private val engine = GestureEngine()
    private val handler = Handler(Looper.getMainLooper())

    private var frames = 0
    private var windowStart = 0L
    private var inferenceMs = 0f
    private var lastAspect = 1f

    // Capture page state. All of it is touched on the main thread only.
    private var encoder: ClipEncoder? = null
    private var run: CaptureRun? = null
    private var phase = CapturePhase.IDLE
    private var phaseStart = 0L
    private var clipIndex = 0
    private var clipVideo: File? = null
    private var clipAspect = 1f
    private val observations = mutableListOf<Observation>()
    private val verdicts = arrayOfNulls<Verdict>(CaptureGesture.entries.size)
    private var captureNote = ""
    private var startHeldSince = 0L

    private val askCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) startCamera() else view.status = "CAMERA PERMISSION DENIED"
        view.invalidate()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        view = PlaygroundView(this)
        setContentView(view)
        refreshCaptureUi()

        cameraThread = Executors.newSingleThreadExecutor()
        tracker = HandTracker(this) { frame -> runOnUiThread { onFrame(frame) } }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startCamera()
        } else {
            askCamera.launch(Manifest.permission.CAMERA)
        }
    }

    private fun startCamera() {
        cameraThread.execute { tracker.start() }
        val providerFuture = ProcessCameraProvider.getInstance(this)
        providerFuture.addListener({
            try {
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(ANALYSIS_SIZE, ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER),
                            )
                            .build(),
                    )
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(cameraThread, tracker::analyze)

                val provider = providerFuture.get()
                provider.unbindAll()
                provider.bindToLifecycle(this, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                view.status = "CAMERA"
            } catch (e: Exception) {
                Log.e(TAG, "camera failed to start", e)
                view.status = "CAMERA FAILED"
                view.statusDetail = e.message.orEmpty().take(60)
            }
            refreshCaptureUi()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun onFrame(frame: TrackedFrame) {
        // Results still queued from before a clip began would corrupt its fresh
        // engine state and its timeline.
        if (phase == CapturePhase.RECORDING && frame.timestampMs < phaseStart) return
        val state = engine.update(frame.landmarks, frame.aspect, frame.timestampMs)
        lastAspect = frame.aspect
        if (phase == CapturePhase.RECORDING) {
            observations += Observation(frame.timestampMs - phaseStart, frame.landmarks, state)
        }
        view.onHand(state, frame.landmarks)

        frames++
        inferenceMs += (frame.inferenceMs - inferenceMs) * 0.2f
        val now = SystemClock.uptimeMillis()
        if (windowStart == 0L) windowStart = now
        if (now - windowStart >= STATS_INTERVAL_MS) {
            val fps = frames * 1000f / (now - windowStart)
            frames = 0
            windowStart = now
            view.status = "CAMERA  ${fps.toInt()} FPS"
            view.statusDetail = "${tracker.frameSize}  ${tracker.delegate}  ${inferenceMs.toInt()} MS"
            Log.i(
                TAG,
                "fps=%.1f latency=%.0fms delegate=%s frame=%s hand=%s gesture=%s pinch=%.2f".format(
                    fps, inferenceMs, tracker.delegate, tracker.frameSize,
                    if (state.tracked) "%.2f,%.2f".format(state.x, state.y) else "none",
                    state.gesture?.id ?: "none", state.pinchRatio,
                ),
            )
        }
        for (event in state.events) Log.i(TAG, "gesture $event")
        // On the Capture page a thumbs-up held for a moment starts the recording
        // sequence; the temple tap stays free for turning pages.
        val starting = view.onCapturePage && phase == CapturePhase.IDLE && state.gesture == EmojiGesture.THUMBS_UP
        if (!starting) {
            startHeldSince = 0L
        } else {
            if (startHeldSince == 0L) startHeldSince = frame.timestampMs
            if (frame.timestampMs - startHeldSince >= START_HOLD_MS) {
                startHeldSince = 0L
                startSequence()
            }
        }
    }

    // ---- Capture: one short clip per gesture, with the detector's output saved beside it.

    /** Records every gesture in turn, hands-free, as one new run. */
    private fun startSequence() {
        clipIndex = 0
        verdicts.fill(null)
        run = null
        view.locked = true
        nextClip()
        tick()
    }

    private fun nextClip() {
        phase = CapturePhase.COUNTDOWN
        phaseStart = SystemClock.uptimeMillis()
        handler.postDelayed(::beginRecording, COUNTDOWN_MS)
    }

    private fun beginRecording() {
        val gesture = CaptureGesture.entries[clipIndex]
        val current = run ?: CaptureRun(getExternalFilesDir(null) ?: filesDir, ::detectorInfo).also { run = it }
        observations.clear()
        clipAspect = lastAspect
        clipVideo = null
        // A fresh engine per clip, so the recorded events are exactly what a replay produces.
        engine.reset()

        val file = current.clipFile(clipIndex, gesture)
        try {
            val size = tracker.uprightSize
            encoder = ClipEncoder(file, size.width, size.height)
            tracker.clip = encoder
            clipVideo = file
        } catch (e: Exception) {
            Log.w(TAG, "could not record video: ${e.message}")
            captureNote = "NO VIDEO: LANDMARKS ONLY"
        }
        phase = CapturePhase.RECORDING
        phaseStart = SystemClock.uptimeMillis()
        handler.postDelayed(::finishClip, CLIP_MS)
    }

    private fun finishClip() {
        stopEncoder()
        val gesture = CaptureGesture.entries[clipIndex]
        val verdict = CaptureJudge.judge(gesture, observations)
        verdicts[clipIndex] = verdict
        run?.add(gesture, clipVideo, clipAspect, CLIP_MS, observations.toList(), verdict)
        Log.i(TAG, "clip ${gesture.id}: ${if (verdict.pass) "pass" else "fail"} (${verdict.detected}) frames=${observations.size} dir=${run?.dir}")
        clipIndex++
        if (clipIndex < CaptureGesture.entries.size) nextClip() else endSequence()
    }

    private fun endSequence() {
        handler.removeCallbacksAndMessages(null)
        stopEncoder(discard = true)
        phase = CapturePhase.IDLE
        view.locked = false
        // Whatever the hand was doing as the last clip ended must not act on the next page.
        engine.reset()
        refreshCaptureUi()
    }

    /**
     * Detaches the clip from the tracker, then closes it behind any frame still
     * being written. A clip cut short has no entry in run.json, so [discard] its video.
     */
    private fun stopEncoder(discard: Boolean = false) {
        val done = encoder ?: return
        val file = clipVideo
        encoder = null
        tracker.clip = null
        cameraThread.execute {
            val ok = done.finish()
            if (discard) file?.delete()
            Log.i(TAG, "clip video closed: ${done.frames} frames, ${if (discard) "discarded" else if (ok) "ok" else "unusable"}")
        }
    }

    /** Repaints the countdown and recording timer even when no camera frames arrive. */
    private fun tick() {
        refreshCaptureUi()
        if (phase != CapturePhase.IDLE) handler.postDelayed(::tick, 100)
    }

    private fun refreshCaptureUi() {
        val gestures = CaptureGesture.entries
        val started = verdicts.any { it != null } || phase != CapturePhase.IDLE
        val done = phase == CapturePhase.IDLE && clipIndex >= gestures.size
        val gesture = gestures[clipIndex.coerceAtMost(gestures.size - 1)]
        val elapsed = SystemClock.uptimeMillis() - phaseStart
        val passed = verdicts.count { it?.pass == true }
        view.capture = CaptureUi(
            phase = phase,
            symbol = if (phase == CapturePhase.IDLE) "" else gesture.symbol,
            title = when {
                phase != CapturePhase.IDLE -> "${clipIndex + 1}/${gestures.size}  ${gesture.label}"
                done -> "$passed / ${gestures.size} PASSED"
                started -> "STOPPED AT ${clipIndex + 1}/${gestures.size}"
                else -> "RECORD TEST CLIPS"
            },
            prompt = when (phase) {
                CapturePhase.COUNTDOWN -> "GET READY  %.0f".format(((COUNTDOWN_MS - elapsed) / 1000f).coerceAtLeast(0f) + 0.5f)
                CapturePhase.RECORDING -> "REC  %.1f".format(((CLIP_MS - elapsed) / 1000f).coerceAtLeast(0f))
                CapturePhase.IDLE -> "HOLD 👍 TO START"
            },
            detail = if (phase == CapturePhase.IDLE) "${gestures.size} GESTURES, ${(COUNTDOWN_MS + CLIP_MS) * gestures.size / 1000} S" else "HOLD IT UNTIL THE NEXT ONE",
            rows = gestures.mapIndexed { i, g ->
                val v = verdicts[i]
                val mark = when {
                    v == null -> if (i == clipIndex && phase != CapturePhase.IDLE) ">" else "-"
                    v.pass -> "PASS"
                    else -> "FAIL"
                }
                CaptureRow("$mark  ${g.label}", i == clipIndex && phase != CapturePhase.IDLE)
            },
            footer = captureNote.ifEmpty { if (phase == CapturePhase.IDLE) "TAP FOR NEXT PAGE" else "TAP TO STOP" },
        )
        view.invalidate()
    }

    private fun detectorInfo() = JSONObject()
        .put("model", "mediapipe hand_landmarker float16")
        .put("delegate", tracker.delegate)
        .put("frame", tracker.frameSize)
        .put("gestureConfig", GestureConfig().toString())

    // Temple controls arrive as key events; pages can always be turned without gestures.
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        Log.i(TAG, "key ${KeyEvent.keyCodeToString(keyCode)}")
        return when (keyCode) {
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_DPAD_CENTER -> {
                // A tap turns the page, except that it first stops a recording in progress.
                if (phase != CapturePhase.IDLE) endSequence() else view.turnPage(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT -> {
                view.turnPage(1)
                true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_LEFT -> {
                view.turnPage(-1)
                true
            }
            else -> super.onKeyUp(keyCode, event)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        stopEncoder(discard = true)
        cameraThread.execute { tracker.stop() }
        cameraThread.shutdown()
    }

    private companion object {
        const val TAG = "GestureLab"
        const val STATS_INTERVAL_MS = 2000L
        const val COUNTDOWN_MS = 3000L
        const val CLIP_MS = 4000L
        const val START_HOLD_MS = 1000L
        val ANALYSIS_SIZE = Size(640, 480)
    }
}
