package com.nosmai.camerasdk.reactnative

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import com.nosmai.effect.api.NosmaiPreviewView
import java.io.File
import java.io.FileOutputStream
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

/** Captures the rendered preview surface. Camera sensor still capture is intentionally not used. */
internal class NosmaiAndroidCapture(
  context: Context,
  private val mainHandler: Handler
) {
  private val appContext = context.applicationContext
  private val ioExecutor = Executors.newSingleThreadExecutor(
    ThreadFactory { runnable ->
      Thread(runnable, "NosmaiPhotoCapture").apply { isDaemon = true }
    }
  )
  private var captureInProgress = false

  fun capture(
    preview: NosmaiPreviewView,
    isSessionCurrent: () -> Boolean,
    completion: (NosmaiCapturedPhoto?, NosmaiMediaException?) -> Unit
  ) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "Rendered photo capture must start on the main looper"
    }
    if (captureInProgress) {
      completion(
        null,
        NosmaiMediaException(
          NosmaiMediaErrorCode.CAPTURE_IN_PROGRESS,
          "A rendered photo capture is already in progress"
        )
      )
      return
    }

    val surfaceView = findSurfaceView(preview)
    val width = surfaceView?.width ?: 0
    val height = surfaceView?.height ?: 0
    if (
      surfaceView == null ||
      width <= 0 ||
      height <= 0 ||
      !surfaceView.isAttachedToWindow ||
      !surfaceView.holder.surface.isValid
    ) {
      completion(
        null,
        NosmaiMediaException(
          NosmaiMediaErrorCode.CAPTURE_FAILED,
          "The rendered camera surface is not ready for capture"
        )
      )
      return
    }

    val bitmap = runCatching {
      Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }.getOrElse { error ->
      completion(
        null,
        NosmaiMediaException(
          NosmaiMediaErrorCode.CAPTURE_FAILED,
          "Unable to allocate the rendered photo buffer",
          error
        )
      )
      return
    }

    captureInProgress = true
    val settled = AtomicBoolean(false)
    val timeout = Runnable {
      if (settled.compareAndSet(false, true)) {
        captureInProgress = false
        completion(
          null,
          NosmaiMediaException(
            NosmaiMediaErrorCode.CAPTURE_FAILED,
            "Rendered photo capture timed out"
          )
        )
      }
    }
    mainHandler.postDelayed(timeout, CAPTURE_TIMEOUT_MS)

    fun settle(photo: NosmaiCapturedPhoto?, error: NosmaiMediaException?) {
      mainHandler.post {
        if (!settled.compareAndSet(false, true)) {
          photo?.let { deleteFileUri(it.uri) }
          return@post
        }
        mainHandler.removeCallbacks(timeout)
        captureInProgress = false
        if (!isSessionCurrent()) {
          photo?.let { deleteFileUri(it.uri) }
          completion(
            null,
            NosmaiMediaException(
              NosmaiMediaErrorCode.CAPTURE_FAILED,
              "Rendered photo capture was cancelled by a camera transition"
            )
          )
        } else {
          completion(photo, error)
        }
      }
    }

    try {
      PixelCopy.request(
        surfaceView,
        bitmap,
        { copyResult ->
          if (copyResult != PixelCopy.SUCCESS) {
            bitmap.recycle()
            settle(
              null,
              NosmaiMediaException(
                NosmaiMediaErrorCode.CAPTURE_FAILED,
                "Rendered photo capture failed (PixelCopy result $copyResult)"
              )
            )
            return@request
          }

          try {
            ioExecutor.execute {
              try {
                val directory = File(appContext.cacheDir, CAPTURE_DIRECTORY)
                if (!directory.exists() && !directory.mkdirs() && !directory.isDirectory) {
                  throw IllegalStateException("Unable to create the photo capture directory")
                }
                val file = File(directory, "nosmai_photo_${UUID.randomUUID()}.jpg")
                FileOutputStream(file).use { output ->
                  if (!bitmap.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, output)) {
                    throw IllegalStateException("JPEG encoding returned false")
                  }
                  output.fd.sync()
                }
                val length = file.length()
                if (length <= 0L) {
                  file.delete()
                  throw IllegalStateException("Rendered photo output is empty")
                }
                settle(
                  NosmaiCapturedPhoto(
                    uri = Uri.fromFile(file).toString(),
                    width = width,
                    height = height,
                    fileSizeBytes = length
                  ),
                  null
                )
              } catch (error: Throwable) {
                settle(
                  null,
                  NosmaiMediaException(
                    NosmaiMediaErrorCode.CAPTURE_FAILED,
                    "Unable to encode the rendered photo",
                    error
                  )
                )
              } finally {
                bitmap.recycle()
              }
            }
          } catch (error: RejectedExecutionException) {
            bitmap.recycle()
            settle(
              null,
              NosmaiMediaException(
                NosmaiMediaErrorCode.CAPTURE_FAILED,
                "The photo capture worker is unavailable",
                error
              )
            )
          }
        },
        mainHandler
      )
    } catch (error: Throwable) {
      bitmap.recycle()
      settle(
        null,
        NosmaiMediaException(
          NosmaiMediaErrorCode.CAPTURE_FAILED,
          "Unable to request rendered photo capture",
          error
        )
      )
    }
  }

  fun shutdown() {
    ioExecutor.shutdownNow()
  }

  private fun findSurfaceView(view: View): SurfaceView? {
    if (view is SurfaceView) return view
    if (view !is ViewGroup) return null
    for (index in 0 until view.childCount) {
      findSurfaceView(view.getChildAt(index))?.let { return it }
    }
    return null
  }

  private fun deleteFileUri(rawUri: String) {
    runCatching {
      val uri = Uri.parse(rawUri)
      if (uri.scheme == "file") uri.path?.let(::File)?.delete()
    }
  }

  companion object {
    private const val CAPTURE_DIRECTORY = "NosmaiCaptures"
    private const val JPEG_QUALITY = 90
    private const val CAPTURE_TIMEOUT_MS = 4_000L
  }
}
