package com.sticam.engine

import android.content.ContentValues
import android.content.Context
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records the already-encoded H.264 stream without re-encoding it.
 *
 * Android 10+ output is created through MediaStore so the file is compatible
 * with scoped storage. Android 8/9 retain the shared Movies/Sticam location;
 * callers must obtain WRITE_EXTERNAL_STORAGE before configuring the session.
 */
class RecordingSession(context: Context) {

    companion object {
        private const val TAG = "SticamRecord"
        private const val DURATION_UPDATE_INTERVAL_MS = 500L
    }

    private val appContext = context.applicationContext
    private var muxer: MediaMuxer? = null
    private var videoTrack = -1
    private var muxerStarted = false
    private var outputFile: File? = null
    private var outputUri: Uri? = null
    private var outputDescriptor: ParcelFileDescriptor? = null
    private var outputLocation: String? = null
    private val isRecording = AtomicBoolean(false)
    private var awaitingFirstKeyFrame = true
    private var startMs = 0L
    private var lastDurationUpdateMs = 0L

    val filePath: String? get() = outputLocation
    val recording: Boolean get() = isRecording.get()

    var onDurationMs: ((Long) -> Unit)? = null

    /** Configure the muxer track from the encoder's SPS/PPS data. */
    @Synchronized
    fun configure(
        sps: ByteArray,
        pps: ByteArray,
        width: Int,
        height: Int,
        fps: Int = 30,
        bitrateMbps: Int = 8,
    ) {
        if (isRecording.get()) return
        releaseConfiguredOutput(deleteMediaStoreEntry = true)

        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "STICAM_$timestamp.mp4"

        try {
            muxer = createMuxer(displayName)
            val format = MediaFormat.createVideoFormat(
                MediaFormat.MIMETYPE_VIDEO_AVC,
                width,
                height,
            ).apply {
                setByteBuffer("csd-0", java.nio.ByteBuffer.wrap(sps))
                setByteBuffer("csd-1", java.nio.ByteBuffer.wrap(pps))
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_BIT_RATE, bitrateMbps * 1_000_000)
            }
            val configuredMuxer = muxer ?: error("MediaMuxer was not created")
            videoTrack = configuredMuxer.addTrack(format)
            Log.i(TAG, "Configured: $displayName [${width}x${height}@${fps}fps]")
        } catch (e: Exception) {
            releaseConfiguredOutput(deleteMediaStoreEntry = true)
            throw e
        }
    }

    @Synchronized
    fun start() {
        if (isRecording.get() || videoTrack < 0) return
        val configuredMuxer = muxer ?: return
        configuredMuxer.start()
        muxerStarted = true
        awaitingFirstKeyFrame = true
        startMs = System.currentTimeMillis()
        lastDurationUpdateMs = 0L
        isRecording.set(true)
        Log.i(TAG, "Recording started -> $outputLocation")
    }

    /** Stops and finalizes the recording, returning a file path or content Uri. */
    @Synchronized
    fun stop(): String? {
        if (!isRecording.compareAndSet(true, false)) {
            releaseConfiguredOutput(deleteMediaStoreEntry = true)
            return null
        }

        var completed = false
        val location = outputLocation
        try {
            if (muxerStarted) {
                muxer?.stop()
                completed = true
                Log.i(TAG, "Recording stopped. Duration: ${elapsedMs() / 1000L}s -> $outputLocation")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Muxer stop error", e)
        } finally {
            releaseConfiguredOutput(deleteMediaStoreEntry = !completed)
        }
        return if (completed) location else null
    }

    /**
     * Writes samples only after the first keyframe so a new MP4 never starts
     * with delta frames that depend on video preceding the recording.
     */
    @Synchronized
    fun offerFrame(data: ByteArray, info: MediaCodec.BufferInfo) {
        if (!isRecording.get() || !muxerStarted) return
        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0) return
        if (info.size <= 0 || data.isEmpty()) return

        val isKeyFrame = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
        if (awaitingFirstKeyFrame) {
            if (!isKeyFrame) return
            awaitingFirstKeyFrame = false
        }

        try {
            val sampleSize = minOf(info.size, data.size)
            val buffer = java.nio.ByteBuffer.wrap(data, 0, sampleSize)
            val ownedInfo = MediaCodec.BufferInfo().apply {
                set(0, sampleSize, info.presentationTimeUs, info.flags)
            }
            muxer?.writeSampleData(videoTrack, buffer, ownedInfo)

            val durationMs = elapsedMs()
            if (durationMs - lastDurationUpdateMs >= DURATION_UPDATE_INTERVAL_MS) {
                lastDurationUpdateMs = durationMs
                onDurationMs?.invoke(durationMs)
            }
        } catch (e: Exception) {
            Log.e(TAG, "writeSampleData error", e)
        }
    }

    private fun createMuxer(displayName: String): MediaMuxer {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Video.Media.RELATIVE_PATH,
                    "${Environment.DIRECTORY_MOVIES}/Sticam",
                )
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val uri = appContext.contentResolver.insert(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                values,
            ) ?: error("Unable to create MediaStore recording")
            outputUri = uri
            outputLocation = uri.toString()

            val descriptor = appContext.contentResolver.openFileDescriptor(uri, "rw")
                ?: error("Unable to open MediaStore recording")
            outputDescriptor = descriptor
            return MediaMuxer(
                descriptor.fileDescriptor,
                MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4,
            )
        }

        val movies = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        val directory = File(movies, "Sticam")
        if (!directory.exists() && !directory.mkdirs()) {
            error("Unable to create ${directory.absolutePath}")
        }
        val file = File(directory, displayName)
        outputFile = file
        outputLocation = file.absolutePath
        return MediaMuxer(file.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    private fun releaseConfiguredOutput(deleteMediaStoreEntry: Boolean) {
        runCatching { muxer?.release() }
        muxer = null
        muxerStarted = false
        videoTrack = -1
        awaitingFirstKeyFrame = true

        runCatching { outputDescriptor?.close() }
        outputDescriptor = null

        val uri = outputUri
        if (uri != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (deleteMediaStoreEntry) {
                runCatching { appContext.contentResolver.delete(uri, null, null) }
            } else {
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.IS_PENDING, 0)
                }
                runCatching { appContext.contentResolver.update(uri, values, null, null) }
            }
        }
        outputUri = null
        if (deleteMediaStoreEntry) {
            runCatching { outputFile?.delete() }
        }
        outputFile = null
        if (deleteMediaStoreEntry) outputLocation = null
    }

    private fun elapsedMs(): Long = System.currentTimeMillis() - startMs
}
