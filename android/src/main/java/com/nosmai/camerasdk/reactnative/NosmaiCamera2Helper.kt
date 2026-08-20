package com.nosmai.camerasdk.reactnative

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraAccessException
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.util.Range
import android.util.Size
import android.view.Surface
import java.nio.ByteBuffer
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Owns one Camera2 capture session for the React Native bridge.
 *
 * This class deliberately has no dependency on the Nosmai SDK or React Native.
 * Its OES [SurfaceTexture] is owned by the caller; this helper owns only the
 * temporary [Surface] wrapper. YUV plane buffers are valid only for the duration
 * of [FrameCallback.onFrameAvailable].
 */
internal class NosmaiCamera2Helper(
  context: Context,
  isFrontCamera: Boolean = true,
) {
  internal enum class InputMode {
    YUV,
    OES,
  }

  internal fun interface FrameCallback {
    fun onFrameAvailable(
      y: ByteBuffer,
      u: ByteBuffer,
      v: ByteBuffer,
      width: Int,
      height: Int,
      yStride: Int,
      uStride: Int,
      vStride: Int,
      uPixelStride: Int,
      vPixelStride: Int,
    )
  }

  internal fun interface CameraConfigurationCallback {
    fun onCameraConfigured(
      width: Int,
      height: Int,
      sensorOrientation: Int,
      isFrontCamera: Boolean,
    )
  }

  internal fun interface CameraReadyCallback {
    fun onCameraReady()
  }

  internal fun interface CameraErrorCallback {
    fun onCameraError(code: String, message: String, cause: Throwable?)
  }

  internal fun interface CameraStoppedCallback {
    fun onCameraStopped(failure: Throwable?, safeToRestart: Boolean)
  }

  private data class CameraSelection(
    val id: String,
    val characteristics: CameraCharacteristics,
    val isFrontCamera: Boolean,
  )

  private val applicationContext = context.applicationContext
  private val cameraManager =
    applicationContext.getSystemService(Context.CAMERA_SERVICE) as CameraManager
  private val openCloseLock = Semaphore(1)
  private val cameraClosedLock = Semaphore(0)
  private val surfaceLock = Any()
  private val frameProcessingLock = Any()
  private val lifecycleLock = Any()
  private val stopCompletionLock = Any()
  private val stopWatchdogHandler = Handler(Looper.getMainLooper())

  @Volatile private var cameraDevice: CameraDevice? = null
  @Volatile private var captureSession: CameraCaptureSession? = null
  @Volatile private var imageReader: ImageReader? = null
  @Volatile private var backgroundThread: HandlerThread? = null
  @Volatile private var backgroundHandler: Handler? = null
  @Volatile private var previewSize: Size? = null
  @Volatile private var sensorOrientation = 0
  @Volatile private var cameraCharacteristics: CameraCharacteristics? = null
  @Volatile private var previewRequestBuilder: CaptureRequest.Builder? = null
  @Volatile private var flashMode = LIGHT_MODE_OFF
  @Volatile private var torchMode = LIGHT_MODE_OFF
  @Volatile private var autoFlashRequired = false
  @Volatile private var photoFlashActive = false

  @Volatile private var cameraOpened = false
  @Volatile private var openingCamera = false
  @Volatile private var closingCamera = false
  @Volatile private var stopThreadWhenCameraCloses = false
  @Volatile private var frameErrorReported = false
  @Volatile private var quiescedForSurfaceRelease = false
  @Volatile private var pendingStopFailure: Throwable? = null
  @Volatile private var deviceClosePending = false

  private val stopCompletionCallbacks = mutableListOf<CameraStoppedCallback>()
  private var stopInProgress = false
  private var stopTimeoutDelivered = false
  private var lateCloseWatchdogGeneration = 0L

  @Volatile private var requestedFacing =
    if (isFrontCamera) {
      CameraCharacteristics.LENS_FACING_FRONT
    } else {
      CameraCharacteristics.LENS_FACING_BACK
    }
  @Volatile private var selectedIsFrontCamera = isFrontCamera
  @Volatile private var inputMode = InputMode.YUV
  @Volatile private var targetWidth = DEFAULT_TARGET_WIDTH
  @Volatile private var targetHeight = DEFAULT_TARGET_HEIGHT
  @Volatile private var captureSessionGeneration = 0L

  private var oesSurfaceTexture: SurfaceTexture? = null
  private var oesSurface: Surface? = null

  @Volatile private var frameCallback: FrameCallback? = null
  @Volatile private var configurationCallback: CameraConfigurationCallback? = null
  @Volatile private var readyCallback: CameraReadyCallback? = null
  @Volatile private var errorCallback: CameraErrorCallback? = null

  internal fun setFrameCallback(callback: FrameCallback?) {
    frameCallback = callback
  }

  internal fun setCameraConfigurationCallback(callback: CameraConfigurationCallback?) {
    configurationCallback = callback
  }

  internal fun setCameraReadyCallback(callback: CameraReadyCallback?) {
    readyCallback = callback
  }

  internal fun setCameraErrorCallback(callback: CameraErrorCallback?) {
    errorCallback = callback
  }

  internal fun setTargetDimensions(width: Int, height: Int) {
    if (width > 0 && height > 0) {
      targetWidth = width
      targetHeight = height
    }
  }

  internal fun setFacing(isFrontCamera: Boolean) {
    requestedFacing =
      if (isFrontCamera) {
        CameraCharacteristics.LENS_FACING_FRONT
      } else {
        CameraCharacteristics.LENS_FACING_BACK
      }
  }

  internal fun setInputMode(mode: InputMode) {
    inputMode = mode
    if (mode == InputMode.OES) {
      frameCallback = null
    }
  }

  /** Set the caller-owned OES texture before [startCamera]. */
  internal fun setOesPreviewSurfaceTexture(surfaceTexture: SurfaceTexture?) {
    synchronized(surfaceLock) {
      replaceOesSurfaceLocked(surfaceTexture)
    }
  }

  /**
   * Rebuild the active OES capture session around a replacement texture.
   * Camera work is serialized on the helper's Camera2 thread.
   */
  internal fun reconfigureOesPreviewSurfaceTexture(surfaceTexture: SurfaceTexture?) {
    val handler = backgroundHandler
    if (handler == null || !isCameraOpened()) {
      setOesPreviewSurfaceTexture(surfaceTexture)
      return
    }

    handler.post {
      if (closingCamera || inputMode != InputMode.OES) {
        return@post
      }
      closeCaptureSessionQuietly()
      synchronized(surfaceLock) {
        replaceOesSurfaceLocked(surfaceTexture)
      }
      if (surfaceTexture == null) {
        cameraOpened = false
        notifyError(ERROR_OES_SURFACE, "OES SurfaceTexture is not available")
      } else {
        createCaptureSession()
      }
    }
  }

  /**
   * Starts an asynchronous Camera2 open. `true` means the request was accepted;
   * preview readiness is reported only through [CameraReadyCallback].
   */
  internal fun startCamera(): Boolean {
    if (quiescedForSurfaceRelease) {
      return false
    }
    if (isCameraStarting() || isCameraOpened()) {
      return true
    }
    if (applicationContext.checkSelfPermission(Manifest.permission.CAMERA) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      notifyError(ERROR_PERMISSION, "Camera permission has not been granted")
      return false
    }

    startBackgroundThread()
    val started = openCamera()
    if (!started) {
      if (closeCamera()) {
        stopBackgroundThread()
      }
    }
    return started
  }

  /**
   * Stops capture and reports completion only after CameraDevice closure and
   * worker teardown. A late open callback therefore remains serialized ahead
   * of the controller's next camera start.
   */
  internal fun stopCamera(callback: CameraStoppedCallback) {
    var timeoutAlreadyDelivered = false
    val shouldStart = synchronized(stopCompletionLock) {
      if (stopInProgress) {
        timeoutAlreadyDelivered = stopTimeoutDelivered
        if (!timeoutAlreadyDelivered) stopCompletionCallbacks += callback
        false
      } else {
        stopCompletionCallbacks += callback
        stopInProgress = true
        stopTimeoutDelivered = false
        true
      }
    }
    if (!shouldStart) {
      if (timeoutAlreadyDelivered) {
        runCatching {
          callback.onCameraStopped(
            pendingStopFailure ?: IllegalStateException("Camera close is still pending"),
            false
          )
        }
      }
      return
    }
    if (closeCamera()) {
      stopBackgroundThread()
      completeCameraStop(pendingStopFailure, safeToRestart = true)
    }
  }

  /** Fire-and-forget teardown for stale helpers that no longer own a session. */
  internal fun stopCamera() {
    stopCamera(CameraStoppedCallback { _, _ -> })
  }

  /**
   * Synchronously prevents Camera2 from writing into a preview surface that is
   * about to leave the Android window. Full device/thread teardown still runs
   * through [stopCamera] on the serialized camera executor.
   */
  internal fun quiesceForSurfaceRelease() {
    synchronized(lifecycleLock) {
      quiescedForSurfaceRelease = true
      closingCamera = true
      ++captureSessionGeneration
      frameCallback = null
    }
    captureSession?.let { session ->
      try {
        session.stopRepeating()
        session.abortCaptures()
      } catch (_: CameraAccessException) {
        // The device may already be closing.
      } catch (_: IllegalStateException) {
        // The session may already be closed.
      }
    }
    closeCaptureSessionQuietly()
    synchronized(frameProcessingLock) {
      closeImageReaderQuietlyLocked()
      synchronized(surfaceLock) {
        releaseOesSurfaceLocked(clearTexture = false)
      }
    }
    cameraOpened = false
  }

  internal fun isCameraOpened(): Boolean =
    cameraOpened && cameraDevice != null && captureSession != null

  internal fun isCameraStarting(): Boolean =
    openingCamera || (!closingCamera && cameraDevice != null && captureSession == null)

  internal fun isValidState(): Boolean = isCameraOpened()

  internal fun getPreviewSize(): Size? = previewSize

  internal fun getSensorOrientation(): Int = sensorOrientation

  internal fun isFrontCamera(): Boolean = selectedIsFrontCamera

  /** Returns the flash capability for the selected/requested camera only. */
  internal fun hasFlash(): Boolean = runCatching {
    val characteristics = cameraCharacteristics ?: characteristicsForRequestedFacing()
    characteristics?.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
  }.getOrDefault(false)

  /** Camera2 has no separate torch capability flag; it shares the flash unit. */
  internal fun hasTorch(): Boolean = hasFlash()

  /**
   * Stores and applies a still-flash preference. Torch wins while it is on.
   * `auto` uses Camera2 auto-flash AE; the SDK's rendered photo capture consumes
   * the illuminated preview frame rather than opening a second camera session.
   */
  internal fun setFlashMode(mode: String): Boolean {
    if (mode !in SUPPORTED_LIGHT_MODES) return false
    if (mode != LIGHT_MODE_OFF && !hasFlash()) return false
    if (mode == LIGHT_MODE_AUTO && !supportsAutoFlash()) return false
    flashMode = mode
    refreshLightModes()
    return true
  }

  internal fun getFlashMode(): String = flashMode

  private fun supportsAutoFlash(): Boolean = runCatching {
    val characteristics = cameraCharacteristics ?: characteristicsForRequestedFacing()
    val modes = characteristics
      ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
      ?: intArrayOf()
    modes.contains(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
  }.getOrDefault(false)

  internal fun setTorchMode(mode: String): Boolean {
    if (mode !in SUPPORTED_TORCH_MODES) return false
    if (mode != LIGHT_MODE_OFF && !hasTorch()) return false
    torchMode = mode
    refreshLightModes()
    return true
  }

  internal fun getTorchMode(): String = torchMode

  /**
   * Warms the flash unit for a rendered preview-frame photo. The ordinary
   * repeating request never uses FLASH_MODE_SINGLE, which is unreliable for a
   * PixelCopy/YUV capture and can strobe on some Camera2 implementations.
   */
  internal fun preparePhotoFlash(completion: (Boolean, Throwable?) -> Unit) {
    val handler = backgroundHandler
    if (handler == null) {
      completion(false, IllegalStateException("Camera thread is unavailable"))
      return
    }
    handler.post {
      val shouldIlluminate =
        torchMode != LIGHT_MODE_ON &&
          (flashMode == LIGHT_MODE_ON ||
            (flashMode == LIGHT_MODE_AUTO && autoFlashRequired))
      if (!shouldIlluminate) {
        completion(false, null)
        return@post
      }
      val builder = previewRequestBuilder
      val session = captureSession
      val characteristics = cameraCharacteristics
      if (
        builder == null || session == null || characteristics == null ||
        closingCamera || !cameraOpened
      ) {
        completion(false, IllegalStateException("Camera is not ready for flash capture"))
        return@post
      }
      try {
        photoFlashActive = true
        applyLightModes(builder, characteristics)
        session.setRepeatingRequest(builder.build(), captureResultCallback, handler)
        handler.postDelayed(
          { completion(true, null) },
          PHOTO_FLASH_WARMUP_MS,
        )
      } catch (error: Throwable) {
        photoFlashActive = false
        completion(false, error)
      }
    }
  }

  internal fun finishPhotoFlash() {
    val handler = backgroundHandler ?: run {
      photoFlashActive = false
      return
    }
    handler.post {
      if (!photoFlashActive) return@post
      photoFlashActive = false
      val builder = previewRequestBuilder ?: return@post
      val session = captureSession ?: return@post
      val characteristics = cameraCharacteristics ?: return@post
      if (closingCamera || !cameraOpened) return@post
      runCatching {
        applyLightModes(builder, characteristics)
        session.setRepeatingRequest(builder.build(), captureResultCallback, handler)
      }.onFailure { error ->
        notifyError(ERROR_CAMERA_ACCESS, "Unable to restore the camera light mode", error)
      }
    }
  }

  private fun startBackgroundThread() {
    if (backgroundThread != null) {
      return
    }
    val thread = HandlerThread(THREAD_NAME)
    thread.start()
    backgroundThread = thread
    backgroundHandler = Handler(thread.looper)
  }

  private fun stopBackgroundThread() {
    val thread = backgroundThread ?: return
    val handler = backgroundHandler
    handler?.removeCallbacksAndMessages(null)
    thread.quitSafely()

    if (Looper.myLooper() != thread.looper) {
      try {
        thread.join(THREAD_JOIN_TIMEOUT_MS)
        if (thread.isAlive) {
          thread.quit()
          thread.join(THREAD_FORCE_JOIN_TIMEOUT_MS)
        }
      } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        Log.w(TAG, "Interrupted while stopping Camera2 thread", interrupted)
      }
    }

    if (backgroundThread === thread) {
      backgroundThread = null
      backgroundHandler = null
    }
  }

  @SuppressLint("MissingPermission")
  private fun openCamera(): Boolean {
    var lockAcquired = false
    try {
      if (cameraManager.cameraIdList.isEmpty()) {
        return failStart(ERROR_NO_CAMERA, "No camera is available")
      }
      val selection = selectCamera()
        ?: return failStart(
          ERROR_FACING_UNAVAILABLE,
          "The requested camera facing is not available",
        )
      cameraCharacteristics = selection.characteristics
      selectedIsFrontCamera = selection.isFrontCamera
      selection.characteristics.get(CameraCharacteristics.LENS_FACING)?.let {
        requestedFacing = it
      }

      val configurationMap =
        selection.characteristics.get(
          CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP
        ) ?: return failStart(
          ERROR_NO_OUTPUT,
          "Camera does not expose a stream configuration map",
        )

      sensorOrientation =
        selection.characteristics.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 0

      val outputSizes = outputSizes(configurationMap)
      if (outputSizes.isEmpty()) {
        return failStart(
          ERROR_NO_OUTPUT,
          "Camera does not expose a compatible ${inputMode.name} output",
        )
      }

      val selectedSize = chooseOptimalSize(outputSizes, targetWidth, targetHeight)
      previewSize = selectedSize

      if (inputMode == InputMode.OES) {
        val hasSurface = synchronized(surfaceLock) {
          ensureOesSurfaceLocked(selectedSize)
        }
        if (!hasSurface) {
          return failStart(ERROR_OES_SURFACE, "OES SurfaceTexture is not ready")
        }
      } else {
        val reader = ImageReader.newInstance(
          selectedSize.width,
          selectedSize.height,
          ImageFormat.YUV_420_888,
          IMAGE_READER_BUFFER_COUNT,
        )
        imageReader = reader
        reader.setOnImageAvailableListener(imageAvailableListener, backgroundHandler)
      }

      configurationCallback?.onCameraConfigured(
        selectedSize.width,
        selectedSize.height,
        sensorOrientation,
        selectedIsFrontCamera,
      )

      if (!openCloseLock.tryAcquire(OPEN_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        return failStart(ERROR_OPEN_TIMEOUT, "Timed out waiting to open the camera")
      }
      lockAcquired = true
      val mayOpen = synchronized(lifecycleLock) {
        if (quiescedForSurfaceRelease) {
          false
        } else {
          closingCamera = false
          openingCamera = true
          true
        }
      }
      if (!mayOpen) {
        openCloseLock.release()
        lockAcquired = false
        closeImageReaderQuietly()
        synchronized(surfaceLock) {
          releaseOesSurfaceLocked(clearTexture = false)
        }
        return false
      }
      frameErrorReported = false

      cameraManager.openCamera(selection.id, stateCallback, backgroundHandler)
      return true
    } catch (exception: CameraAccessException) {
      notifyError(ERROR_CAMERA_ACCESS, "Unable to access the camera", exception)
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      notifyError(ERROR_INTERRUPTED, "Camera open was interrupted", interrupted)
    } catch (exception: SecurityException) {
      notifyError(ERROR_PERMISSION, "Camera permission was revoked", exception)
    } catch (exception: RuntimeException) {
      notifyError(ERROR_CAMERA_OPEN, "Unable to open the camera", exception)
    }

    if (lockAcquired) {
      openingCamera = false
      openCloseLock.release()
    }
    return false
  }

  private fun selectCamera(): CameraSelection? {
    val ids = cameraManager.cameraIdList
    if (ids.isEmpty()) {
      return null
    }

    for (cameraId in ids) {
      val characteristics = cameraManager.getCameraCharacteristics(cameraId)
      val facing = characteristics.get(CameraCharacteristics.LENS_FACING)
      val selection = CameraSelection(
        id = cameraId,
        characteristics = characteristics,
        isFrontCamera = facing == CameraCharacteristics.LENS_FACING_FRONT,
      )
      if (facing == requestedFacing) {
        return selection
      }
    }
    return null
  }

  private fun outputSizes(configurationMap: StreamConfigurationMap): Array<Size> {
    val preferred =
      if (inputMode == InputMode.OES) {
        configurationMap.getOutputSizes(SurfaceTexture::class.java)
      } else {
        configurationMap.getOutputSizes(ImageFormat.YUV_420_888)
      }
    if (!preferred.isNullOrEmpty()) {
      return preferred
    }

    // Some devices omit a YUV list even though their SurfaceTexture path works.
    // This fallback is useful only for OES; YUV must have an actual YUV output.
    return if (inputMode == InputMode.OES) {
      configurationMap.getOutputSizes(SurfaceTexture::class.java) ?: emptyArray()
    } else {
      emptyArray()
    }
  }

  private fun chooseOptimalSize(
    choices: Array<Size>,
    targetWidth: Int,
    targetHeight: Int,
  ): Size {
    val targetLongEdge = max(targetWidth, targetHeight)
    val targetShortEdge = min(targetWidth, targetHeight)
    val targetRatio = targetLongEdge.toDouble() / targetShortEdge.toDouble()
    val fitting = choices.filter { size ->
      max(size.width, size.height) <= targetLongEdge &&
        min(size.width, size.height) <= targetShortEdge
    }

    val candidates = fitting.ifEmpty { choices.toList() }
    return if (fitting.isNotEmpty()) {
      candidates.maxWithOrNull(
        compareBy<Size> { it.width.toLong() * it.height.toLong() }
          .thenBy { -aspectRatioDistance(it, targetRatio) }
      ) ?: choices.first()
    } else {
      candidates.minWithOrNull(
        compareBy<Size> { it.width.toLong() * it.height.toLong() }
          .thenBy { aspectRatioDistance(it, targetRatio) }
      ) ?: choices.first()
    }
  }

  private fun aspectRatioDistance(size: Size, targetRatio: Double): Double {
    val longEdge = max(size.width, size.height).toDouble()
    val shortEdge = min(size.width, size.height).coerceAtLeast(1).toDouble()
    return abs(longEdge / shortEdge - targetRatio)
  }

  private val stateCallback = object : CameraDevice.StateCallback() {
    override fun onOpened(device: CameraDevice) {
      if (closingCamera) {
        cameraClosedLock.drainPermits()
        deviceClosePending = true
        try {
          device.close()
        } catch (error: Throwable) {
          pendingStopFailure = error
          cameraDevice = device
          notifyError(ERROR_CLOSE_TIMEOUT, "Unable to close a late-opened camera", error)
        } finally {
          cameraOpened = false
          releaseOpeningLockIfNeeded()
        }
        return
      }
      cameraDevice = device
      // Publish the device before releasing the open semaphore. Otherwise a
      // concurrent close can acquire the semaphore, observe a null device, and
      // leave this late-opened device alive.
      releaseOpeningLockIfNeeded()
      createCaptureSession()
    }

    override fun onDisconnected(device: CameraDevice) {
      prepareDeviceClose()
      releaseOpeningLockIfNeeded()
      closeAfterDeviceFailure(device)
      notifyError(ERROR_DISCONNECTED, "Camera was disconnected")
    }

    override fun onError(device: CameraDevice, error: Int) {
      prepareDeviceClose()
      releaseOpeningLockIfNeeded()
      closeAfterDeviceFailure(device)
      notifyError(
        ERROR_DEVICE,
        "Camera device error $error (${cameraDeviceErrorName(error)})",
      )
    }

    override fun onClosed(device: CameraDevice) {
      if (cameraDevice === device) {
        cameraDevice = null
      }
      deviceClosePending = false
      closingCamera = false
      cameraClosedLock.release()
      if (stopThreadWhenCameraCloses) {
        stopThreadWhenCameraCloses = false
        stopBackgroundThread()
        completeCameraStop(pendingStopFailure, safeToRestart = true)
      }
    }
  }

  private fun prepareDeviceClose() {
    cameraClosedLock.drainPermits()
    deviceClosePending = true
  }

  private fun releaseOpeningLockIfNeeded() {
    if (openingCamera) {
      openingCamera = false
      openCloseLock.release()
    }
  }

  @Suppress("DEPRECATION")
  private fun createCaptureSession() {
    val device = cameraDevice ?: return
    if (closingCamera) {
      return
    }

    val targets = ArrayList<Surface>(1)
    val target =
      if (inputMode == InputMode.OES) {
        synchronized(surfaceLock) {
          val size = previewSize
          if (size != null) {
            ensureOesSurfaceLocked(size)
          }
          oesSurface
        }
      } else {
        imageReader?.surface
      }

    if (target == null || !target.isValid) {
      cameraOpened = false
      closeCurrentDeviceAfterSessionFailure()
      notifyError(
        if (inputMode == InputMode.OES) ERROR_OES_SURFACE else ERROR_NO_OUTPUT,
        "Camera capture target is not available",
      )
      return
    }

    closeCaptureSessionQuietly()
    val generation = ++captureSessionGeneration

    try {
      val requestBuilder =
        device.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
          addTarget(target)
          configureCaptureRequest(this)
        }
      previewRequestBuilder = requestBuilder
      targets.add(target)

      device.createCaptureSession(
        targets,
        object : CameraCaptureSession.StateCallback() {
          override fun onConfigured(session: CameraCaptureSession) {
            if (
              generation != captureSessionGeneration ||
              closingCamera ||
              cameraDevice !== device
            ) {
              session.close()
              return
            }

            captureSession = session
            try {
              session.setRepeatingRequest(
                requestBuilder.build(),
                captureResultCallback,
                backgroundHandler,
              )
              cameraOpened = true
              readyCallback?.onCameraReady()
            } catch (exception: CameraAccessException) {
              failCaptureSession(
                session,
                ERROR_PREVIEW_START,
                "Unable to start repeating camera capture",
                exception,
              )
            } catch (exception: IllegalStateException) {
              failCaptureSession(
                session,
                ERROR_PREVIEW_START,
                "Camera capture session closed before preview started",
                exception,
              )
            }
          }

          override fun onConfigureFailed(session: CameraCaptureSession) {
            failCaptureSession(
              session,
              ERROR_SESSION_CONFIGURE,
              "Unable to configure the camera capture session",
            )
          }
        },
        backgroundHandler,
      )
    } catch (exception: CameraAccessException) {
      cameraOpened = false
      closeCurrentDeviceAfterSessionFailure()
      notifyError(
        ERROR_SESSION_CONFIGURE,
        "Unable to create the camera capture session",
        exception,
      )
    } catch (exception: IllegalArgumentException) {
      cameraOpened = false
      closeCurrentDeviceAfterSessionFailure()
      notifyError(
        ERROR_SESSION_CONFIGURE,
        "Camera rejected the selected output configuration",
        exception,
      )
    } catch (exception: IllegalStateException) {
      cameraOpened = false
      closeCurrentDeviceAfterSessionFailure()
      notifyError(
        ERROR_SESSION_CONFIGURE,
        "Camera closed while configuring its capture session",
        exception,
      )
    }
  }

  private fun configureCaptureRequest(builder: CaptureRequest.Builder) {
    val characteristics = cameraCharacteristics ?: return

    chooseBestFpsRange(characteristics)?.let { range ->
      builder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, range)
    }

    val availableAeModes =
      characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
        ?: intArrayOf()
    if (availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON)) {
      builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
    }

    val availableAfModes =
      characteristics.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES)
        ?: intArrayOf()
    val afMode = when {
      availableAfModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE) ->
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
      availableAfModes.contains(CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO) ->
        CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO
      availableAfModes.contains(CaptureRequest.CONTROL_AF_MODE_AUTO) ->
        CaptureRequest.CONTROL_AF_MODE_AUTO
      availableAfModes.contains(CaptureRequest.CONTROL_AF_MODE_OFF) ->
        CaptureRequest.CONTROL_AF_MODE_OFF
      else -> null
    }
    if (afMode != null) {
      builder.set(CaptureRequest.CONTROL_AF_MODE, afMode)
    }

    applyLightModes(builder, characteristics)
  }

  private fun characteristicsForRequestedFacing(): CameraCharacteristics? {
    for (cameraId in cameraManager.cameraIdList) {
      val candidate = cameraManager.getCameraCharacteristics(cameraId)
      if (candidate.get(CameraCharacteristics.LENS_FACING) == requestedFacing) {
        return candidate
      }
    }
    return null
  }

  private fun refreshLightModes() {
    val handler = backgroundHandler ?: return
    handler.post {
      val builder = previewRequestBuilder ?: return@post
      val session = captureSession ?: return@post
      val characteristics = cameraCharacteristics ?: return@post
      if (closingCamera || !cameraOpened) return@post
      try {
        applyLightModes(builder, characteristics)
        session.setRepeatingRequest(builder.build(), captureResultCallback, backgroundHandler)
      } catch (exception: CameraAccessException) {
        notifyError(ERROR_CAMERA_ACCESS, "Unable to update flash or torch mode", exception)
      } catch (exception: IllegalStateException) {
        notifyError(ERROR_PREVIEW_START, "Camera closed while updating flash or torch mode", exception)
      } catch (exception: IllegalArgumentException) {
        notifyError(ERROR_PREVIEW_START, "Camera rejected the flash or torch mode", exception)
      }
    }
  }

  private fun applyLightModes(
    builder: CaptureRequest.Builder,
    characteristics: CameraCharacteristics,
  ) {
    val available = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    if (!available) {
      builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
      return
    }

    when {
      torchMode == LIGHT_MODE_ON || photoFlashActive -> {
        builder.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
      }
      else -> {
        val availableAeModes =
          characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES)
            ?: intArrayOf()
        val aeMode = if (
          flashMode == LIGHT_MODE_AUTO &&
          availableAeModes.contains(CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH)
        ) {
          CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
        } else {
          CaptureRequest.CONTROL_AE_MODE_ON
        }
        builder.set(CaptureRequest.CONTROL_AE_MODE, aeMode)
        builder.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
      }
    }
  }

  private val captureResultCallback = object : CameraCaptureSession.CaptureCallback() {
    override fun onCaptureCompleted(
      session: CameraCaptureSession,
      request: CaptureRequest,
      result: TotalCaptureResult,
    ) {
      if (session !== captureSession) return
      if (flashMode == LIGHT_MODE_AUTO && !photoFlashActive) {
        autoFlashRequired =
          result.get(CaptureResult.CONTROL_AE_STATE) ==
            CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
      }
    }
  }

  private fun chooseBestFpsRange(
    characteristics: CameraCharacteristics
  ): Range<Int>? {
    val ranges =
      characteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
        ?: return null
    if (ranges.isEmpty()) {
      return null
    }

    ranges.firstOrNull { it.lower == TARGET_FPS && it.upper == TARGET_FPS }?.let {
      return it
    }

    ranges
      .filter { it.lower <= TARGET_FPS && it.upper >= TARGET_FPS }
      .minWithOrNull(
        compareBy<Range<Int>> { it.upper - it.lower }
          .thenBy { abs(it.upper - TARGET_FPS) }
          .thenByDescending { it.lower }
      )
      ?.let { return it }

    return ranges.maxWithOrNull(
      compareBy<Range<Int>> { it.upper }
        .thenBy { it.lower }
    )
  }

  private fun failCaptureSession(
    session: CameraCaptureSession,
    code: String,
    message: String,
    cause: Throwable? = null,
  ) {
    try {
      session.close()
    } catch (_: Throwable) {
      // Best effort during a camera-service failure.
    }
    if (captureSession === session) {
      captureSession = null
    }
    cameraOpened = false
    closeCurrentDeviceAfterSessionFailure()
    notifyError(code, message, cause)
  }

  private fun closeCurrentDeviceAfterSessionFailure() {
    ++captureSessionGeneration
    closeCaptureSessionQuietly()
    val device = cameraDevice
    if (device != null) {
      cameraClosedLock.drainPermits()
      deviceClosePending = true
    }
    cameraDevice = null
    if (device != null) {
      try {
        device.close()
      } catch (error: Throwable) {
        pendingStopFailure = error
        notifyError(ERROR_CLOSE_TIMEOUT, "Unable to close camera after session failure", error)
      }
    }
    closeImageReaderQuietly()
    synchronized(surfaceLock) {
      releaseOesSurfaceLocked(clearTexture = false)
    }
  }

  private fun closeAfterDeviceFailure(device: CameraDevice) {
    ++captureSessionGeneration
    closeCaptureSessionQuietly()
    cameraClosedLock.drainPermits()
    deviceClosePending = true
    try {
      device.close()
    } catch (error: Throwable) {
      pendingStopFailure = error
      notifyError(ERROR_CLOSE_TIMEOUT, "Unable to close camera after device failure", error)
    }
    if (cameraDevice === device) {
      cameraDevice = null
    }
    closeImageReaderQuietly()
    synchronized(surfaceLock) {
      releaseOesSurfaceLocked(clearTexture = false)
    }
    cameraOpened = false
  }

  /** @return true when the Camera2 worker can be stopped immediately. */
  private fun closeCamera(): Boolean {
    closingCamera = true
    frameCallback = null
    ++captureSessionGeneration
    var lockAcquired = false
    var waitForClosed = false

    try {
      if (!openCloseLock.tryAcquire(OPEN_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
        val failure = IllegalStateException("Timed out waiting to close the camera")
        pendingStopFailure = failure
        notifyError(ERROR_CLOSE_TIMEOUT, failure.message.orEmpty(), failure)
        closeCaptureSessionQuietly()
        closeImageReaderQuietly()
        synchronized(surfaceLock) {
          releaseOesSurfaceLocked(clearTexture = false)
        }
        cameraOpened = false
        // An open request still owns the semaphore. Keep its callback thread
        // alive: onOpened will close the late device, and onClosed will finish
        // shutting the worker down.
        stopThreadWhenCameraCloses = true
        armLateCloseWatchdog()
        completePendingStopIfDeviceAlreadyClosed()
        return false
      }
      lockAcquired = true

      captureSession?.let { session ->
        try {
          session.stopRepeating()
          session.abortCaptures()
        } catch (_: CameraAccessException) {
          // The device may already be closing.
        } catch (_: IllegalStateException) {
          // The session may already be closed.
        }
      }
      closeCaptureSessionQuietly()

      cameraDevice?.let { device ->
        cameraClosedLock.drainPermits()
        deviceClosePending = true
        try {
          device.close()
          waitForClosed = true
        } catch (error: Throwable) {
          pendingStopFailure = error
          waitForClosed = true
          notifyError(ERROR_CLOSE_TIMEOUT, "CameraDevice.close failed", error)
        }
      }
      if (deviceClosePending) {
        waitForClosed = true
      }
      cameraDevice = null

      closeImageReaderQuietly()
      synchronized(surfaceLock) {
        releaseOesSurfaceLocked(clearTexture = false)
      }
      cameraOpened = false
      openingCamera = false
    } catch (interrupted: InterruptedException) {
      Thread.currentThread().interrupt()
      pendingStopFailure = interrupted
      notifyError(ERROR_INTERRUPTED, "Camera close was interrupted", interrupted)
      stopThreadWhenCameraCloses = true
      armLateCloseWatchdog()
      completePendingStopIfDeviceAlreadyClosed()
      return false
    } finally {
      if (lockAcquired) {
        openCloseLock.release()
      }
    }

    if (waitForClosed && Looper.myLooper() != backgroundThread?.looper) {
      try {
        if (!cameraClosedLock.tryAcquire(DEVICE_CLOSE_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
          val failure = IllegalStateException("Timed out waiting for CameraDevice.onClosed")
          pendingStopFailure = failure
          notifyError(ERROR_CLOSE_TIMEOUT, failure.message.orEmpty(), failure)
          stopThreadWhenCameraCloses = true
          armLateCloseWatchdog()
          completePendingStopIfDeviceAlreadyClosed()
          return false
        }
      } catch (interrupted: InterruptedException) {
        Thread.currentThread().interrupt()
        pendingStopFailure = interrupted
        notifyError(ERROR_INTERRUPTED, "Waiting for camera close was interrupted", interrupted)
        stopThreadWhenCameraCloses = true
        armLateCloseWatchdog()
        completePendingStopIfDeviceAlreadyClosed()
        return false
      }
    }
    closingCamera = false
    return true
  }

  private fun completeCameraStop(failure: Throwable?, safeToRestart: Boolean) {
    val callbacks = synchronized(stopCompletionLock) {
      if (!stopInProgress) {
        emptyList()
      } else if (!safeToRestart) {
        if (stopTimeoutDelivered) {
          emptyList()
        } else {
          stopTimeoutDelivered = true
          stopCompletionCallbacks.toList().also { stopCompletionCallbacks.clear() }
        }
      } else {
        stopInProgress = false
        stopTimeoutDelivered = false
        ++lateCloseWatchdogGeneration
        stopCompletionCallbacks.toList().also { stopCompletionCallbacks.clear() }
      }
    }
    callbacks.forEach { callback ->
      try {
        callback.onCameraStopped(failure, safeToRestart)
      } catch (callbackFailure: Throwable) {
        Log.e(TAG, "Camera stop callback failed", callbackFailure)
      }
    }
  }

  private fun armLateCloseWatchdog() {
    val generation = synchronized(stopCompletionLock) {
      if (!stopInProgress) return
      ++lateCloseWatchdogGeneration
    }
    stopWatchdogHandler.postDelayed({
      val shouldFail = synchronized(stopCompletionLock) {
        stopInProgress && generation == lateCloseWatchdogGeneration
      }
      if (shouldFail) {
        val failure = pendingStopFailure ?: IllegalStateException(
          "Camera close did not reach a physical onClosed callback in time"
        ).also { pendingStopFailure = it }
        completeCameraStop(failure, safeToRestart = false)
      }
    }, LATE_CLOSE_WATCHDOG_MS)
  }

  private fun completePendingStopIfDeviceAlreadyClosed() {
    if (!cameraClosedLock.tryAcquire()) return
    stopThreadWhenCameraCloses = false
    closingCamera = false
    stopBackgroundThread()
    completeCameraStop(pendingStopFailure, safeToRestart = true)
  }

  private fun closeCaptureSessionQuietly() {
    val session = captureSession
    captureSession = null
    previewRequestBuilder = null
    photoFlashActive = false
    autoFlashRequired = false
    if (session != null) {
      try {
        session.close()
      } catch (_: Throwable) {
        // Best effort during teardown.
      }
    }
  }

  private fun closeImageReaderQuietly() {
    synchronized(frameProcessingLock) {
      closeImageReaderQuietlyLocked()
    }
  }

  private fun closeImageReaderQuietlyLocked() {
    val reader = imageReader
    imageReader = null
    if (reader != null) {
      try {
        reader.setOnImageAvailableListener(null, null)
        reader.close()
      } catch (_: Throwable) {
        // Best effort during teardown.
      }
    }
  }

  private fun ensureOesSurfaceLocked(size: Size): Boolean {
    val texture = oesSurfaceTexture ?: return false
    if (oesSurface == null) {
      oesSurface = Surface(texture)
    }
    texture.setDefaultBufferSize(size.width, size.height)
    return oesSurface?.isValid == true
  }

  private fun replaceOesSurfaceLocked(surfaceTexture: SurfaceTexture?) {
    if (oesSurfaceTexture === surfaceTexture && oesSurface != null) {
      previewSize?.let { size ->
        surfaceTexture?.setDefaultBufferSize(size.width, size.height)
      }
      return
    }
    releaseOesSurfaceLocked(clearTexture = true)
    oesSurfaceTexture = surfaceTexture
    if (surfaceTexture != null) {
      previewSize?.let { size ->
        surfaceTexture.setDefaultBufferSize(size.width, size.height)
      }
      oesSurface = Surface(surfaceTexture)
    }
  }

  private fun releaseOesSurfaceLocked(clearTexture: Boolean) {
    try {
      oesSurface?.release()
    } catch (_: Throwable) {
      // Best effort during teardown.
    }
    oesSurface = null
    if (clearTexture) {
      oesSurfaceTexture = null
    }
  }

  private val imageAvailableListener = ImageReader.OnImageAvailableListener { reader ->
    synchronized(frameProcessingLock) {
      if (closingCamera || frameCallback == null) {
        return@OnImageAvailableListener
      }
      val image = try {
        reader.acquireLatestImage()
      } catch (exception: IllegalStateException) {
        reportFrameErrorOnce("Unable to acquire the latest camera frame", exception)
        null
      } ?: return@OnImageAvailableListener

      try {
        val callback = frameCallback ?: return@OnImageAvailableListener
        val planes = image.planes
        if (planes.size < 3) {
          reportFrameErrorOnce("Camera returned fewer than three YUV planes")
          return@OnImageAvailableListener
        }

        callback.onFrameAvailable(
          planes[0].buffer,
          planes[1].buffer,
          planes[2].buffer,
          image.width,
          image.height,
          planes[0].rowStride,
          planes[1].rowStride,
          planes[2].rowStride,
          planes[1].pixelStride,
          planes[2].pixelStride,
        )
      } catch (exception: Throwable) {
        reportFrameErrorOnce("Camera frame callback failed", exception)
      } finally {
        image.close()
      }
    }
  }

  private fun reportFrameErrorOnce(message: String, cause: Throwable? = null) {
    if (frameErrorReported) {
      return
    }
    frameErrorReported = true
    notifyError(ERROR_FRAME, message, cause)
  }

  private fun failStart(code: String, message: String): Boolean {
    notifyError(code, message)
    return false
  }

  private fun notifyError(code: String, message: String, cause: Throwable? = null) {
    Log.e(TAG, "$code: $message", cause)
    try {
      errorCallback?.onCameraError(code, message, cause)
    } catch (callbackFailure: Throwable) {
      Log.e(TAG, "Camera error callback failed", callbackFailure)
    }
  }

  private fun cameraDeviceErrorName(error: Int): String = when (error) {
    CameraDevice.StateCallback.ERROR_CAMERA_IN_USE -> "camera-in-use"
    CameraDevice.StateCallback.ERROR_MAX_CAMERAS_IN_USE -> "max-cameras-in-use"
    CameraDevice.StateCallback.ERROR_CAMERA_DISABLED -> "camera-disabled"
    CameraDevice.StateCallback.ERROR_CAMERA_DEVICE -> "camera-device"
    CameraDevice.StateCallback.ERROR_CAMERA_SERVICE -> "camera-service"
    else -> "unknown"
  }

  companion object {
    private const val LIGHT_MODE_OFF = "off"
    private const val LIGHT_MODE_ON = "on"
    private const val LIGHT_MODE_AUTO = "auto"
    private val SUPPORTED_LIGHT_MODES = setOf(
      LIGHT_MODE_OFF,
      LIGHT_MODE_ON,
      LIGHT_MODE_AUTO,
    )
    private val SUPPORTED_TORCH_MODES = setOf(LIGHT_MODE_OFF, LIGHT_MODE_ON)
    private const val PHOTO_FLASH_WARMUP_MS = 180L
    private const val TAG = "NosmaiCamera2Helper"
    private const val THREAD_NAME = "NosmaiCamera2"
    private const val DEFAULT_TARGET_WIDTH = 1280
    private const val DEFAULT_TARGET_HEIGHT = 720
    private const val TARGET_FPS = 30
    private const val IMAGE_READER_BUFFER_COUNT = 3
    private const val OPEN_CLOSE_TIMEOUT_MS = 2_500L
    private const val DEVICE_CLOSE_TIMEOUT_MS = 1_000L
    private const val THREAD_JOIN_TIMEOUT_MS = 1_500L
    private const val THREAD_FORCE_JOIN_TIMEOUT_MS = 250L
    private const val LATE_CLOSE_WATCHDOG_MS = 5_000L

    internal const val ERROR_PERMISSION = "E_CAMERA_PERMISSION"
    internal const val ERROR_NO_CAMERA = "E_CAMERA_UNAVAILABLE"
    internal const val ERROR_FACING_UNAVAILABLE = "E_CAMERA_FACING_UNAVAILABLE"
    internal const val ERROR_NO_OUTPUT = "E_CAMERA_OUTPUT_UNAVAILABLE"
    internal const val ERROR_OES_SURFACE = "E_CAMERA_OES_SURFACE"
    internal const val ERROR_CAMERA_ACCESS = "E_CAMERA_ACCESS"
    internal const val ERROR_CAMERA_OPEN = "E_CAMERA_OPEN"
    internal const val ERROR_OPEN_TIMEOUT = "E_CAMERA_OPEN_TIMEOUT"
    internal const val ERROR_CLOSE_TIMEOUT = "E_CAMERA_CLOSE_TIMEOUT"
    internal const val ERROR_DISCONNECTED = "E_CAMERA_DISCONNECTED"
    internal const val ERROR_DEVICE = "E_CAMERA_DEVICE"
    internal const val ERROR_SESSION_CONFIGURE = "E_CAMERA_CONFIGURE"
    internal const val ERROR_PREVIEW_START = "E_CAMERA_PREVIEW_START"
    internal const val ERROR_FRAME = "E_CAMERA_FRAME"
    internal const val ERROR_INTERRUPTED = "E_CAMERA_INTERRUPTED"
  }
}
