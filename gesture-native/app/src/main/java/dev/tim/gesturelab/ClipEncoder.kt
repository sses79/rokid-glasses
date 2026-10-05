package dev.tim.gesturelab

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import android.view.Surface
import java.io.File

/**
 * Writes the frames the hand tracker analysed to an H.264 .mp4.
 *
 * The glasses' camera cannot feed the tracker and a standard video recorder at
 * once, so clips are encoded from the tracker's own frames. That also means a
 * clip shows exactly what the detector saw. Use from a single thread.
 */
class ClipEncoder(private val file: File, private val width: Int, private val height: Int) {

    private val codec = MediaCodec.createEncoderByType(MIME)
    private val surface: Surface
    private val muxer = MediaMuxer(file.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val info = MediaCodec.BufferInfo()
    private var track = -1
    private var firstPtsUs = -1L
    var frames = 0
        private set

    init {
        val format = MediaFormat.createVideoFormat(MIME, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, 2_000_000)
            setInteger(MediaFormat.KEY_FRAME_RATE, 30)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }

    /** Adds one upright frame of the encoder's size. */
    fun add(bitmap: Bitmap) {
        val canvas = surface.lockHardwareCanvas()
        try {
            canvas.drawBitmap(bitmap, 0f, 0f, null)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
        frames++
        drain(false)
    }

    /** Flushes the encoder and closes the file. Returns false if nothing usable was written. */
    fun finish(): Boolean = try {
        codec.signalEndOfInputStream()
        drain(true)
        track >= 0
    } catch (e: Exception) {
        Log.w(TAG, "clip ${file.name} not finalised: ${e.message}")
        false
    } finally {
        runCatching { codec.stop() }
        codec.release()
        surface.release()
        runCatching { if (track >= 0) muxer.stop() }
        muxer.release()
    }

    private fun drain(untilEnd: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000L else 0L)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val isConfig = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && !isConfig && track >= 0) {
                        // Start the clip's clock at zero.
                        if (firstPtsUs < 0) firstPtsUs = info.presentationTimeUs
                        info.presentationTimeUs -= firstPtsUs
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private companion object {
        const val TAG = "GestureLab"
        const val MIME = MediaFormat.MIMETYPE_VIDEO_AVC
    }
}
