package com.nosmai.camerasdk.reactnative

internal class NosmaiMediaException(
  val code: String,
  override val message: String,
  override val cause: Throwable? = null
) : Exception(message, cause)

internal data class NosmaiCapturedPhoto(
  val uri: String,
  val width: Int,
  val height: Int,
  val fileSizeBytes: Long,
  val mimeType: String = "image/jpeg"
)

internal data class NosmaiRecordedVideo(
  val uri: String,
  val durationSeconds: Double,
  val fileSizeBytes: Long,
  val hasAudio: Boolean,
  val muxWarning: Throwable? = null,
  val mimeType: String = "video/mp4"
)

internal data class NosmaiGalleryItem(
  val uri: String,
  val mediaType: String
)

internal object NosmaiMediaErrorCode {
  const val INVALID_ARGUMENT = "E_INVALID_ARGUMENT"
  const val CAPTURE_FAILED = "E_CAPTURE_FAILED"
  const val CAPTURE_IN_PROGRESS = "E_CAPTURE_IN_PROGRESS"
  const val RECORDING_PERMISSION = "E_RECORDING_PERMISSION"
  const val RECORDING_IN_PROGRESS = "E_RECORDING_IN_PROGRESS"
  const val NOT_RECORDING = "E_NOT_RECORDING"
  const val RECORDING_START = "E_RECORDING_START"
  const val RECORDING_STOP = "E_RECORDING_STOP"
  const val RECORDING_STORAGE_FULL = "E_RECORDING_STORAGE_FULL"
  const val RECORDING_WRITE = "E_RECORDING_WRITE"
  const val RECORDING_AUDIO_MUX = "E_RECORDING_AUDIO_MUX"
  const val RECORDING_INTERRUPTED = "E_RECORDING_INTERRUPTED"
  const val MEDIA_NOT_FOUND = "E_MEDIA_NOT_FOUND"
  const val GALLERY_PERMISSION = "E_GALLERY_PERMISSION"
  const val GALLERY_SAVE = "E_GALLERY_SAVE"
}
