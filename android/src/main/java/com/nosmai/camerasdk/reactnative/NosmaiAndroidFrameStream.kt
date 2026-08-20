package com.nosmai.camerasdk.reactnative

import android.os.Handler
import android.util.Base64
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.nosmai.effect.api.NosmaiSDK
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

internal fun interface NosmaiAndroidFrameMetadataDelegate {
  fun onFrameAvailable(metadata: WritableMap)
}

/**
 * Owns the Android processed-frame callback and one bounded, latest-only CPU
 * frame slot. Frame bytes never cross the React Native bridge until JavaScript
 * explicitly consumes the slot with [takeLatest].
 */
internal class NosmaiAndroidFrameStream(
  private val mainHandler: Handler,
  private val metadataDelegate: NosmaiAndroidFrameMetadataDelegate,
  private val errorDelegate: (String, String) -> Unit
) {
  private data class Plane(
    val offset: Int,
    val byteLength: Int,
    val bytesPerRow: Int,
    val width: Int,
    val height: Int
  )

  private data class Packet(
    val data: ByteArray,
    val sequence: Long,
    val timestampSeconds: Double,
    val width: Int,
    val height: Int,
    val format: String,
    val colorRange: String,
    val planes: List<Plane>,
    val droppedFrames: Long
  )

  private val lock = Any()
  private val encodingExecutor: ExecutorService = Executors.newSingleThreadExecutor(
    ThreadFactory { runnable ->
      Thread(
        runnable,
        "NosmaiFrameEncoding-${THREAD_COUNTER.incrementAndGet()}"
      ).apply { isDaemon = true }
    }
  )

  private var active = false
  private var generation = 0L
  private var minimumIntervalNs = NANOS_PER_SECOND / DEFAULT_MAX_FPS
  private var lastAcceptedAtNs = Long.MIN_VALUE
  private var latest: Packet? = null
  private var readInProgress = false
  private var sequence = 0L
  private var droppedFrames = 0L
  private var reportedUnsupportedFrame = false
  private var startupCallbacksToDiscard = 0
  private var notBeforeTimestampNs = Long.MAX_VALUE
  private var dispatcherInstalled = false
  private var previousRenderMode = NosmaiSDK.RenderMode.PREVIEW_ONLY

  fun start(maxFramesPerSecond: Int) {
    synchronized(lock) {
      if (active) {
        throw IllegalStateException("The processed frame stream is already active")
      }
      generation++
      active = true
      minimumIntervalNs = NANOS_PER_SECOND / maxFramesPerSecond.coerceAtLeast(1)
      lastAcceptedAtNs = Long.MIN_VALUE
      latest = null
      readInProgress = false
      sequence = 0L
      droppedFrames = 0L
      reportedUnsupportedFrame = false
      notBeforeTimestampNs = System.nanoTime()
      // One callback primes the SDK's double PBO. A second startup discard
      // quarantines the single callback that can already be in JNI flight when
      // a prior logical stream is stopped. This is a tiny startup-only cost.
      startupCallbacksToDiscard = STARTUP_CALLBACKS_TO_DISCARD
    }

    try {
      previousRenderMode = NosmaiSDK.getRenderMode()
      if (!synchronized(lock) { dispatcherInstalled }) {
        // Keep this callback object installed for the process-scoped SDK
        // lifetime. The SDK reads/deletes its JNI global callback references
        // without a shared fence, so replacing or clearing them while GL work
        // can be in flight is unsafe. Logical generations below gate delivery.
        NosmaiSDK.setFrameCallback { frame -> acceptFrame(frame) }
        synchronized(lock) { dispatcherInstalled = true }
      }
      NosmaiSDK.setRenderMode(NosmaiSDK.RenderMode.DUAL_OUTPUT)
    } catch (error: Throwable) {
      stop()
      throw error
    }
  }

  fun stop() {
    val shouldDetach = synchronized(lock) {
      val wasActive = active
      generation++
      active = false
      latest = null
      readInProgress = false
      lastAcceptedAtNs = Long.MIN_VALUE
      startupCallbacksToDiscard = 0
      notBeforeTimestampNs = Long.MAX_VALUE
      wasActive
    }
    if (!shouldDetach) return

    runCatching {
      if (NosmaiSDK.getRenderMode() == NosmaiSDK.RenderMode.DUAL_OUTPUT) {
        NosmaiSDK.setRenderMode(previousRenderMode)
      }
    }
  }

  fun isActive(): Boolean = synchronized(lock) { active }

  fun takeLatest(promise: Promise) {
    val operationGeneration: Long
    val packet: Packet
    synchronized(lock) {
      if (!active) {
        reject(
          promise,
          ERROR_INVALID_STATE,
          "Start the processed frame stream before reading frames"
        )
        return
      }
      if (readInProgress) {
        reject(
          promise,
          ERROR_INVALID_STATE,
          "A processed frame read is already in progress"
        )
        return
      }
      packet = latest ?: run {
        promise.resolve(null)
        return
      }
      latest = null
      readInProgress = true
      operationGeneration = generation
    }

    encodingExecutor.execute {
      val encoded = runCatching {
        Base64.encodeToString(packet.data, Base64.NO_WRAP)
      }
      mainHandler.post {
        val stillCurrent = synchronized(lock) {
          val current = active && generation == operationGeneration
          if (generation == operationGeneration) readInProgress = false
          current
        }
        if (!stillCurrent) {
          reject(
            promise,
            ERROR_OPERATION_CANCELLED,
            "The processed frame read was cancelled because the stream stopped"
          )
          return@post
        }
        encoded.fold(
          onSuccess = { dataBase64 ->
            promise.resolve(packetMap(packet, dataBase64))
          },
          onFailure = { error ->
            reject(
              promise,
              ERROR_FRAME_STREAM,
              error.message ?: "Unable to encode the processed frame",
              error
            )
          }
        )
      }
    }
  }

  private fun acceptFrame(frame: NosmaiSDK.FrameData?) {
    if (frame == null) return

    val operationGeneration: Long
    val now = System.nanoTime()
    synchronized(lock) {
      if (!active) return
      if (frame.timestampNs > 0L && frame.timestampNs < notBeforeTimestampNs) {
        droppedFrames++
        return
      }
      if (startupCallbacksToDiscard > 0) {
        startupCallbacksToDiscard--
        droppedFrames++
        return
      }
      if (
        lastAcceptedAtNs != Long.MIN_VALUE &&
        now - lastAcceptedAtNs < minimumIntervalNs
      ) {
        droppedFrames++
        return
      }
      lastAcceptedAtNs = now
      operationGeneration = generation
    }

    val dimensions = checkedPixelCount(frame.width, frame.height)
      ?: return reportUnsupportedOnce("The native frame dimensions are invalid or too large")
    val packetDescription = when (frame.format) {
      FORMAT_RGBA -> rgbaDescription(frame.width, frame.height, dimensions)
      FORMAT_I420 -> i420Description(frame.width, frame.height, dimensions)
      else -> null
    } ?: return reportUnsupportedOnce(
      "The native frame format is unsupported or its dimensions are invalid"
    )

    val expectedBytes = packetDescription.first
    if (expectedBytes > MAX_FRAME_BYTES) {
      return reportUnsupportedOnce(
        "The native frame exceeds the ${MAX_FRAME_BYTES / (1024 * 1024)} MiB bridge safety limit"
      )
    }
    val source = frame.pixelBuffer ?: return
    if (source.size < expectedBytes) {
      return reportUnsupportedOnce("The native frame buffer is smaller than its declared format")
    }

    // The SDK pools callback objects and byte arrays, so retain an immutable
    // copy before returning from the native callback.
    val copied = source.copyOf(expectedBytes)
    val packet: Packet
    synchronized(lock) {
      if (!active || generation != operationGeneration) return
      if (latest != null) droppedFrames++
      sequence++
      val safeTimestampNs = frame.timestampNs.takeIf { it > 0L } ?: now
      packet = Packet(
        data = copied,
        sequence = sequence,
        timestampSeconds = safeTimestampNs.toDouble() / NANOS_PER_SECOND,
        width = frame.width,
        height = frame.height,
        format = packetDescription.second,
        colorRange = "full",
        planes = packetDescription.third,
        droppedFrames = droppedFrames
      )
      latest = packet
    }

    mainHandler.post {
      if (synchronized(lock) { active && generation == operationGeneration }) {
        metadataDelegate.onFrameAvailable(metadataMap(packet))
      }
    }
  }

  private fun reportUnsupportedOnce(message: String) {
    var operationGeneration = 0L
    val shouldReport = synchronized(lock) {
      if (!active || reportedUnsupportedFrame) {
        false
      } else {
        reportedUnsupportedFrame = true
        operationGeneration = generation
        true
      }
    }
    if (shouldReport) {
      mainHandler.post {
        if (synchronized(lock) { active && generation == operationGeneration }) {
          errorDelegate(ERROR_FRAME_STREAM, message)
        }
      }
    }
  }

  private fun rgbaDescription(
    width: Int,
    height: Int,
    pixels: Long
  ): Triple<Int, String, List<Plane>>? {
    val byteLength = pixels * 4L
    if (byteLength > Int.MAX_VALUE) return null
    return Triple(
      byteLength.toInt(),
      "rgba8888",
      listOf(Plane(0, byteLength.toInt(), width * 4, width, height))
    )
  }

  private fun i420Description(
    width: Int,
    height: Int,
    pixels: Long
  ): Triple<Int, String, List<Plane>>? {
    if (width % 2 != 0 || height % 2 != 0) return null
    val yLength = pixels
    val chromaLength = pixels / 4L
    val total = yLength + chromaLength * 2L
    if (total > Int.MAX_VALUE) return null
    val y = yLength.toInt()
    val chroma = chromaLength.toInt()
    return Triple(
      total.toInt(),
      "i420",
      listOf(
        Plane(0, y, width, width, height),
        Plane(y, chroma, width / 2, width / 2, height / 2),
        Plane(y + chroma, chroma, width / 2, width / 2, height / 2)
      )
    )
  }

  private fun checkedPixelCount(width: Int, height: Int): Long? {
    if (width <= 0 || height <= 0) return null
    val pixels = width.toLong() * height.toLong()
    return pixels.takeIf { it > 0 && it <= Int.MAX_VALUE }
  }

  private fun metadataMap(packet: Packet): WritableMap = Arguments.createMap().apply {
    putDouble("sequence", packet.sequence.toDouble())
    putDouble("timestampSeconds", packet.timestampSeconds)
    putInt("width", packet.width)
    putInt("height", packet.height)
    putString("format", packet.format)
    putString("colorRange", packet.colorRange)
    putInt("byteLength", packet.data.size)
    putArray("planes", planesArray(packet.planes))
    putDouble("droppedFrames", packet.droppedFrames.toDouble())
  }

  private fun packetMap(packet: Packet, dataBase64: String): WritableMap =
    metadataMap(packet).apply { putString("dataBase64", dataBase64) }

  private fun planesArray(planes: List<Plane>): WritableArray =
    Arguments.createArray().apply {
      planes.forEach { plane ->
        pushMap(Arguments.createMap().apply {
          putInt("offset", plane.offset)
          putInt("byteLength", plane.byteLength)
          putInt("bytesPerRow", plane.bytesPerRow)
          putInt("width", plane.width)
          putInt("height", plane.height)
        })
      }
    }

  private fun reject(
    promise: Promise,
    code: String,
    message: String,
    cause: Throwable? = null
  ) {
    if (cause == null) promise.reject(code, message) else promise.reject(code, message, cause)
  }

  companion object {
    const val MIN_MAX_FPS = 1
    const val MAX_MAX_FPS = 5
    const val DEFAULT_MAX_FPS = 2
    const val MAX_FRAME_BYTES = 8 * 1024 * 1024

    private const val FORMAT_RGBA = 0
    private const val FORMAT_I420 = 1
    private const val NANOS_PER_SECOND = 1_000_000_000L
    private const val ERROR_INVALID_STATE = "E_INVALID_STATE"
    private const val ERROR_OPERATION_CANCELLED = "E_OPERATION_CANCELLED"
    private const val ERROR_FRAME_STREAM = "E_FRAME_STREAM"
    private const val STARTUP_CALLBACKS_TO_DISCARD = 2
    private val THREAD_COUNTER = AtomicInteger()
  }
}
