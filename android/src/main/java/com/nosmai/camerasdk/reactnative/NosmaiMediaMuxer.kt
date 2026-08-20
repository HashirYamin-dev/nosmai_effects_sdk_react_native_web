package com.nosmai.camerasdk.reactnative

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer
import kotlin.math.max

/** Copies the SDK video track and microphone AAC track into one normalized MP4. */
internal object NosmaiMediaMuxer {
  fun hasUsableVideoTrack(file: File?): Boolean {
    if (file == null || !file.isFile || file.length() <= 0L) return false
    var extractor: MediaExtractor? = null
    return try {
      extractor = MediaExtractor().apply { setDataSource(file.absolutePath) }
      val track = findTrack(extractor, "video/")
      if (track < 0) {
        false
      } else {
        extractor.selectTrack(track)
        extractor.sampleTime >= 0L
      }
    } catch (_: Throwable) {
      false
    } finally {
      runCatching { extractor?.release() }
    }
  }

  fun merge(
    videoFile: File,
    audioFile: File,
    outputFile: File,
    audioLeadUs: Long
  ) {
    var videoExtractor: MediaExtractor? = null
    var audioExtractor: MediaExtractor? = null
    var muxer: MediaMuxer? = null
    var muxerStarted = false
    var completed = false

    try {
      require(hasUsableVideoTrack(videoFile)) {
        "The temporary video file has no readable video track"
      }
      require(audioFile.isFile && audioFile.length() > 0L) {
        "The temporary audio file is missing or empty"
      }
      outputFile.parentFile?.let { parent ->
        require(parent.isDirectory || parent.mkdirs()) {
          "Unable to create the final recording directory"
        }
      }
      if (outputFile.exists() && !outputFile.delete()) {
        throw IllegalStateException("Unable to replace the final recording file")
      }

      videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
      audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
      val videoSourceTrack = findTrack(videoExtractor, "video/")
      val audioSourceTrack = findTrack(audioExtractor, "audio/")
      if (videoSourceTrack < 0) throw IllegalStateException("No video track was found")
      if (audioSourceTrack < 0) throw IllegalStateException("No audio track was found")

      videoExtractor.selectTrack(videoSourceTrack)
      audioExtractor.selectTrack(audioSourceTrack)
      val videoFormat = videoExtractor.getTrackFormat(videoSourceTrack)
      val audioFormat = audioExtractor.getTrackFormat(audioSourceTrack)

      muxer = MediaMuxer(
        outputFile.absolutePath,
        MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
      )
      val videoDestinationTrack = muxer.addTrack(videoFormat)
      val audioDestinationTrack = muxer.addTrack(audioFormat)
      muxer.start()
      muxerStarted = true

      val videoDurationUs = copyTrack(
        extractor = videoExtractor,
        muxer = muxer,
        destinationTrack = videoDestinationTrack,
        format = videoFormat,
        trimStartUs = 0L,
        outputOffsetUs = 0L,
        maximumOutputUs = Long.MAX_VALUE
      )
      if (videoDurationUs <= 0L) {
        throw IllegalStateException("The video track contains no media samples")
      }

      val audioDurationUs = copyTrack(
        extractor = audioExtractor,
        muxer = muxer,
        destinationTrack = audioDestinationTrack,
        format = audioFormat,
        trimStartUs = max(0L, audioLeadUs),
        outputOffsetUs = 0L,
        maximumOutputUs = videoDurationUs + AUDIO_END_TOLERANCE_US
      )
      if (audioDurationUs <= 0L) {
        throw IllegalStateException("The audio track contains no aligned media samples")
      }

      muxer.stop()
      muxerStarted = false
      muxer.release()
      muxer = null
      if (!hasUsableVideoTrack(outputFile)) {
        throw IllegalStateException("The final recording file has no readable video track")
      }
      completed = true
    } finally {
      runCatching { videoExtractor?.release() }
      runCatching { audioExtractor?.release() }
      if (muxer != null) {
        if (muxerStarted) runCatching { muxer.stop() }
        runCatching { muxer.release() }
      }
      if (!completed) runCatching { outputFile.delete() }
    }
  }

  private fun findTrack(extractor: MediaExtractor, prefix: String): Int {
    for (index in 0 until extractor.trackCount) {
      val mime = extractor.getTrackFormat(index).getString(MediaFormat.KEY_MIME)
      if (mime?.startsWith(prefix) == true) return index
    }
    return -1
  }

  /** Returns the final written presentation timestamp, normalized to microseconds. */
  private fun copyTrack(
    extractor: MediaExtractor,
    muxer: MediaMuxer,
    destinationTrack: Int,
    format: MediaFormat,
    trimStartUs: Long,
    outputOffsetUs: Long,
    maximumOutputUs: Long
  ): Long {
    val capacity = runCatching {
      if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
        format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)
      } else {
        DEFAULT_BUFFER_BYTES
      }
    }.getOrDefault(DEFAULT_BUFFER_BYTES)
      .coerceAtLeast(DEFAULT_BUFFER_BYTES)
      .coerceAtMost(MAX_BUFFER_BYTES)
    val buffer = ByteBuffer.allocateDirect(capacity)
    val info = MediaCodec.BufferInfo()
    val firstSourcePtsUs = extractor.sampleTime
    if (firstSourcePtsUs < 0L) return 0L

    var lastOutputPtsUs = -1L
    while (true) {
      val sourcePtsUs = extractor.sampleTime
      if (sourcePtsUs < 0L) break
      val relativeSourcePtsUs = max(0L, sourcePtsUs - firstSourcePtsUs)
      if (relativeSourcePtsUs < trimStartUs) {
        if (!extractor.advance()) break
        continue
      }

      val outputPtsUs = max(0L, relativeSourcePtsUs - trimStartUs + outputOffsetUs)
      if (outputPtsUs > maximumOutputUs) break
      buffer.clear()
      val sampleSize = extractor.readSampleData(buffer, 0)
      if (sampleSize < 0) break
      if (sampleSize > buffer.capacity()) {
        throw IllegalStateException("A media sample exceeds the mux buffer capacity")
      }
      info.set(0, sampleSize, outputPtsUs, extractor.sampleFlags)
      muxer.writeSampleData(destinationTrack, buffer, info)
      lastOutputPtsUs = outputPtsUs
      if (!extractor.advance()) break
    }
    return max(0L, lastOutputPtsUs)
  }

  private const val DEFAULT_BUFFER_BYTES = 1024 * 1024
  private const val MAX_BUFFER_BYTES = 32 * 1024 * 1024
  private const val AUDIO_END_TOLERANCE_US = 100_000L
}
