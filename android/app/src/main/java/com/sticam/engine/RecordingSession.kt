package com.sticam.engine

import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Environment
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * MP4 Recording Session
 *
 * Wraps MediaMuxer to record the H.264 stream (already being encoded by
 * CameraEngine) directly to an MP4 file — zero re-encoding overhead.
 *
 * Frame flow (no copy of video data — just muxer metadata overhead):
 *   CameraEngine.onEncodedData  ──────────────────────────────────┐
 *       (also feeds StreamServer)                                  ↓
 *                                              RecordingSession.offerFrame()
 *                                                  → MediaMuxer.writeSampleData()
 *                                                      → .mp4 on device storage
 *
 * Output location: Movies/Sticam/STICAM_yyyyMMdd_HHmmss.mp4
 *
 * Usage:
 *   val rec = RecordingSession(context)
 *   // Wire BEFORE first IDR frame:
 *   rec.configure(sps, pps, width, height, fps)
 *   rec.start()
 *   // On every encoded buffer:
 *   rec.offerFrame(data, info)
 *   // When done:
 *   rec.stop()  // returns the output File path
 */
class RecordingSession(private val context: android.content.Context) {

    companion object {
        private const val TAG = "SticamRecord"
    }

    private var muxer:       MediaMuxer? = null
    private var videoTrack:  Int         = -1
    private var outputFile:  File?       = null
    private val isRecording  = AtomicBoolean(false)
    private var muxerStarted = false

    // ── Output info ───────────────────────────────────────────────────────────

    val filePath: String? get() = outputFile?.absolutePath
    val recording: Boolean      get() = isRecording.get()

    var onDurationMs: ((Long) -> Unit)? = null
    private var startMs: Long = 0L

    // ═════════════════════════════════════════════════════════════════════
    //  Setup
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Must be called with the SPS and PPS from CameraEngine before start().
     * These are used to configure the MediaFormat for the muxer video track.
     */
    fun configure(
        sps: ByteArray,
        pps: ByteArray,
        width: Int,
        height: Int,
        fps: Int = 30,
    ) {
        if (isRecording.get()) return

        val dir = getOutputDir()
        val ts  = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        outputFile = File(dir, "STICAM_$ts.mp4")

        muxer = MediaMuxer(
            outputFile!!.absolutePath,
            MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )

        // Build the MediaFormat from SPS/PPS — this is what the muxer track needs
        val fmt = MediaFormat.createVideoFormat(
            MediaFormat.MIMETYPE_VIDEO_AVC, width, height
        ).apply {
            setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
            setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_BIT_RATE,   8_000_000)
        }

        videoTrack = muxer!!.addTrack(fmt)
        Log.i(TAG, "Configured: ${outputFile!!.name} [${width}x${height}@${fps}fps]")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Lifecycle
    // ═════════════════════════════════════════════════════════════════════

    fun start() {
        if (isRecording.get() || muxer == null || videoTrack < 0) return
        muxer!!.start()
        muxerStarted = true
        startMs = System.currentTimeMillis()
        isRecording.set(true)
        Log.i(TAG, "Recording started → ${outputFile?.name}")
    }

    /**
     * Stops recording and finalizes the MP4 file.
     * @return the output File, or null if recording never started.
     */
    fun stop(): File? {
        if (!isRecording.compareAndSet(true, false)) return outputFile
        try {
            if (muxerStarted) {
                muxer?.stop()
                Log.i(TAG, "Recording stopped. Duration: ${elapsedSeconds()}s → ${outputFile?.name}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Muxer stop error: ${e.message}")
        } finally {
            muxer?.release()
            muxer = null
            muxerStarted = false
            videoTrack = -1
        }
        return outputFile
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Frame ingestion
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Feed an encoded frame from CameraEngine.onEncodedData into the muxer.
     * Call this in parallel with StreamServer.onEncodedData.
     *
     * Skip FLAG_CODEC_CONFIG buffers — they are handled via csd-0/csd-1
     * in configure(). Passing them to writeSampleData causes a crash.
     */
    fun offerFrame(data: ByteArray, info: MediaCodec.BufferInfo) {
        if (!isRecording.get() || !muxerStarted) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return // SPS/PPS
        if (info.size <= 0) return

        try {
            val buf = java.nio.ByteBuffer.wrap(data, info.offset, info.size)
            val adjusted = MediaCodec.BufferInfo().apply {
                set(0, info.size, info.presentationTimeUs, info.flags)
            }
            muxer?.writeSampleData(videoTrack, buf, adjusted)
            onDurationMs?.invoke(elapsedMs())
        } catch (e: Exception) {
            Log.e(TAG, "writeSampleData error: ${e.message}")
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Helpers
    // ═════════════════════════════════════════════════════════════════════

    private fun elapsedMs()      = System.currentTimeMillis() - startMs
    fun elapsedSeconds(): Long   = elapsedMs() / 1000L

    private fun getOutputDir(): File {
        // Movies/Sticam on external storage, fallback to app internal
        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val dir = File(movies, "Sticam")
        if (!dir.exists()) dir.mkdirs()
        return dir
    }
}
