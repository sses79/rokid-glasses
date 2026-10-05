package dev.tim.gesturelab

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarkerResult

/** One tracking result: landmarks of the first hand (or null) in the upright frame. */
data class TrackedFrame(
    val landmarks: List<Point>?,
    val aspect: Float,
    val timestampMs: Long,
    val inferenceMs: Long,
)

/**
 * Runs MediaPipe Hand Landmarker on camera frames. [analyze] is called on the
 * camera thread; results arrive on a MediaPipe thread through [onFrame].
 */
class HandTracker(
    private val context: Context,
    private val onFrame: (TrackedFrame) -> Unit,
) {
    @Volatile private var landmarker: HandLandmarker? = null
    @Volatile var delegate: String = "-"
        private set
    @Volatile var frameSize: String = "-"
        private set

    // Written on the camera thread just before detectAsync, read in the result
    // callback; frames are processed one at a time so one slot is enough.
    @Volatile private var pendingAspect = 1f
    private val rotate = Matrix()

    /** While set, every analysed frame is also written to this clip. */
    @Volatile var clip: ClipEncoder? = null
    @Volatile var uprightSize = android.util.Size(480, 640)
        private set

    fun start() {
        landmarker = create(Delegate.GPU) ?: create(Delegate.CPU)
    }

    fun stop() {
        landmarker?.close()
        landmarker = null
    }

    private fun create(which: Delegate): HandLandmarker? = try {
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(BaseOptions.builder().setModelAssetPath(MODEL).setDelegate(which).build())
            .setRunningMode(RunningMode.LIVE_STREAM)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .setResultListener { result, _ -> deliver(result) }
            .setErrorListener { e -> onError(which, e) }
            .build()
        HandLandmarker.createFromOptions(context, options).also {
            delegate = which.name
            Log.i(TAG, "hand landmarker ready on $which")
        }
    } catch (e: Exception) {
        Log.w(TAG, "hand landmarker failed to start on $which: ${e.message}")
        null
    }

    private fun onError(which: Delegate, e: RuntimeException) {
        Log.w(TAG, "hand landmarker error on $which: ${e.message}")
        // The GPU path can fail only once frames arrive; retry on the CPU.
        if (which == Delegate.GPU) {
            landmarker?.close()
            landmarker = create(Delegate.CPU)
        }
    }

    fun analyze(image: ImageProxy) {
        val active = landmarker
        if (active == null) {
            image.close()
            return
        }
        try {
            val rotation = image.imageInfo.rotationDegrees
            val raw = image.toBitmap()
            // The sensor is mounted sideways. Turn the frame upright here rather
            // than asking MediaPipe to: its landmarks then need no remapping, and
            // the cursor moves the way the hand does.
            val bitmap = if (rotation == 0) raw else {
                rotate.setRotate(rotation.toFloat())
                Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, rotate, false)
            }
            pendingAspect = bitmap.width.toFloat() / bitmap.height
            if (uprightSize.width != bitmap.width || uprightSize.height != bitmap.height) {
                uprightSize = android.util.Size(bitmap.width, bitmap.height)
            }
            frameSize = "${bitmap.width}x${bitmap.height}"
            active.detectAsync(BitmapImageBuilder(bitmap).build(), SystemClock.uptimeMillis())
            try {
                clip?.add(bitmap)
            } catch (e: Exception) {
                Log.w(TAG, "clip frame dropped: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "frame dropped: ${e.message}")
        } finally {
            image.close()
        }
    }

    private fun deliver(result: HandLandmarkerResult) {
        val now = SystemClock.uptimeMillis()
        val hand = result.landmarks().firstOrNull()?.map { Point(it.x(), it.y(), it.z()) }
        onFrame(TrackedFrame(hand, pendingAspect, now, now - result.timestampMs()))
    }

    private companion object {
        const val TAG = "GestureLab"
        const val MODEL = "hand_landmarker.task"
    }
}
