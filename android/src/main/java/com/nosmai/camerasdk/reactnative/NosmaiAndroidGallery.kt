package com.nosmai.camerasdk.reactnative

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicBoolean

internal class NosmaiAndroidGallery(
  context: Context,
  private val mainHandler: Handler
) {
  private val appContext = context.applicationContext
  private val ioExecutor = Executors.newSingleThreadExecutor(
    ThreadFactory { runnable ->
      Thread(runnable, "NosmaiGalleryWriter").apply { isDaemon = true }
    }
  )

  enum class MediaKind(
    val contractName: String,
    val mimeType: String,
    val extension: String,
    val relativeDirectory: String
  ) {
    PHOTO("photo", "image/jpeg", ".jpg", Environment.DIRECTORY_PICTURES),
    VIDEO("video", "video/mp4", ".mp4", Environment.DIRECTORY_MOVIES)
  }

  fun save(
    sourceUri: String,
    requestedName: String?,
    kind: MediaKind,
    completion: (NosmaiGalleryItem?, NosmaiMediaException?) -> Unit
  ) {
    val settled = AtomicBoolean(false)
    fun finish(item: NosmaiGalleryItem?, error: NosmaiMediaException?) {
      val delivery = Runnable {
        if (settled.compareAndSet(false, true)) runCatching { completion(item, error) }
      }
      if (android.os.Looper.myLooper() == mainHandler.looper) {
        delivery.run()
      } else {
        mainHandler.post(delivery)
      }
    }

    val sourceFile = try {
      resolveSourceFile(sourceUri, kind)
    } catch (error: NosmaiMediaException) {
      finish(null, error)
      return
    }

    if (
      Build.VERSION.SDK_INT <= Build.VERSION_CODES.P &&
      appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      finish(
        null,
        NosmaiMediaException(
          NosmaiMediaErrorCode.GALLERY_PERMISSION,
          "WRITE_EXTERNAL_STORAGE must be granted by the host on Android 9 and earlier"
        )
      )
      return
    }

    val displayName = normalizedDisplayName(requestedName, kind)
    try {
      ioExecutor.execute {
        try {
          validateMediaContent(sourceFile, kind)
          if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val uri = saveScoped(sourceFile, displayName, kind)
            finish(NosmaiGalleryItem(uri.toString(), kind.contractName), null)
          } else {
            saveLegacy(sourceFile, displayName, kind, ::finish)
          }
        } catch (error: NosmaiMediaException) {
          finish(null, error)
        } catch (error: Throwable) {
          finish(
            null,
            NosmaiMediaException(
              NosmaiMediaErrorCode.GALLERY_SAVE,
              "Unable to save ${kind.contractName} media to the gallery",
              error
            )
          )
        }
      }
    } catch (error: RejectedExecutionException) {
      finish(
        null,
        NosmaiMediaException(
          NosmaiMediaErrorCode.GALLERY_SAVE,
          "The gallery writer is unavailable",
          error
        )
      )
    }
  }

  fun shutdown() {
    ioExecutor.shutdownNow()
  }

  private fun resolveSourceFile(rawUri: String, kind: MediaKind): File {
    val uri = runCatching { Uri.parse(rawUri) }.getOrNull()
      ?: throw NosmaiMediaException(
        NosmaiMediaErrorCode.INVALID_ARGUMENT,
        "Media URI must be a valid local file URI"
      )
    if (
      uri.scheme != "file" ||
      (!uri.host.isNullOrBlank() && !uri.host.equals("localhost", ignoreCase = true))
    ) {
      throw NosmaiMediaException(
        NosmaiMediaErrorCode.INVALID_ARGUMENT,
        "Media URI must use file:// with no remote host"
      )
    }
    val path = uri.path?.takeIf(String::isNotBlank)
      ?: throw NosmaiMediaException(
        NosmaiMediaErrorCode.INVALID_ARGUMENT,
        "Media URI does not contain a local path"
      )
    val file = runCatching { File(path).canonicalFile }.getOrElse { error ->
      throw NosmaiMediaException(
        NosmaiMediaErrorCode.INVALID_ARGUMENT,
        "Media URI could not be resolved",
        error
      )
    }
    if (!file.isFile || !file.canRead() || file.length() <= 0L) {
      throw NosmaiMediaException(
        NosmaiMediaErrorCode.MEDIA_NOT_FOUND,
        "The local media file is missing, unreadable, or empty"
      )
    }
    val lowerName = file.name.lowercase(java.util.Locale.US)
    val validExtension = when (kind) {
      MediaKind.PHOTO -> lowerName.endsWith(".jpg") || lowerName.endsWith(".jpeg")
      MediaKind.VIDEO -> lowerName.endsWith(".mp4")
    }
    if (!validExtension) {
      throw NosmaiMediaException(
        NosmaiMediaErrorCode.INVALID_ARGUMENT,
        when (kind) {
          MediaKind.PHOTO -> "Image URI must reference a .jpg or .jpeg file"
          MediaKind.VIDEO -> "Video URI must reference a .mp4 file"
        }
      )
    }
    return file
  }

  private fun validateMediaContent(file: File, kind: MediaKind) {
    val validMedia = when (kind) {
      MediaKind.PHOTO -> BitmapFactory.Options().let { options ->
        options.inJustDecodeBounds = true
        BitmapFactory.decodeFile(file.absolutePath, options)
        options.outWidth > 0 &&
          options.outHeight > 0 &&
          options.outMimeType.equals("image/jpeg", ignoreCase = true)
      }
      MediaKind.VIDEO -> NosmaiMediaMuxer.hasUsableVideoTrack(file)
    }
    if (!validMedia) {
      throw NosmaiMediaException(
        NosmaiMediaErrorCode.INVALID_ARGUMENT,
        "Media URI does not contain valid ${kind.contractName} data"
      )
    }
  }

  private fun saveScoped(source: File, displayName: String, kind: MediaKind): Uri {
    val resolver = appContext.contentResolver
    val collection = when (kind) {
      MediaKind.PHOTO -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
      MediaKind.VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    }
    val values = ContentValues().apply {
      put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
      put(MediaStore.MediaColumns.MIME_TYPE, kind.mimeType)
      put(
        MediaStore.MediaColumns.RELATIVE_PATH,
        "${kind.relativeDirectory}/$GALLERY_DIRECTORY"
      )
      put(MediaStore.MediaColumns.IS_PENDING, 1)
    }
    val uri = resolver.insert(collection, values)
      ?: throw NosmaiMediaException(
        NosmaiMediaErrorCode.GALLERY_SAVE,
        "The system media provider rejected the gallery item"
      )
    var completed = false
    try {
      resolver.openOutputStream(uri, "w")?.use { output ->
        source.inputStream().use { input -> input.copyTo(output) }
      } ?: throw IOException("The system media output stream is unavailable")
      val published = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
      if (resolver.update(uri, published, null, null) <= 0) {
        throw IOException("The system media item could not be published")
      }
      completed = true
      return uri
    } finally {
      if (!completed) runCatching { resolver.delete(uri, null, null) }
    }
  }

  private fun saveLegacy(
    source: File,
    displayName: String,
    kind: MediaKind,
    completion: (NosmaiGalleryItem?, NosmaiMediaException?) -> Unit
  ) {
    val publicRoot = Environment.getExternalStoragePublicDirectory(kind.relativeDirectory)
    val directory = File(publicRoot, GALLERY_DIRECTORY)
    if (!directory.isDirectory && !directory.mkdirs()) {
      throw NosmaiMediaException(
        NosmaiMediaErrorCode.GALLERY_SAVE,
        "Unable to create the legacy gallery directory"
      )
    }
    val destination = uniqueDestination(directory, displayName)
    try {
      source.copyTo(destination, overwrite = false)
    } catch (error: Throwable) {
      destination.delete()
      throw error
    }
    if (!destination.isFile || destination.length() <= 0L) {
      destination.delete()
      throw NosmaiMediaException(
        NosmaiMediaErrorCode.GALLERY_SAVE,
        "The legacy gallery item is empty"
      )
    }

    val scanSettled = AtomicBoolean(false)
    val scanTimeout = Runnable {
      if (scanSettled.compareAndSet(false, true)) {
        destination.delete()
        completion(
          null,
          NosmaiMediaException(
            NosmaiMediaErrorCode.GALLERY_SAVE,
            "The saved media scan timed out"
          )
        )
      }
    }
    mainHandler.postDelayed(scanTimeout, MEDIA_SCAN_TIMEOUT_MS)
    try {
      MediaScannerConnection.scanFile(
        appContext,
        arrayOf(destination.absolutePath),
        arrayOf(kind.mimeType)
      ) { _, scannedUri ->
        if (scanSettled.compareAndSet(false, true)) {
          mainHandler.removeCallbacks(scanTimeout)
          if (scannedUri?.scheme == "content") {
            completion(
              NosmaiGalleryItem(scannedUri.toString(), kind.contractName),
              null
            )
          } else {
            destination.delete()
            completion(
              null,
              NosmaiMediaException(
                NosmaiMediaErrorCode.GALLERY_SAVE,
                "The saved media could not be registered with the gallery"
              )
            )
          }
        }
      }
    } catch (error: Throwable) {
      if (scanSettled.compareAndSet(false, true)) {
        mainHandler.removeCallbacks(scanTimeout)
        destination.delete()
      }
      throw error
    }
  }

  private fun normalizedDisplayName(requestedName: String?, kind: MediaKind): String {
    val fallback = "nosmai_${kind.contractName}_${System.currentTimeMillis()}"
    val base = requestedName
      ?.trim()
      ?.takeIf(String::isNotEmpty)
      ?.replace(Regex("[^A-Za-z0-9_-]"), "_")
      ?.take(MAX_NAME_LENGTH)
      ?.trim('_')
      ?.takeIf(String::isNotEmpty)
      ?: fallback
    return base + kind.extension
  }

  private fun uniqueDestination(directory: File, displayName: String): File {
    val requested = File(directory, displayName)
    if (!requested.exists()) return requested
    val base = displayName.substringBeforeLast('.')
    val extension = "." + displayName.substringAfterLast('.')
    for (index in 1..MAX_DUPLICATE_ATTEMPTS) {
      val candidate = File(directory, "${base}_$index$extension")
      if (!candidate.exists()) return candidate
    }
    throw NosmaiMediaException(
      NosmaiMediaErrorCode.GALLERY_SAVE,
      "Unable to allocate a unique gallery filename"
    )
  }

  companion object {
    private const val GALLERY_DIRECTORY = "Nosmai"
    private const val MAX_NAME_LENGTH = 80
    private const val MAX_DUPLICATE_ATTEMPTS = 10_000
    private const val MEDIA_SCAN_TIMEOUT_MS = 10_000L
  }
}
