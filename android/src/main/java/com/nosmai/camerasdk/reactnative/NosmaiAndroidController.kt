package com.nosmai.camerasdk.reactnative

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.SurfaceTexture
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.LifecycleEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap
import com.facebook.react.common.LifecycleState
import com.nosmai.effect.NosmaiEffects
import com.nosmai.effect.NosmaiEffectsEngine
import com.nosmai.effect.NosmaiFilterInfo
import com.nosmai.effect.NosmaiGameEvent
import com.nosmai.effect.NosmaiPipelineState
import com.nosmai.effect.api.NosmaiPreviewView
import com.nosmai.effect.api.NosmaiSDK
import java.io.File
import java.lang.ref.WeakReference
import java.security.MessageDigest
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger

private data class NosmaiEffectOperationResult(
  val value: Any? = null,
  val code: String? = null,
  val message: String? = null,
  val cause: Throwable? = null
)

private typealias NosmaiEffectOperationCompletion =
  (NosmaiEffectOperationResult) -> Unit
private typealias NosmaiEffectOperation =
  (NosmaiEffectOperationCompletion) -> Unit

private data class NosmaiPackageSource(
  val sdkPath: String,
  val file: File? = null
)

private enum class NosmaiVisualCapability {
  NONE,
  BEAUTY,
  ADVANCED
}

internal interface NosmaiModuleEventSink {
  val nosmaiReactContext: ReactApplicationContext
  val nosmaiOwnerValid: Boolean
  fun onActiveEffectsChanged(state: WritableMap)
  fun onLicenseStatusChanged(status: String)
  fun onNativeError(error: WritableMap)
  fun onRecordingProgress(progress: WritableMap)
  fun onDownloadProgress(progress: WritableMap)
  fun onGameEvent(event: WritableMap)
  fun onFrameAvailable(metadata: WritableMap)
}

/**
 * Process-wide owner of Android SDK, preview, and Camera2 state. React contexts
 * are adopted through generation-bound lifecycle listeners so a stale bridge
 * cannot tear down its replacement. All state transitions happen on the main
 * looper; potentially blocking camera work is serialized on [cameraExecutor].
 */
internal class NosmaiAndroidController(
  initialReactContext: ReactApplicationContext
) {
  @Volatile private var reactContext: ReactApplicationContext? = initialReactContext
  private var pendingReactContextAfterCleanup: ReactApplicationContext? = null
  private val mainHandler = Handler(Looper.getMainLooper())
  private val cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor(
    ThreadFactory { runnable ->
      Thread(runnable, "NosmaiCameraControl-${THREAD_COUNTER.incrementAndGet()}").apply {
        isDaemon = true
      }
    }
  )
  private val licenseAdapter = NosmaiLicenseStatusAdapter()
  private val effectMutationAdapter = NosmaiEffectMutationAdapter()
  private val visualEffects = NosmaiAndroidVisualEffects(mainHandler)
  private val cloudFilters = NosmaiAndroidCloud(
    initialReactContext.applicationContext,
    mainHandler,
    NosmaiAndroidCloudDelegate(::emitCloudDownloadProgress)
  )
  private val mediaCapture = NosmaiAndroidCapture(
    initialReactContext.applicationContext,
    mainHandler
  )
  private val mediaGallery = NosmaiAndroidGallery(
    initialReactContext.applicationContext,
    mainHandler
  )
  private val mediaRecorder = NosmaiAndroidRecorder(
    initialReactContext.applicationContext,
    mainHandler,
    ::emitRecordingProgress
  )
  private val frameStream = NosmaiAndroidFrameStream(
    mainHandler,
    NosmaiAndroidFrameMetadataDelegate(::emitFrameAvailable),
    ::emitError
  )

  private var moduleSink: NosmaiModuleEventSink? = null
  private var pendingModuleSinkAfterCleanup: NosmaiModuleEventSink? = null
  @Volatile private var activeView: NosmaiCameraView? = null
  private var pendingViewAfterCleanup: NosmaiCameraView? = null
  private var previewView: NosmaiPreviewView? = null
  private var retiredPreviewPendingTeardown: NosmaiPreviewView? = null
  @Volatile private var cameraHelper: NosmaiCamera2Helper? = null
  @Volatile private var cameraCloseBarrierHelper: NosmaiCamera2Helper? = null

  private var sdkInitialized = false
  private var gameEventListenerRegistered = false
  private val gameEventListener = NosmaiEffects.GameEventListener { event ->
    runOnMain { emitGameEvent(event) }
  }
  private var pipelineListenerRegistered = false
  private var pipelineListenerGeneration = 0L
  private var pipelineStateListener: NosmaiEffectsEngine.PipelineStateListener? = null
  @Volatile private var processing = false
  private var cameraRunning = false
  private var cameraStarting = false
  private var cameraStopping = false
  private val cameraStopWaiters = mutableListOf<() -> Unit>()
  private val cameraAttemptsAwaitingStop = mutableListOf<CameraStartAttempt>()
  private val pendingCameraStartCallbacks = mutableListOf<(Boolean) -> Unit>()
  private var cameraPaused = false
  private var switchingCamera = false
  private var hostPaused = initialReactContext.lifecycleState != LifecycleState.RESUMED
  private var resumeAfterHost = false
  private var resumeAfterViewReplacement = false
  private var resumeReplacementCameraPaused = false
  private var captureInProgress = false
  private var pendingViewConfigurationChange = false
  private var recordingTeardownInProgress = false
  private var recordingTeardownShouldEmit = false
  private var recordingTeardownReason = "Recording was interrupted by camera teardown"
  private val recordingTeardownCallbacks = mutableListOf<() -> Unit>()
  private var pendingViewAfterRecording: NosmaiCameraView? = null
  private var recordingViewReplacementScheduled = false
  private var recordingViewDetachPending: NosmaiCameraView? = null
  @Volatile private var destroyed = false
  private var processingStopping = false
  private val pendingProcessingStarts = mutableListOf<Promise?>()
  private val processingStopPromises = mutableListOf<Promise>()
  private var cleanupInProgress = false
  private var contextRetirementPending = false
  private var teardownPoisoned = false
  private val cleanupPromises = mutableListOf<Promise>()
  private var initializedKeyDigest: String? = null
  private val effectOperations = ArrayDeque<() -> Unit>()
  private val effectDrainCallbacks = mutableListOf<() -> Unit>()
  private var effectOperationActive = false
  private var effectPipelinePoisoned = false
  private var pendingInitialization: PendingInitialization? = null

  private var desiredFrontCamera = true
  private var desiredMirror = false
  private var desiredFlashMode = LIGHT_MODE_OFF
  private var desiredTorchMode = LIGHT_MODE_OFF
  private var targetWidth = DEFAULT_WIDTH
  private var targetHeight = DEFAULT_HEIGHT

  private var useOesInput = false
  private var oesSurfaceTexture: SurfaceTexture? = null
  private var pendingCameraStart = false
  private var firstFrameDelivered = false
  private var lastSwitchAtMs = 0L

  private var viewGeneration = 0L
  @Volatile private var cameraGeneration = 0L
  private var licenseGeneration = 0L
  private var oesWatchdogGeneration = 0L
  private var sessionGeneration = 0L
  private var contextGeneration = 1L
  private var transitionGeneration = 0L
  private var cameraConfigurationGeneration = 0L
  private var lifecycleListener: LifecycleEventListener? = null
  private val retiredReactContexts = mutableListOf<WeakReference<ReactApplicationContext>>()

  private data class CameraFailure(
    val code: String,
    val message: String,
    val cause: Throwable?
  )

  private class PendingInitialization(
    val owner: NosmaiModuleEventSink,
    val licenseKey: String,
    val licenseDigest: String,
    firstPromise: Promise
  ) {
    val promises = mutableListOf(firstPromise)
  }

  private class CameraStartAttempt(
    val generation: Long,
    val startedWithOes: Boolean,
    callback: ((Boolean) -> Unit)?
  ) {
    private val callbacks = mutableListOf<(Boolean) -> Unit>()
    var resultKnown = false
    var settled = false
    var pendingFailure: CameraFailure? = null
    var readyBeforeResult = false
    private var settlementResult: Boolean? = null

    init {
      callback?.let(callbacks::add)
    }

    fun addCallback(callback: ((Boolean) -> Unit)?) {
      if (callback == null) return
      val result = settlementResult
      if (result != null) {
        runCatching { callback(result) }
      } else {
        callbacks += callback
      }
    }

    fun settle(accepted: Boolean) {
      if (settled) return
      settled = true
      settlementResult = accepted
      val waiting = callbacks.toList()
      callbacks.clear()
      waiting.forEach { callback -> runCatching { callback(accepted) } }
    }

    fun hasCaller(): Boolean = callbacks.isNotEmpty()

    fun transferCallbacks(): List<(Boolean) -> Unit> {
      if (settled) return emptyList()
      settled = true
      settlementResult = false
      return callbacks.toList().also { callbacks.clear() }
    }
  }

  private class YuvPipelineStartAttempt(
    val view: NosmaiCameraView,
    val preview: NosmaiPreviewView,
    val token: Long,
    val session: Long,
    callback: ((Boolean) -> Unit)?
  ) {
    private val callbacks = mutableListOf<(Boolean) -> Unit>()
    private var result: Boolean? = null

    init {
      callback?.let(callbacks::add)
    }

    fun matches(
      candidateView: NosmaiCameraView,
      candidatePreview: NosmaiPreviewView,
      candidateToken: Long,
      candidateSession: Long
    ): Boolean =
      result == null &&
        view === candidateView &&
        preview === candidatePreview &&
        token == candidateToken &&
        session == candidateSession

    fun addCallback(callback: ((Boolean) -> Unit)?) {
      if (callback == null) return
      val settledResult = result
      if (settledResult == null) {
        callbacks += callback
      } else {
        runCatching { callback(settledResult) }
      }
    }

    fun settle(accepted: Boolean) {
      if (result != null) return
      result = accepted
      val waiting = callbacks.toList()
      callbacks.clear()
      waiting.forEach { callback -> runCatching { callback(accepted) } }
    }
  }

  private var cameraStartAttempt: CameraStartAttempt? = null
  private var yuvPipelineStartAttempt: YuvPipelineStartAttempt? = null
  private var yuvPipelineWaitGeneration = 0L
  private var cameraStartSettlementFailure: CameraFailure? = null
  private var cameraStopSettlementFailure: Throwable? = null

  init {
    lifecycleListener = createLifecycleListener(contextGeneration).also {
      initialReactContext.addLifecycleEventListener(it)
    }
  }

  fun adoptReactContext(context: ReactApplicationContext) {
    runOnMain {
      if (
        destroyed ||
        isReactContextRetired(context) ||
        (!contextRetirementPending && reactContext === context) ||
        (contextRetirementPending && pendingReactContextAfterCleanup === context)
      ) {
        return@runOnMain
      }
      beginContextRetirement(context)
    }
  }

  fun attachModule(sink: NosmaiModuleEventSink) {
    runOnMain {
      if (
        destroyed ||
        !sink.nosmaiOwnerValid ||
        isReactContextRetired(sink.nosmaiReactContext)
      ) {
        return@runOnMain
      }
      val ownerContext = sink.nosmaiReactContext
      when {
        contextRetirementPending && pendingReactContextAfterCleanup === ownerContext -> {
          if (pendingModuleSinkAfterCleanup !== sink) {
            pendingInitialization
              ?.takeIf { it.owner !== sink }
              ?.let {
                cancelPendingInitialization(
                  ERROR_OPERATION_CANCELLED,
                  "The pending React Native module was replaced"
                )
              }
          }
          pendingModuleSinkAfterCleanup = sink
        }
        !contextRetirementPending && reactContext === ownerContext -> {
          moduleSink = sink
        }
      }
    }
  }

  fun detachModule(sink: NosmaiModuleEventSink) {
    runOnMain {
      if (moduleSink === sink) moduleSink = null
      if (pendingModuleSinkAfterCleanup === sink) {
        pendingModuleSinkAfterCleanup = null
        if (pendingInitialization?.owner === sink) {
          cancelPendingInitialization(
            ERROR_OPERATION_CANCELLED,
            "The pending React Native module was invalidated"
          )
        }
      }
    }
  }

  fun attachView(view: NosmaiCameraView) {
    runOnMain {
      if (destroyed) {
        view.dispatchCameraError(ERROR_DESTROYED, "The native camera session is destroyed")
        return@runOnMain
      }
      if (!viewBelongsToCurrentOrPendingContext(view)) {
        view.controllerToken = 0L
        view.dispatchCameraError(
          ERROR_OPERATION_CANCELLED,
          "The camera view belongs to a retired React Native context"
        )
        return@runOnMain
      }
      if (cleanupInProgress || contextRetirementPending) {
        if (activeView === view || pendingViewAfterCleanup === view) {
          view.showTransitionOverlay()
          return@runOnMain
        }
        pendingViewAfterCleanup?.controllerToken = 0L
        val token = ++viewGeneration
        view.controllerToken = token
        pendingViewAfterCleanup = view
        desiredFrontCamera = view.cameraPosition != "back"
        desiredMirror = view.mirrorPreview
        view.showTransitionOverlay()
        return@runOnMain
      }
      if (effectPipelinePoisoned || teardownPoisoned) {
        if (activeView === view || pendingViewAfterCleanup === view) {
          view.showTransitionOverlay()
          return@runOnMain
        }
        pendingViewAfterCleanup?.controllerToken = 0L
        val token = ++viewGeneration
        view.controllerToken = token
        pendingViewAfterCleanup = view
        desiredFrontCamera = view.cameraPosition != "back"
        desiredMirror = view.mirrorPreview
        view.showTransitionOverlay()
        return@runOnMain
      }

      val oldView = activeView
      val wasProcessing = processing
      val wasCameraPaused = cameraPaused
      if (oldView != null && oldView !== view) {
        if (mediaRecorder.isBusy()) {
          pendingViewAfterRecording
            ?.takeIf { it !== view }
            ?.let { staleView -> staleView.controllerToken = 0L }
          pendingViewAfterRecording = view
          view.showTransitionOverlay()
          if (!recordingViewReplacementScheduled) {
            recordingViewReplacementScheduled = true
            stopRecordingForTeardown("Recording was interrupted by preview replacement") {
              recordingViewReplacementScheduled = false
              val replacement = pendingViewAfterRecording
              pendingViewAfterRecording = null
              replacement?.let(::attachView)
            }
          }
          return@runOnMain
        }
        frameStream.stop()
        val transition = ++transitionGeneration
        ++cameraConfigurationGeneration
        oldView.controllerToken = 0L
        runCatching { cameraHelper?.quiesceForSurfaceRelease() }
        val retiredPreview = previewView ?: retiredPreviewPendingTeardown
        retainPreviewForTeardown(retiredPreview)
        if (wasProcessing) {
          resumeAfterViewReplacement = true
          resumeReplacementCameraPaused = wasCameraPaused
        }
        processing = false
        processingStopping = true
        pendingCameraStart = false
        cancelYuvPipelineStart("The camera preview was replaced")
        cancelPendingCameraStartCallbacks()
        previewView = null

        val token = ++viewGeneration
        view.controllerToken = token
        activeView = view
        val replacementFront = view.cameraPosition != "back"
        if (desiredFrontCamera != replacementFront) resetCameraLightModes()
        desiredFrontCamera = replacementFront
        desiredMirror = view.mirrorPreview
        stopCameraAsync {
          if (transition != transitionGeneration) return@stopCameraAsync
          var failure = cameraStopSettlementFailure
          runCatching { retiredPreview?.setOnOesFrameProcessedListener(null) }
            .exceptionOrNull()?.let { if (failure == null) failure = it }
          runCatching { retiredPreview?.onPause() }
            .exceptionOrNull()?.let { if (failure == null) failure = it }
          runCatching { NosmaiSDK.stopProcessing() }
            .exceptionOrNull()?.let { if (failure == null) failure = it }
          if (failure != null) {
            teardownPoisoned = true
            retainPreviewForTeardown(retiredPreview)
          } else if (!effectPipelinePoisoned && !teardownPoisoned) {
            releaseRetiredPreview(retiredPreview)
          }
          val shouldResume = resumeAfterViewReplacement
          val shouldKeepCameraPaused = resumeReplacementCameraPaused
          resumeAfterViewReplacement = false
          resumeReplacementCameraPaused = false
          if (
            failure == null &&
            isActiveView(view, token) &&
            sdkInitialized &&
            !effectPipelinePoisoned &&
            !teardownPoisoned &&
            retiredPreviewPendingTeardown == null
          ) {
            runCatching { createAndMountPreview(view, token) }
              .exceptionOrNull()?.let { error ->
                failure = error
                teardownPoisoned = true
                retainPreviewForTeardown(retiredPreview)
                previewView = null
                runCatching { view.removeAllViews() }
                emitCameraError(
                  ERROR_NO_PREVIEW,
                  error.message ?: "Unable to mount the replacement camera preview",
                  error
                )
              }
          } else if (isActiveView(view, token)) {
            view.showTransitionOverlay()
          }
          completeProcessingStop(failure, drainQueuedStarts = false)
          if (
            failure == null &&
            shouldResume &&
            sdkInitialized &&
            isActiveView(view, token) &&
            !effectPipelinePoisoned &&
            !teardownPoisoned &&
            previewView != null
          ) {
            startProcessingInternal(null, startCameraNow = !shouldKeepCameraPaused)
          }
          if (failure == null) {
            drainPendingProcessingStarts()
          } else {
            cancelPendingProcessingStarts(
              ERROR_OPERATION_CANCELLED,
              "Replacement preview setup failed"
            )
          }
        }
        return@runOnMain
      }

      val token = ++viewGeneration
      view.controllerToken = token
      activeView = view
      val mountedFront = view.cameraPosition != "back"
      if (desiredFrontCamera != mountedFront) resetCameraLightModes()
      desiredFrontCamera = mountedFront
      desiredMirror = view.mirrorPreview
      if (sdkInitialized) {
        if (retiredPreviewPendingTeardown != null || processingStopping) {
          view.showTransitionOverlay()
        } else {
          runCatching { createAndMountPreview(view, token) }.onFailure { error ->
            previewView = null
            runCatching { view.removeAllViews() }
            emitCameraError(
              ERROR_NO_PREVIEW,
              error.message ?: "Unable to mount the camera preview",
              error
            )
          }
        }
      }
    }
  }

  fun detachView(view: NosmaiCameraView) {
    runOnMain {
      if (pendingViewAfterRecording === view) {
        pendingViewAfterRecording = null
        view.controllerToken = 0L
        return@runOnMain
      }
      if (pendingViewAfterCleanup === view) {
        pendingViewAfterCleanup = null
        view.controllerToken = 0L
        activeView?.let { survivingView ->
          desiredFrontCamera = survivingView.cameraPosition != "back"
          desiredMirror = survivingView.mirrorPreview
        }
        return@runOnMain
      }
      if (cleanupInProgress) {
        when {
          activeView === view -> {
            runCatching { cameraHelper?.quiesceForSurfaceRelease() }
            ++viewGeneration
            invalidateOesWatchdog()
            retainPreviewForTeardown(previewView)
            activeView = null
            previewView = null
            view.controllerToken = 0L
          }
        }
        return@runOnMain
      }
      if (!isActiveView(view, view.controllerToken)) return@runOnMain
      if (mediaRecorder.isBusy()) {
        if (recordingViewDetachPending !== view) {
          recordingViewDetachPending = view
          val token = view.controllerToken
          stopRecordingForTeardown("Recording was interrupted because the preview unmounted") {
            if (recordingViewDetachPending === view) {
              recordingViewDetachPending = null
              if (isActiveView(view, token)) detachView(view)
            }
          }
        }
        return@runOnMain
      }

      frameStream.stop()

      val transition = ++transitionGeneration
      ++cameraConfigurationGeneration
      val wasProcessing = processing
      val wasCameraPaused = cameraPaused
      view.controllerToken = 0L
      runCatching { cameraHelper?.quiesceForSurfaceRelease() }
      ++viewGeneration
      val retiredPreview = previewView ?: retiredPreviewPendingTeardown
      retainPreviewForTeardown(retiredPreview)
      activeView = null
      previewView = null
      processing = false
      processingStopping = true
      if (wasProcessing) {
        resumeAfterViewReplacement = true
        resumeReplacementCameraPaused = wasCameraPaused
      }
      cameraPaused = false
      pendingCameraStart = false
      cancelYuvPipelineStart("The camera preview was unmounted")
      cancelPendingCameraStartCallbacks()
      invalidateOesWatchdog()
      stopCameraAsync {
        // Camera stop can complete synchronously while paused. Give Fabric one
        // main-loop turn to attach the replacement view before deciding that
        // the prior processing intent should be discarded.
        mainHandler.post finalizeDetach@{
          if (transition != transitionGeneration) return@finalizeDetach
          var failure = cameraStopSettlementFailure
          runCatching { retiredPreview?.setOnOesFrameProcessedListener(null) }
            .exceptionOrNull()?.let { if (failure == null) failure = it }
          runCatching { retiredPreview?.onPause() }
            .exceptionOrNull()?.let { if (failure == null) failure = it }
          runCatching { NosmaiSDK.stopProcessing() }
            .exceptionOrNull()?.let { if (failure == null) failure = it }
          if (failure != null) {
            teardownPoisoned = true
            retainPreviewForTeardown(retiredPreview)
          } else if (!effectPipelinePoisoned && !teardownPoisoned) {
            releaseRetiredPreview(retiredPreview)
          }

          val replacement = activeView
          if (
            failure == null &&
            replacement != null &&
            previewView == null &&
            sdkInitialized &&
            !effectPipelinePoisoned &&
            !teardownPoisoned &&
            retiredPreviewPendingTeardown == null
          ) {
            val replacementToken = replacement.controllerToken
            if (isActiveView(replacement, replacementToken)) {
              runCatching { createAndMountPreview(replacement, replacementToken) }
                .exceptionOrNull()?.let { error ->
                  failure = error
                  teardownPoisoned = true
                  retainPreviewForTeardown(retiredPreview)
                  previewView = null
                  runCatching { replacement.removeAllViews() }
                  emitCameraError(
                    ERROR_NO_PREVIEW,
                    error.message ?: "Unable to mount the replacement camera preview",
                    error
                  )
                }
            }
          }
          val hasReplacement = activeView != null
          val shouldResume = resumeAfterViewReplacement
          val shouldKeepCameraPaused = resumeReplacementCameraPaused
          resumeAfterViewReplacement = false
          resumeReplacementCameraPaused = false
          if (!hasReplacement) {
            cancelPendingProcessingStarts(
              ERROR_OPERATION_CANCELLED,
              "The camera preview was unmounted"
            )
          }
          completeProcessingStop(failure, drainQueuedStarts = false)
          if (
            failure == null &&
            shouldResume &&
            sdkInitialized &&
            replacement != null &&
            isActiveView(replacement, replacement.controllerToken) &&
            !effectPipelinePoisoned &&
            !teardownPoisoned &&
            previewView != null
          ) {
            startProcessingInternal(null, startCameraNow = !shouldKeepCameraPaused)
          }
          if (failure == null && hasReplacement) {
            drainPendingProcessingStarts()
          } else if (failure != null) {
            cancelPendingProcessingStarts(
              ERROR_OPERATION_CANCELLED,
              "Replacement preview setup failed"
            )
          }
        }
      }
    }
  }

  fun onViewConfigurationChanged(view: NosmaiCameraView) {
    runOnMain {
      if (!isActiveView(view, view.controllerToken)) return@runOnMain
      if (mediaRecorder.isBusy() || captureInProgress) {
        pendingViewConfigurationChange = true
        val recordingBusy = mediaRecorder.isBusy()
        emitError(
          if (recordingBusy) {
            NosmaiMediaErrorCode.RECORDING_IN_PROGRESS
          } else {
            NosmaiMediaErrorCode.CAPTURE_IN_PROGRESS
          },
          if (recordingBusy) {
            "Camera presentation changes are deferred until recording stops"
          } else {
            "Camera presentation changes are deferred until photo capture completes"
          }
        )
        return@runOnMain
      }
      applyViewConfiguration(view)
    }
  }

  private fun applyViewConfiguration(view: NosmaiCameraView) {
    if (!isActiveView(view, view.controllerToken)) return
    pendingViewConfigurationChange = false
    ++cameraConfigurationGeneration

    val newFront = view.cameraPosition != "back"
    val facingChanged = desiredFrontCamera != newFront
    if (facingChanged) {
      frameStream.stop()
      resetCameraLightModes()
    }
    desiredFrontCamera = newFront
    desiredMirror = view.mirrorPreview
    applyCameraPresentation()

    if (facingChanged && processing && !cameraPaused && !hostPaused) {
      view.showTransitionOverlay()
      restartCamera()
    }
  }

  fun initializeSdk(
    owner: NosmaiModuleEventSink,
    licenseKey: String,
    promise: Promise
  ) {
    runOnMain {
      if (queueInitializationDuringContextRetirement(owner, licenseKey, promise)) {
        return@runOnMain
      }
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureAvailable(promise)) return@runOnMain
      val context = reactContext
      if (context == null) {
        reject(promise, ERROR_DESTROYED, "No active React application context is available")
        return@runOnMain
      }

      try {
        val requestedDigest = digestLicenseKey(licenseKey)
        val nativeKey: String? = runCatching { NosmaiSDK.getLicenseKey() }.getOrNull()
        val nativeDigest = nativeKey
          ?.takeIf { it.isNotBlank() }
          ?.let { digestLicenseKey(it) }
        val activeDigest = initializedKeyDigest ?: nativeDigest
        if (NosmaiSDK.isInitialized() && activeDigest != null && activeDigest != requestedDigest) {
          reject(
            promise,
            ERROR_LICENSE_KEY_MISMATCH,
            "This process was already initialized with a different license key"
          )
          return@runOnMain
        }

        val generation = ++licenseGeneration
        emitLicenseStatus(NosmaiLicenseStatusAdapter.UNVERIFIED)
        runCatching {
          licenseAdapter.install(generation) { callbackGeneration, status ->
            runOnMain {
              if (
                !destroyed &&
                callbackGeneration == licenseGeneration
              ) {
                emitLicenseStatus(status)
              }
            }
          }
        }.getOrElse { error ->
          emitError(
            ERROR_LICENSE_CALLBACK,
            "License status callbacks are unavailable in this SDK build",
            error
          )
          emitLicenseStatus(NosmaiLicenseStatusAdapter.UNKNOWN)
          NosmaiLicenseStatusAdapter.UNKNOWN
        }

        if (!NosmaiSDK.isInitialized()) {
          NosmaiSDK.initialize(context.applicationContext, licenseKey)
        }
        sdkInitialized = NosmaiSDK.isInitialized()
        if (!sdkInitialized) {
          reject(
            promise,
            ERROR_INITIALIZATION,
            "Nosmai SDK initialization did not complete"
          )
          return@runOnMain
        }
        initializedKeyDigest = requestedDigest
        registerGameEventListener()

        registerPipelineStateListener()
        val view = activeView
        if (previewView == null && view != null) {
          createAndMountPreview(view, view.controllerToken)
        } else {
          previewView?.let { initializePreviewPipeline(it) }
        }
        moduleSink?.onActiveEffectsChanged(NosmaiStateMapper.currentPipelineState())
        val verifiedStatus = licenseAdapter.currentStatus()
        if (verifiedStatus != NosmaiLicenseStatusAdapter.UNKNOWN) {
          emitLicenseStatus(verifiedStatus)
        }
        promise.resolve(true)
      } catch (error: Throwable) {
        sdkInitialized = false
        reject(
          promise,
          ERROR_INITIALIZATION,
          error.message ?: "Unable to initialize the Nosmai SDK",
          error
        )
      }
    }
  }

  fun configureCamera(
    owner: NosmaiModuleEventSink,
    position: String,
    sessionPreset: String?,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureAvailable(promise)) return@runOnMain
      if (!ensureMediaTransitionAllowed(promise)) return@runOnMain
      val front = when (position) {
        "front" -> true
        "back" -> false
        else -> {
          reject(promise, ERROR_ARGUMENT, "position must be 'front' or 'back'")
          return@runOnMain
        }
      }
      val dimensions = dimensionsForPreset(sessionPreset)
      if (dimensions == null) {
        reject(
          promise,
          ERROR_CAMERA_PRESET,
          "Unsupported Android camera preset: $sessionPreset"
        )
        return@runOnMain
      }

      val previousFront = desiredFrontCamera
      val previousWidth = targetWidth
      val previousHeight = targetHeight
      val configuration = ++cameraConfigurationGeneration
      val facingChanged = desiredFrontCamera != front
      val dimensionsChanged = targetWidth != dimensions.first || targetHeight != dimensions.second
      if (facingChanged || dimensionsChanged) frameStream.stop()
      if (facingChanged) resetCameraLightModes()
      desiredFrontCamera = front
      targetWidth = dimensions.first
      targetHeight = dimensions.second
      activeView?.updateCameraPositionFromController(if (front) "front" else "back")
      applyCameraPresentation()

      if ((facingChanged || dimensionsChanged) && processing && !cameraPaused && !hostPaused) {
        activeView?.showTransitionOverlay()
        restartCamera { accepted ->
          if (configuration != cameraConfigurationGeneration) {
            reject(
              promise,
              ERROR_OPERATION_CANCELLED,
              "Camera configuration was superseded by a newer request"
            )
            return@restartCamera
          }
          if (accepted) promise.resolve(null)
          else {
            desiredFrontCamera = previousFront
            targetWidth = previousWidth
            targetHeight = previousHeight
            activeView?.updateCameraPositionFromController(
              if (previousFront) "front" else "back"
            )
            applyCameraPresentation()
            rejectCameraStart(promise, "Camera reconfiguration was rejected")
          }
        }
      } else {
        promise.resolve(null)
      }
    }
  }

  fun startProcessing(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      startProcessingInternal(promise)
    }
  }

  fun stopProcessing(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureAvailable(promise)) return@runOnMain
      if (!ensureMediaTransitionAllowed(promise)) return@runOnMain
      frameStream.stop()
      processingStopPromises += promise
      if (processingStopping) {
        resumeAfterViewReplacement = false
        resumeReplacementCameraPaused = false
        resumeAfterHost = false
        cancelPendingProcessingStarts(
          ERROR_OPERATION_CANCELLED,
          "Processing was stopped before the queued start completed"
        )
        return@runOnMain
      }

      val transition = ++transitionGeneration
      processingStopping = true
      processing = false
      cameraPaused = false
      pendingCameraStart = false
      resumeAfterHost = false
      cancelYuvPipelineStart("Processing was stopped")
      cancelPendingCameraStartCallbacks()
      cancelPendingProcessingStarts(
        ERROR_OPERATION_CANCELLED,
        "Processing was stopped before the queued start completed"
      )
      invalidateOesWatchdog()
      stopCameraAsync {
        if (transition != transitionGeneration) return@stopCameraAsync
        val sdkFailure = runCatching { NosmaiSDK.stopProcessing() }.exceptionOrNull()
        var failure = cameraStopSettlementFailure ?: sdkFailure
        runCatching { activeView?.showTransitionOverlay() }
          .exceptionOrNull()?.let { if (failure == null) failure = it }
        completeProcessingStop(failure)
      }
    }
  }

  fun pauseCamera(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      if (!ensureMediaTransitionAllowed(promise)) return@runOnMain
      if (!processing) {
        reject(promise, ERROR_INVALID_STATE, "Cannot pause when processing is not active")
        return@runOnMain
      }
      frameStream.stop()
      if (cameraPaused) {
        if (cameraStopping) {
          stopCameraAsync { completePauseAfterCameraStop(promise) }
        } else {
          promise.resolve(true)
        }
        return@runOnMain
      }

      cameraPaused = true
      pendingCameraStart = false
      cancelYuvPipelineStart("Camera pause cancelled the pending start")
      cancelPendingCameraStartCallbacks()
      invalidateOesWatchdog()
      stopCameraAsync { completePauseAfterCameraStop(promise) }
    }
  }

  private fun completePauseAfterCameraStop(promise: Promise) {
    val stopFailure = cameraStopSettlementFailure
    if (stopFailure != null) {
      reject(
        promise,
        ERROR_PROCESSING_STOP,
        stopFailure.message ?: "Unable to pause the camera cleanly",
        stopFailure
      )
      return
    }
    if (cameraPaused && processing) {
      runCatching { previewView?.onPause() }
      promise.resolve(true)
    } else {
      reject(
        promise,
        ERROR_OPERATION_CANCELLED,
        "Pause was superseded by a newer camera transition"
      )
    }
  }

  fun resumeCamera(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      if (!processing) {
        reject(promise, ERROR_INVALID_STATE, "Cannot resume when processing is not active")
        return@runOnMain
      }
      if (!cameraPaused) {
        if (!cameraRunning && !cameraStarting && !hostPaused) {
          firstFrameDelivered = false
          activeView?.showTransitionOverlay()
          runCatching { previewView?.onResume() }
          startCameraAfterPipelineReady { accepted ->
            if (accepted) promise.resolve(true)
            else rejectCameraStart(promise, "Camera resume was rejected")
          }
        } else {
          if (hostPaused) resumeAfterHost = true
          promise.resolve(true)
        }
        return@runOnMain
      }

      cameraPaused = false
      firstFrameDelivered = false
      activeView?.showTransitionOverlay()
      if (hostPaused) {
        resumeAfterHost = true
        promise.resolve(true)
        return@runOnMain
      }
      runCatching { previewView?.onResume() }
      startCameraAfterPipelineReady { accepted ->
        if (accepted) promise.resolve(true)
        else rejectCameraStart(promise, "Camera resume was rejected")
      }
    }
  }

  fun switchCamera(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      if (!ensureMediaTransitionAllowed(promise)) return@runOnMain
      val now = SystemClock.uptimeMillis()
      if (switchingCamera || now - lastSwitchAtMs < CAMERA_SWITCH_DEBOUNCE_MS) {
        promise.resolve(false)
        return@runOnMain
      }

      frameStream.stop()
      switchingCamera = true
      val configuration = ++cameraConfigurationGeneration
      lastSwitchAtMs = now
      val previousFrontCamera = desiredFrontCamera
      resetCameraLightModes()
      desiredFrontCamera = !desiredFrontCamera
      activeView?.updateCameraPositionFromController(
        if (desiredFrontCamera) "front" else "back"
      )
      activeView?.showTransitionOverlay()
      applyCameraPresentation()

      if (!processing || cameraPaused || hostPaused) {
        switchingCamera = false
        promise.resolve(true)
        return@runOnMain
      }

      restartCamera { accepted ->
        if (configuration != cameraConfigurationGeneration) {
          switchingCamera = false
          reject(
            promise,
            ERROR_OPERATION_CANCELLED,
            "Camera switch was superseded by a newer configuration"
          )
          return@restartCamera
        }
        switchingCamera = false
        if (accepted) {
          promise.resolve(true)
        } else {
          desiredFrontCamera = previousFrontCamera
          activeView?.updateCameraPositionFromController(
            if (previousFrontCamera) "front" else "back"
          )
          applyCameraPresentation()
          rejectCameraStart(promise, "Camera switch was rejected")
        }
      }
    }
  }

  fun hasFlash(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      promise.resolve(cameraLightCapability(torch = false))
    }
  }

  fun hasTorch(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      promise.resolve(cameraLightCapability(torch = true))
    }
  }

  fun setFlashMode(owner: NosmaiModuleEventSink, mode: String, promise: Promise) {
    setCameraLightMode(owner, mode, torch = false, promise)
  }

  fun setTorchMode(owner: NosmaiModuleEventSink, mode: String, promise: Promise) {
    setCameraLightMode(owner, mode, torch = true, promise)
  }

  fun getFlashMode(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      promise.resolve(
        if (cameraLightCapability(torch = false)) desiredFlashMode else LIGHT_MODE_OFF
      )
    }
  }

  fun getTorchMode(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      promise.resolve(
        if (cameraLightCapability(torch = true)) desiredTorchMode else LIGHT_MODE_OFF
      )
    }
  }

  fun cleanup(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureUsable(promise)) return@runOnMain
      if (!ensureMediaTransitionAllowed(promise)) return@runOnMain
      frameStream.stop()
      resetCameraLightModes()
      cleanupPromises += promise
      if (cleanupInProgress) return@runOnMain
      cleanupInProgress = true
      cleanupSession(::completeCleanup)
    }
  }

  fun startFrameStream(
    owner: NosmaiModuleEventSink,
    maxFramesPerSecond: Double,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      val maximumRate = validateCodegenInteger(
        maxFramesPerSecond,
        "maxFramesPerSecond",
        NosmaiAndroidFrameStream.MIN_MAX_FPS,
        NosmaiAndroidFrameStream.MAX_MAX_FPS,
        promise
      ) ?: return@runOnMain
      if (
        !processing ||
        processingStopping ||
        cameraPaused ||
        hostPaused ||
        previewView == null ||
        activeView == null
      ) {
        reject(
          promise,
          ERROR_INVALID_STATE,
          "Start an active camera preview before starting the processed frame stream"
        )
        return@runOnMain
      }
      if (frameStream.isActive()) {
        reject(
          promise,
          ERROR_INVALID_STATE,
          "The processed frame stream is already active"
        )
        return@runOnMain
      }
      runCatching { frameStream.start(maximumRate) }.fold(
        onSuccess = { promise.resolve(null) },
        onFailure = { error ->
          reject(
            promise,
            ERROR_FRAME_STREAM,
            error.message ?: "Unable to start the processed frame stream",
            error
          )
        }
      )
    }
  }

  fun stopFrameStream(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      frameStream.stop()
      promise.resolve(null)
    }
  }

  fun getLatestFrame(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      frameStream.takeLatest(promise)
    }
  }

  fun isFrameStreamActive(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      promise.resolve(frameStream.isActive())
    }
  }

  fun capturePhoto(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      if (mediaRecorder.isBusy()) {
        reject(
          promise,
          NosmaiMediaErrorCode.RECORDING_IN_PROGRESS,
          "Photo capture is unavailable while recording"
        )
        return@runOnMain
      }
      if (captureInProgress) {
        reject(
          promise,
          NosmaiMediaErrorCode.CAPTURE_IN_PROGRESS,
          "A rendered photo capture is already in progress"
        )
        return@runOnMain
      }
      val mediaSession = activeMediaSession(
        promise,
        NosmaiMediaErrorCode.CAPTURE_FAILED,
        "Start the rendered camera preview before capturing a photo"
      ) ?: return@runOnMain
      val view = mediaSession.first
      val preview = mediaSession.second
      val viewToken = view.controllerToken
      val captureSessionGeneration = sessionGeneration
      val helper = cameraHelper
      if (helper == null) {
        reject(
          promise,
          NosmaiMediaErrorCode.CAPTURE_FAILED,
          "The camera session is unavailable for photo capture"
        )
        return@runOnMain
      }
      captureInProgress = true
      var flashPreparationSettled = false
      val flashPreparationWatchdog = Runnable {
        if (flashPreparationSettled) return@Runnable
        flashPreparationSettled = true
        helper.finishPhotoFlash()
        captureInProgress = false
        applyPendingViewConfiguration()
        reject(
          promise,
          NosmaiMediaErrorCode.CAPTURE_FAILED,
          "Flash preparation did not complete in time"
        )
      }
      mainHandler.postDelayed(
        flashPreparationWatchdog,
        PHOTO_FLASH_PREPARATION_TIMEOUT_MS
      )
      helper.preparePhotoFlash { _, flashError ->
        runOnMain flashReady@{
          if (flashPreparationSettled) {
            helper.finishPhotoFlash()
            return@flashReady
          }
          flashPreparationSettled = true
          mainHandler.removeCallbacks(flashPreparationWatchdog)
          if (flashError != null) {
            helper.finishPhotoFlash()
            captureInProgress = false
            applyPendingViewConfiguration()
            reject(
              promise,
              NosmaiMediaErrorCode.CAPTURE_FAILED,
              flashError.message ?: "Unable to prepare the configured flash mode",
              flashError
            )
            return@flashReady
          }
          if (
            destroyed ||
            !sdkInitialized ||
            sessionGeneration != captureSessionGeneration ||
            !isActivePreview(view, preview, viewToken)
          ) {
            helper.finishPhotoFlash()
            captureInProgress = false
            applyPendingViewConfiguration()
            reject(
              promise,
              ERROR_OPERATION_CANCELLED,
              "Photo capture was cancelled before flash preparation completed"
            )
            return@flashReady
          }
          mediaCapture.capture(
            preview,
            isSessionCurrent = {
              !destroyed &&
                sdkInitialized &&
                sessionGeneration == captureSessionGeneration &&
                isActivePreview(view, preview, viewToken) &&
                processing &&
                !processingStopping &&
                cameraRunning &&
                firstFrameDelivered &&
                !cameraPaused &&
                !hostPaused
            }
          ) { photo, error ->
            helper.finishPhotoFlash()
            captureInProgress = false
            applyPendingViewConfiguration()
            if (error != null || photo == null) {
              val failure = error ?: NosmaiMediaException(
                NosmaiMediaErrorCode.CAPTURE_FAILED,
                "Rendered photo capture returned no result"
              )
              reject(promise, failure.code, failure.message, failure.cause)
            } else {
              promise.resolve(photo.toWritableMap())
            }
          }
        }
      }
    }
  }

  fun startRecording(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      if (captureInProgress) {
        reject(
          promise,
          NosmaiMediaErrorCode.CAPTURE_IN_PROGRESS,
          "Recording cannot start while a photo capture is in progress"
        )
        return@runOnMain
      }
      if (mediaRecorder.isBusy()) {
        reject(
          promise,
          NosmaiMediaErrorCode.RECORDING_IN_PROGRESS,
          "A recording operation is already in progress"
        )
        return@runOnMain
      }
      val preview = activeMediaSession(
        promise,
        NosmaiMediaErrorCode.RECORDING_START,
        "Start the rendered camera preview before recording"
      )?.second ?: return@runOnMain
      mediaRecorder.start(preview) { error ->
        if (error == null) {
          promise.resolve(null)
        } else {
          applyPendingViewConfiguration()
          reject(promise, error.code, error.message, error.cause)
        }
      }
    }
  }

  fun stopRecording(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      mediaRecorder.stop { recording, error ->
        applyPendingViewConfiguration()
        if (error != null || recording == null) {
          val failure = error ?: NosmaiMediaException(
            NosmaiMediaErrorCode.RECORDING_STOP,
            "Recording stopped without a media result"
          )
          reject(promise, failure.code, failure.message, failure.cause)
          return@stop
        }
        recording.muxWarning?.let { warning ->
          emitError(
            NosmaiMediaErrorCode.RECORDING_AUDIO_MUX,
            "The video was saved without its microphone audio track",
            warning
          )
        }
        promise.resolve(recording.toWritableMap())
      }
    }
  }

  fun isRecording(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      promise.resolve(mediaRecorder.isBusy())
    }
  }

  fun getCurrentRecordingDuration(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      promise.resolve(mediaRecorder.currentDurationSeconds())
    }
  }

  fun saveImageToGallery(
    owner: NosmaiModuleEventSink,
    imageUri: String,
    name: String?,
    promise: Promise
  ) {
    saveToGallery(owner, imageUri, name, NosmaiAndroidGallery.MediaKind.PHOTO, promise)
  }

  fun saveVideoToGallery(
    owner: NosmaiModuleEventSink,
    videoUri: String,
    name: String?,
    promise: Promise
  ) {
    saveToGallery(owner, videoUri, name, NosmaiAndroidGallery.MediaKind.VIDEO, promise)
  }

  private fun saveToGallery(
    owner: NosmaiModuleEventSink,
    mediaUri: String,
    name: String?,
    kind: NosmaiAndroidGallery.MediaKind,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureUsable(promise)) return@runOnMain
      mediaGallery.save(mediaUri, name, kind) { item, error ->
        if (error != null || item == null) {
          val failure = error ?: NosmaiMediaException(
            NosmaiMediaErrorCode.GALLERY_SAVE,
            "Gallery save returned no result"
          )
          reject(promise, failure.code, failure.message, failure.cause)
        } else {
          promise.resolve(item.toWritableMap())
        }
      }
    }
  }

  fun applyEffect(
    owner: NosmaiModuleEventSink,
    packagePath: String,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      if (!canApplyEffect()) {
        reject(
          promise,
          ERROR_INVALID_STATE,
          "Start processing and keep the app active before applying an effect"
        )
        return@runOnMain
      }
      val source = resolvePackageSource(packagePath)
      if (source == null) {
        reject(
          promise,
          ERROR_PACKAGE_PATH,
          "packagePath must reference a readable local .nosmai file or installed package asset"
        )
        return@runOnMain
      }

      enqueueEffectOperation(owner, promise) { finish ->
        if (cleanupInProgress) {
          finish(
            NosmaiEffectOperationResult(
              code = ERROR_OPERATION_CANCELLED,
              message = "Effect apply was cancelled by session cleanup"
            )
          )
          return@enqueueEffectOperation
        }
        if (!canApplyEffect()) {
          finish(
            NosmaiEffectOperationResult(
              code = ERROR_INVALID_STATE,
              message =
                "Start processing and keep the app active before applying an effect"
            )
          )
          return@enqueueEffectOperation
        }
        if (!isPackageSourceReadable(source)) {
          finish(
            NosmaiEffectOperationResult(
              code = ERROR_PACKAGE_PATH,
              message = "The effect package is no longer readable or installed"
            )
          )
          return@enqueueEffectOperation
        }

        try {
          NosmaiEffects.applyEffect(
            source.sdkPath,
            object : NosmaiEffects.EffectCallback {
              override fun onSuccess() {
                runOnMain {
                  waitForEffectPipelineCondition(
                    condition = { state ->
                      pathsEqual(state.activeFilterPath, source.sdkPath) ||
                        pathsEqual(state.activeEffectPath, source.sdkPath) ||
                        pathsEqual(
                          state.activeBackgroundPackagePath,
                          source.sdkPath
                        )
                    }
                  ) { failure ->
                    if (failure == null) {
                      val state = runCatching {
                        NosmaiEffects.getCurrentPipelineState()
                      }.getOrNull()
                      if (pathsEqual(state?.activeEffectPath, source.sdkPath)) {
                        // Authored AR/beauty packages evict built-in beauty
                        // controls in the SDK's exclusivity handoff. Color
                        // controls are tracked independently and may coexist.
                        visualEffects.resetBeautyTracking()
                        visualEffects.reconcileBuiltInState()
                      }
                      finish(NosmaiEffectOperationResult(value = true))
                    } else {
                      finish(
                        NosmaiEffectOperationResult(
                          code = ERROR_EFFECT_APPLY,
                          message = failure.message,
                          cause = failure.cause
                        )
                      )
                    }
                  }
                }
              }

              override fun onError(errorMessage: String?) {
                finish(
                  NosmaiEffectOperationResult(
                    code = ERROR_EFFECT_APPLY,
                    message = errorMessage ?: "The effect package could not be applied"
                  )
                )
              }
            }
          )
        } catch (error: Throwable) {
          finish(
            NosmaiEffectOperationResult(
              code = ERROR_EFFECT_APPLY,
              message = error.message ?: "The effect package could not be applied",
              cause = error
            )
          )
        }
      }
    }
  }

  fun getActiveEffects(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      resolveSdkQuery(promise, ERROR_EFFECT_STATE) {
        NosmaiStateMapper.currentPipelineState()
      }
    }
  }

  fun getActiveFilterInfo(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      resolveSdkQuery(promise, ERROR_EFFECT_STATE) {
        NosmaiStateMapper.filterInfoToWritableMap(NosmaiEffects.getActiveFilterInfo())
      }
    }
  }

  fun getActiveEffectInfo(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      resolveSdkQuery(promise, ERROR_EFFECT_STATE) {
        NosmaiStateMapper.filterInfoToWritableMap(NosmaiEffects.getActiveEffectInfo())
      }
    }
  }

  fun getEffectParameters(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      enqueueEffectOperation(owner, promise) { finish ->
        try {
          finish(
            NosmaiEffectOperationResult(
              value = NosmaiEffectParameterAdapter.currentParameters()
            )
          )
        } catch (error: Throwable) {
          finish(effectParameterFailure(error, "Unable to read effect parameters"))
        }
      }
    }
  }

  fun getEffectParameterValue(
    owner: NosmaiModuleEventSink,
    parameterName: String,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      val name = normalizedEffectParameterName(parameterName)
      if (name == null) {
        reject(promise, ERROR_ARGUMENT, "parameterName is invalid")
        return@runOnMain
      }
      enqueueEffectOperation(owner, promise) { finish ->
        try {
          if (!NosmaiEffectParameterAdapter.hasNumericParameter(name)) {
            finish(
              NosmaiEffectOperationResult(
                code = ERROR_EFFECT_PARAMETER,
                message = "Parameter '$name' was not found or is not numeric"
              )
            )
            return@enqueueEffectOperation
          }
          val value = NosmaiEffects.getEffectParameterValue(name)
          if (value.isNaN() || value.isInfinite()) {
            finish(
              NosmaiEffectOperationResult(
                code = ERROR_EFFECT_PARAMETER,
                message = "Parameter '$name' was not found or is not numeric"
              )
            )
          } else {
            finish(NosmaiEffectOperationResult(value = value.toDouble()))
          }
        } catch (error: Throwable) {
          finish(effectParameterFailure(error, "Unable to read effect parameter '$name'"))
        }
      }
    }
  }

  fun setEffectParameter(
    owner: NosmaiModuleEventSink,
    parameterName: String,
    value: Double,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      val name = normalizedEffectParameterName(parameterName)
      if (name == null || !value.isFinite()) {
        reject(
          promise,
          ERROR_ARGUMENT,
          "parameterName must be non-empty and value must be finite"
        )
        return@runOnMain
      }
      enqueueEffectOperation(owner, promise) { finish ->
        try {
          val accepted = NosmaiEffects.setEffectParameter(name, value.toFloat())
          if (accepted) {
            finish(NosmaiEffectOperationResult(value = true))
          } else {
            finish(
              NosmaiEffectOperationResult(
                code = ERROR_EFFECT_PARAMETER,
                message = "Parameter '$name' does not accept a numeric value"
              )
            )
          }
        } catch (error: Throwable) {
          finish(effectParameterFailure(error, "Unable to set effect parameter '$name'"))
        }
      }
    }
  }

  fun setEffectParameterString(
    owner: NosmaiModuleEventSink,
    parameterName: String,
    value: String,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      val name = normalizedEffectParameterName(parameterName)
      if (name == null || !isSafeEffectParameterString(value)) {
        reject(
          promise,
          ERROR_ARGUMENT,
          "parameterName or string value is invalid"
        )
        return@runOnMain
      }
      enqueueEffectOperation(owner, promise) { finish ->
        try {
          val accepted = NosmaiEffects.setEffectParameter(name, value)
          if (accepted) {
            finish(NosmaiEffectOperationResult(value = true))
          } else {
            finish(
              NosmaiEffectOperationResult(
                code = ERROR_EFFECT_PARAMETER,
                message = "Parameter '$name' does not accept a string value"
              )
            )
          }
        } catch (error: Throwable) {
          finish(effectParameterFailure(error, "Unable to set effect parameter '$name'"))
        }
      }
    }
  }

  fun isGameReady(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      resolveSdkQuery(promise, ERROR_EFFECT_STATE) { NosmaiEffects.isGameReady() }
    }
  }

  fun sendGameTap(
    owner: NosmaiModuleEventSink,
    normalizedX: Double,
    normalizedY: Double,
    promise: Promise
  ) {
    sendGameInput(owner, null, normalizedX, normalizedY, 1.0, promise)
  }

  fun sendGameInput(
    owner: NosmaiModuleEventSink,
    name: String?,
    normalizedX: Double,
    normalizedY: Double,
    value: Double,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      val validCoordinates = normalizedX.isFinite() && normalizedY.isFinite() &&
        normalizedX in 0.0..1.0 && normalizedY in 0.0..1.0
      val validName = name == null || name.trim().isNotEmpty()
      if (!validCoordinates || !validName || !value.isFinite()) {
        reject(
          promise,
          ERROR_ARGUMENT,
          "Game input requires normalized coordinates, a non-empty name, and a finite value"
        )
        return@runOnMain
      }
      resolveSdkQuery(promise, ERROR_EFFECT_STATE) {
        if (name == null) {
          NosmaiEffects.sendGameTap(normalizedX.toFloat(), normalizedY.toFloat())
        } else {
          NosmaiEffects.sendGameInput(
            name.trim(),
            normalizedX.toFloat(),
            normalizedY.toFloat(),
            value.toFloat()
          )
        }
      }
    }
  }

  fun pauseGame(owner: NosmaiModuleEventSink, promise: Promise) {
    runGameControl(owner, promise) { NosmaiEffects.pauseGame() }
  }

  fun resumeGame(owner: NosmaiModuleEventSink, promise: Promise) {
    runGameControl(owner, promise) { NosmaiEffects.resumeGame() }
  }

  fun restartGame(owner: NosmaiModuleEventSink, promise: Promise) {
    runGameControl(owner, promise) { NosmaiEffects.restartGame() }
  }

  private fun runGameControl(
    owner: NosmaiModuleEventSink,
    promise: Promise,
    operation: () -> Unit
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      try {
        operation()
        promise.resolve(null)
      } catch (error: Throwable) {
        reject(
          promise,
          ERROR_EFFECT_STATE,
          error.message ?: "Unable to control the active game",
          error
        )
      }
    }
  }

  fun getLocalFilters(
    owner: NosmaiModuleEventSink,
    packageType: String?,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain

      val nativeType = when (packageType) {
        null -> null
        "filter" -> NosmaiFilterInfo.Type.FILTER
        "effect" -> NosmaiFilterInfo.Type.EFFECT
        "background" -> NosmaiFilterInfo.Type.BACKGROUND
        "beauty_effect" -> NosmaiFilterInfo.Type.BEAUTY_EFFECT
        "game" -> NosmaiFilterInfo.Type.GAME
        else -> {
          reject(
            promise,
            ERROR_ARGUMENT,
            "packageType must be filter, effect, background, beauty_effect, or game"
          )
          return@runOnMain
        }
      }

      try {
        val filters = if (nativeType == null) {
          NosmaiEffects.getFilters()
        } else {
          NosmaiEffects.getFilters(nativeType)
        }
        val items = Arguments.createArray()
        filters.forEach { filter ->
          NosmaiStateMapper.filterInfoToWritableMap(filter)?.let(items::pushMap)
        }
        promise.resolve(Arguments.createMap().apply { putArray("items", items) })
      } catch (error: Throwable) {
        reject(
          promise,
          ERROR_LOCAL_CATALOG,
          error.message ?: "Unable to load the local Nosmai package catalog",
          error
        )
      }
    }
  }

  fun getDebugFilters(
    owner: NosmaiModuleEventSink,
    packageType: String?,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain

      val nativeType = when (packageType) {
        null -> null
        "filter" -> NosmaiFilterInfo.Type.FILTER
        "effect" -> NosmaiFilterInfo.Type.EFFECT
        "background" -> NosmaiFilterInfo.Type.BACKGROUND
        "beauty_effect" -> NosmaiFilterInfo.Type.BEAUTY_EFFECT
        "game" -> NosmaiFilterInfo.Type.GAME
        else -> {
          reject(
            promise,
            ERROR_ARGUMENT,
            "packageType must be filter, effect, background, beauty_effect, or game"
          )
          return@runOnMain
        }
      }

      val callback = NosmaiEffects.DebugFiltersCallback { filters, error ->
        runOnMain {
          if (!ensureModuleOwner(owner, promise)) return@runOnMain
          if (error != null) {
            reject(
              promise,
              ERROR_DEBUG_FILTERS,
              error.message ?: "Unable to load bundled debug filters",
              error
            )
            return@runOnMain
          }

          val items = Arguments.createArray()
          filters.orEmpty().forEach { filter ->
            NosmaiStateMapper.filterInfoToWritableMap(filter)?.let(items::pushMap)
          }
          promise.resolve(Arguments.createMap().apply { putArray("items", items) })
        }
      }

      try {
        if (nativeType == null) {
          NosmaiEffects.getDebugFilters(callback)
        } else {
          NosmaiEffects.getDebugFilters(nativeType, callback)
        }
      } catch (error: Throwable) {
        reject(
          promise,
          ERROR_DEBUG_FILTERS,
          error.message ?: "Unable to load bundled debug filters",
          error
        )
      }
    }
  }

  fun isCloudFilterEnabled(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      cloudFilters.isEnabled { enabled, error ->
        when {
          error != null -> reject(promise, error.code, error.message, error.cause)
          enabled != null -> promise.resolve(enabled)
          else -> reject(
            promise,
            NosmaiCloudErrorCode.CLOUD_CATALOG,
            "Nosmai cloud availability returned no result"
          )
        }
      }
    }
  }

  fun getCloudFilters(
    owner: NosmaiModuleEventSink,
    packageType: String?,
    version: String,
    page: Double,
    limit: Double,
    fetchAllPages: Boolean,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      val nativePage = validateCodegenInteger(
        page,
        "page",
        0,
        Int.MAX_VALUE,
        promise
      ) ?: return@runOnMain
      val nativeLimit = validateCodegenInteger(
        limit,
        "limit",
        1,
        100,
        promise
      ) ?: return@runOnMain

      cloudFilters.fetch(
        NosmaiAndroidCloudQuery(
          packageType = packageType,
          version = version,
          page = nativePage,
          limit = nativeLimit,
          fetchAllPages = fetchAllPages
        )
      ) { result, error ->
        when {
          error != null -> reject(promise, error.code, error.message, error.cause)
          result != null -> promise.resolve(Arguments.makeNativeMap(result))
          else -> reject(
            promise,
            NosmaiCloudErrorCode.CLOUD_CATALOG,
            "The Nosmai cloud catalog returned no result"
          )
        }
      }
    }
  }

  fun downloadCloudFilter(
    owner: NosmaiModuleEventSink,
    filterId: String,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      cloudFilters.download(filterId) { result, error ->
        when {
          error != null -> reject(promise, error.code, error.message, error.cause)
          result != null -> promise.resolve(Arguments.makeNativeMap(result))
          else -> reject(
            promise,
            NosmaiCloudErrorCode.CLOUD_DOWNLOAD,
            "The Nosmai cloud download returned no result"
          )
        }
      }
    }
  }

  fun removeCloudFilter(
    owner: NosmaiModuleEventSink,
    filterId: String,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      cloudFilters.remove(filterId) { removed, error ->
        when {
          error != null -> reject(promise, error.code, error.message, error.cause)
          removed != null -> promise.resolve(removed)
          else -> reject(
            promise,
            NosmaiCloudErrorCode.CLOUD_REMOVE,
            "Nosmai cloud package removal returned no result"
          )
        }
      }
    }
  }

  fun removeEffect(
    owner: NosmaiModuleEventSink,
    packagePath: String,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      val normalizedPath = resolveComparablePath(packagePath)
      if (normalizedPath == null) {
        reject(promise, ERROR_PACKAGE_PATH, "packagePath must not be empty")
        return@runOnMain
      }

      enqueueEffectOperation(owner, promise) { finish ->
        try {
          val state = NosmaiEffects.getCurrentPipelineState()
          when {
            pathsEqual(state?.activeFilterPath, normalizedPath) ->
              clearEffectSlot(
                NosmaiEffectMutationAdapter.Slot.COLOR,
                ERROR_EFFECT_CLEAR,
                finish,
                true
              ) { it.activeFilterPath.isNullOrBlank() }
            pathsEqual(state?.activeEffectPath, normalizedPath) ->
              clearEffectSlot(
                NosmaiEffectMutationAdapter.Slot.AR,
                ERROR_EFFECT_CLEAR,
                finish,
                true
              ) { it.activeEffectPath.isNullOrBlank() }
            pathsEqual(state?.activeBackgroundPackagePath, normalizedPath) ->
              clearEffectSlot(
                NosmaiEffectMutationAdapter.Slot.BACKGROUND,
                ERROR_EFFECT_CLEAR,
                finish,
                true
              ) { it.activeBackgroundPackagePath.isNullOrBlank() }
            else -> finish(NosmaiEffectOperationResult(value = false))
          }
        } catch (error: Throwable) {
          finish(
            NosmaiEffectOperationResult(
              code = ERROR_EFFECT_CLEAR,
              message = error.message ?: "Unable to remove the active effect package",
              cause = error
            )
          )
        }
      }
    }
  }

  fun clearFilter(owner: NosmaiModuleEventSink, promise: Promise) {
    clearScopedEffect(owner, promise, "filter") {
      it.activeFilterPath.isNullOrBlank()
    }
  }

  fun clearAREffect(owner: NosmaiModuleEventSink, promise: Promise) {
    clearScopedEffect(owner, promise, "effect") {
      it.activeEffectPath.isNullOrBlank()
    }
  }

  fun clearAll(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      enqueueEffectOperation(owner, promise) { finish ->
        clearAllEffectState(finish, null)
      }
    }
  }

  fun isBeautyEffectEnabled(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      resolveSdkQuery(promise, NosmaiAndroidVisualEffects.ERROR_VISUAL_CONTROL) {
        visualEffects.isBeautyEffectEnabled()
      }
    }
  }

  fun isAdvancedFiltersEnabled(owner: NosmaiModuleEventSink, promise: Promise) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      resolveSdkQuery(promise, NosmaiAndroidVisualEffects.ERROR_VISUAL_CONTROL) {
        visualEffects.isAdvancedFiltersEnabled()
      }
    }
  }

  fun setBeautyValue(
    owner: NosmaiModuleEventSink,
    control: String,
    value: Double,
    promise: Promise
  ) {
    enqueueVisualMutation(owner, promise, NosmaiVisualCapability.BEAUTY) {
      visualEffects.setBeautyValue(control, value)
    }
  }

  fun clearBeauty(owner: NosmaiModuleEventSink, promise: Promise) {
    enqueueVisualMutation(owner, promise) {
      visualEffects.clearBeauty()
    }
  }

  fun applyMakeup(
    owner: NosmaiModuleEventSink,
    makeupType: String,
    style: String,
    red: Double,
    green: Double,
    blue: Double,
    intensity: Double,
    promise: Promise
  ) {
    enqueueVisualMutation(owner, promise, NosmaiVisualCapability.BEAUTY) {
      visualEffects.applyMakeup(
        makeupType,
        style,
        red,
        green,
        blue,
        intensity
      )
    }
  }

  fun setMakeupIntensity(
    owner: NosmaiModuleEventSink,
    makeupType: String,
    intensity: Double,
    promise: Promise
  ) {
    enqueueVisualMutation(owner, promise, NosmaiVisualCapability.BEAUTY) {
      visualEffects.setMakeupIntensity(makeupType, intensity)
    }
  }

  fun removeMakeup(
    owner: NosmaiModuleEventSink,
    makeupType: String,
    promise: Promise
  ) {
    enqueueVisualMutation(owner, promise) {
      visualEffects.removeMakeup(makeupType)
    }
  }

  fun isMakeupActive(
    owner: NosmaiModuleEventSink,
    makeupType: String,
    promise: Promise
  ) {
    enqueueVisualQuery(owner, promise) {
      visualEffects.isMakeupActive(makeupType)
    }
  }

  fun clearMakeup(owner: NosmaiModuleEventSink, promise: Promise) {
    enqueueVisualMutation(owner, promise) {
      visualEffects.clearMakeup()
    }
  }

  fun setReshape(
    owner: NosmaiModuleEventSink,
    reshapeType: String,
    value: Double,
    promise: Promise
  ) {
    enqueueVisualMutation(owner, promise, NosmaiVisualCapability.BEAUTY) {
      visualEffects.setReshape(reshapeType, value)
    }
  }

  fun clearReshapes(owner: NosmaiModuleEventSink, promise: Promise) {
    enqueueVisualMutation(owner, promise) {
      visualEffects.clearReshapes()
    }
  }

  fun setEyeColor(
    owner: NosmaiModuleEventSink,
    red: Double,
    green: Double,
    blue: Double,
    intensity: Double,
    promise: Promise
  ) {
    enqueueVisualMutation(owner, promise, NosmaiVisualCapability.BEAUTY) {
      visualEffects.setEyeColor(red, green, blue, intensity)
    }
  }

  fun setEyeColorIntensity(
    owner: NosmaiModuleEventSink,
    intensity: Double,
    promise: Promise
  ) {
    enqueueVisualMutation(owner, promise, NosmaiVisualCapability.BEAUTY) {
      visualEffects.setEyeColorIntensity(intensity)
    }
  }

  fun removeEyeColor(owner: NosmaiModuleEventSink, promise: Promise) {
    enqueueVisualMutation(owner, promise) {
      visualEffects.removeEyeColor()
    }
  }

  fun isEyeColorActive(owner: NosmaiModuleEventSink, promise: Promise) {
    enqueueVisualQuery(owner, promise) {
      visualEffects.isEyeColorActive()
    }
  }

  fun setColorAdjustment(
    owner: NosmaiModuleEventSink,
    control: String,
    value1: Double,
    value2: Double,
    value3: Double,
    promise: Promise
  ) {
    enqueueVisualMutation(owner, promise, NosmaiVisualCapability.BEAUTY) {
      visualEffects.setColorAdjustment(control, value1, value2, value3)
    }
  }

  fun resetColorAdjustments(owner: NosmaiModuleEventSink, promise: Promise) {
    enqueueVisualMutation(owner, promise) {
      visualEffects.resetColorAdjustments()
    }
  }

  fun setBackground(
    owner: NosmaiModuleEventSink,
    mode: String,
    resourceUri: String?,
    red: Double,
    green: Double,
    blue: Double,
    alpha: Double,
    blurStrength: Double,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      visualAdmissionFailure(NosmaiVisualCapability.ADVANCED)?.let { failure ->
        rejectVisualPreflight(promise, failure)
        return@runOnMain
      }

      enqueueEffectOperation(owner, promise) { finish ->
        visualAdmissionFailure(NosmaiVisualCapability.ADVANCED)?.let { failure ->
          finish(failure)
          return@enqueueEffectOperation
        }
        try {
          visualEffects.prepareBackground(
            mode,
            resourceUri,
            red,
            green,
            blue,
            alpha,
            blurStrength
          ) prepare@{ config, preparationError ->
            if (preparationError != null || config == null) {
              finish(
                visualOperationFailure(
                  preparationError ?: IllegalStateException(
                    "Background preparation returned no configuration"
                  ),
                  NosmaiAndroidVisualEffects.ERROR_BACKGROUND_RESOURCE,
                  "Unable to prepare the background resource"
                )
              )
              return@prepare
            }

            if (!isModuleOwner(owner)) {
              visualEffects.discardPreparedBackground(config)
              finish(
                NosmaiEffectOperationResult(
                  code = ERROR_OPERATION_CANCELLED,
                  message =
                    "The background operation belongs to a retired React Native module"
                )
              )
              return@prepare
            }
            visualAdmissionFailure(NosmaiVisualCapability.ADVANCED)?.let { failure ->
              visualEffects.discardPreparedBackground(config)
              finish(failure)
              return@prepare
            }

            try {
              visualEffects.applyBackground(config)
              settleVisualMutation(
                owner,
                finish,
                condition = { state ->
                  state.activeBackgroundConfig != null && state.isBackgroundActive
                }
              )
            } catch (error: Throwable) {
              visualEffects.discardPreparedBackground(config)
              finish(
                visualOperationFailure(
                  error,
                  NosmaiAndroidVisualEffects.ERROR_VISUAL_CONTROL,
                  "Unable to apply the background"
                )
              )
            }
          }
        } catch (error: Throwable) {
          finish(
            visualOperationFailure(
              error,
              NosmaiAndroidVisualEffects.ERROR_BACKGROUND_RESOURCE,
              "Unable to prepare the background resource"
            )
          )
        }
      }
    }
  }

  fun clearBackground(owner: NosmaiModuleEventSink, promise: Promise) {
    enqueueVisualMutation(
      owner,
      promise,
      condition = { state -> state.activeBackgroundConfig == null }
    ) {
      // This removes only a manually configured background. Package-provided
      // background state remains under the package mutation APIs.
      visualEffects.clearBackground()
    }
  }

  private fun startProcessingInternal(
    promise: Promise?,
    startCameraNow: Boolean = true
  ) {
    if (promise != null && !ensureInitialized(promise)) return
    if (!sdkInitialized) {
      emitError(ERROR_NOT_INITIALIZED, "Initialize the Nosmai SDK before starting")
      return
    }
    if (cleanupInProgress) {
      promise?.let {
        reject(it, ERROR_OPERATION_CANCELLED, "Session cleanup is in progress")
      }
      return
    }
    if (processingStopping) {
      pendingProcessingStarts += promise
      return
    }
    if (processing) {
      if (!startCameraNow) {
        cameraPaused = true
        promise?.resolve(null)
        return
      }
      if (!cameraRunning && !cameraStarting && !cameraPaused && !hostPaused) {
        startCameraAfterPipelineReady { accepted ->
          if (accepted) promise?.resolve(null)
          else rejectCameraStart(promise, "Camera start was rejected")
        }
      } else {
        if (hostPaused && !cameraPaused) {
          resumeAfterHost = true
        }
        promise?.resolve(null)
      }
      return
    }
    if (!hasCameraPermission()) {
      promise?.let {
        reject(
          it,
          NosmaiCamera2Helper.ERROR_PERMISSION,
          "Camera permission must be granted by the host application before startProcessing"
        )
      } ?: emitError(
        NosmaiCamera2Helper.ERROR_PERMISSION,
        "Camera permission must be granted by the host application before startProcessing"
      )
      return
    }

    val view = activeView
    val preview = previewView
    if (view == null || preview == null) {
      promise?.let { reject(it, ERROR_NO_PREVIEW, "Mount NosmaiCameraView before starting") }
        ?: emitError(ERROR_NO_PREVIEW, "Mount NosmaiCameraView before starting")
      return
    }

    try {
      initializePreviewPipeline(preview)
      applyCameraPresentation()
      NosmaiSDK.startProcessing(preview)
      processing = true
      cameraPaused = !startCameraNow
      firstFrameDelivered = false
      view.showTransitionOverlay()
      if (!startCameraNow) {
        promise?.resolve(null)
        return
      }
      if (hostPaused) {
        resumeAfterHost = true
        promise?.resolve(null)
        return
      }
      startCameraAfterPipelineReady { accepted ->
        if (!accepted) {
          val cancelled =
            cameraStartSettlementFailure?.code == ERROR_OPERATION_CANCELLED ||
              processingStopping || cleanupInProgress || cameraPaused || hostPaused
          if (!cancelled) {
            processing = false
            tryStopProcessing()
          }
          rejectCameraStart(promise, "Camera start was rejected")
        } else {
          promise?.resolve(null)
        }
      }
    } catch (error: Throwable) {
      processing = false
      rejectOrEmit(
        promise,
        ERROR_PROCESSING_START,
        error.message ?: "Unable to start Nosmai processing",
        error
      )
    }
  }

  private fun startCamera(onAccepted: ((Boolean) -> Unit)? = null) {
    if (!processing || cameraPaused || hostPaused || destroyed) {
      cameraStartSettlementFailure = CameraFailure(
        ERROR_OPERATION_CANCELLED,
        "Camera start was cancelled by the current session state",
        null
      )
      onAccepted?.invoke(false)
      return
    }
    if (cameraCloseBarrierHelper != null) {
      cameraStartSettlementFailure = CameraFailure(
        NosmaiCamera2Helper.ERROR_CLOSE_TIMEOUT,
        "The previous camera is still completing a physical close",
        null
      )
      runCatching { onAccepted?.invoke(false) }
      return
    }
    if (cameraStopping) {
      pendingCameraStart = true
      onAccepted?.let(pendingCameraStartCallbacks::add)
      return
    }
    if (cameraRunning) {
      onAccepted?.invoke(true)
      return
    }
    if (cameraStarting) {
      cameraStartAttempt?.addCallback(onAccepted) ?: run {
        cameraStartSettlementFailure = CameraFailure(
          ERROR_CAMERA_START,
          "Camera start state is unavailable",
          null
        )
        onAccepted?.invoke(false)
      }
      return
    }

    val view = activeView
    val preview = previewView
    if (view == null || preview == null) {
      cameraStartSettlementFailure = CameraFailure(
        ERROR_NO_PREVIEW,
        "Mount NosmaiCameraView before starting",
        null
      )
      onAccepted?.invoke(false)
      return
    }
    if (!hasCameraPermission()) {
      cameraStartSettlementFailure = CameraFailure(
        NosmaiCamera2Helper.ERROR_PERMISSION,
        "Camera permission must be granted before starting",
        null
      )
      onAccepted?.invoke(false)
      return
    }

    if (useOesInput) {
      val texture = currentOesSurfaceTexture(preview)
      if (texture == null) {
        pendingCameraStart = true
        onAccepted?.let(pendingCameraStartCallbacks::add)
        armOesSurfaceWatchdog(view, preview, view.controllerToken)
        return
      }
    }

    val context = reactContext
    if (context == null) {
      cameraStartSettlementFailure = CameraFailure(
        ERROR_DESTROYED,
        "No active React application context is available",
        null
      )
      onAccepted?.invoke(false)
      return
    }
    cameraStartSettlementFailure = null
    pendingCameraStart = false
    val generation = ++cameraGeneration
    val startingWithOes = useOesInput
    val token = view.controllerToken
    val helper = try {
      NosmaiCamera2Helper(context.applicationContext, desiredFrontCamera)
    } catch (error: Throwable) {
      cameraStartSettlementFailure = CameraFailure(
        ERROR_CAMERA_START,
        error.message ?: "Unable to create the Camera2 session",
        error
      )
      runCatching { onAccepted?.invoke(false) }
      return
    }
    val attempt = CameraStartAttempt(generation, startingWithOes, onAccepted)
    cameraStartAttempt = attempt
    cameraHelper = helper
    try {
      configureCameraHelper(helper, preview, view, token, generation, attempt)
      cameraStarting = true
    } catch (error: Throwable) {
      cameraStarting = false
      cameraStartAttempt = null
      val failure = CameraFailure(
        if (startingWithOes) {
          NosmaiCamera2Helper.ERROR_OES_SURFACE
        } else {
          ERROR_CAMERA_START
        },
        error.message ?: "Unable to configure the Camera2 session",
        error
      )
      cameraStartSettlementFailure = failure
      if (startingWithOes) {
        val callbacks = attempt.transferCallbacks()
        fallbackToYuv(failure.message) { result ->
          deliverCameraStartResult(callbacks, result, "OES fallback was rejected")
        }
      } else {
        cameraHelper = null
        runCatching { helper.quiesceForSurfaceRelease() }
        executeCameraTask { runCatching { helper.stopCamera() } }
        val hadCaller = attempt.hasCaller()
        attempt.settle(false)
        if (!hadCaller) emitCameraError(failure.code, failure.message, failure.cause)
      }
      return
    }

    executeCameraTask(
      onRejected = {
        if (cameraStartAttempt === attempt) {
          cameraStarting = false
          cameraHelper = null
          cameraStartAttempt = null
          cameraStartSettlementFailure = CameraFailure(
            ERROR_DESTROYED,
            "The camera control executor is unavailable",
            null
          )
          val hadCaller = attempt.hasCaller()
          attempt.settle(false)
          if (!hadCaller) {
            emitError(ERROR_DESTROYED, "The camera control executor is unavailable")
          }
        }
      }
    ) {
      val startResult = runCatching { helper.startCamera() }
      val accepted = startResult.getOrDefault(false)
      val thrownFailure = startResult.exceptionOrNull()?.let { error ->
        CameraFailure(
          ERROR_CAMERA_START,
          error.message ?: "Camera start failed unexpectedly",
          error
        )
      }
      runOnMain {
        if (generation != cameraGeneration || cameraHelper !== helper) {
          if (!cameraAttemptsAwaitingStop.contains(attempt)) {
            if (accepted && !cleanupInProgress) {
              executeCameraTask { runCatching { helper.stopCamera() } }
            }
            cameraStartSettlementFailure = CameraFailure(
              ERROR_OPERATION_CANCELLED,
              "Camera start was superseded by a newer transition",
              null
            )
            attempt.settle(false)
          }
          return@runOnMain
        }
        attempt.resultKnown = true
        if (cleanupInProgress) {
          cameraStartSettlementFailure = CameraFailure(
            ERROR_OPERATION_CANCELLED,
            "Camera start was cancelled by session cleanup",
            null
          )
          attempt.settle(false)
          return@runOnMain
        }
        if (!accepted) {
          cameraStarting = false
          cameraHelper = null
          cameraStartAttempt = null
          val failure = attempt.pendingFailure ?: thrownFailure ?: CameraFailure(
            ERROR_CAMERA_START,
            "Camera start was rejected",
            null
          )
          if (thrownFailure != null) {
            executeCameraTask { runCatching { helper.stopCamera() } }
          }
          if (startingWithOes && isOesRecoverableFailure(failure.code)) {
            val callbacks = attempt.transferCallbacks()
            fallbackToYuv(failure.message) { result ->
              deliverCameraStartResult(callbacks, result, "OES fallback was rejected")
            }
          } else {
            val hadCaller = attempt.hasCaller()
            cameraStartSettlementFailure = failure
            attempt.settle(false)
            if (!hadCaller) emitCameraError(failure.code, failure.message, failure.cause)
          }
        } else {
          cameraStartSettlementFailure = null
          attempt.settle(true)
          if (attempt.readyBeforeResult) {
            markCameraReady(view, preview, token, generation, helper, startingWithOes)
          } else {
            armCameraStartWatchdog(view, token, generation, helper)
          }
          attempt.pendingFailure?.let { failure ->
            handleCameraFailure(
              view,
              preview,
              token,
              generation,
              helper,
              attempt,
              failure
            )
          }
        }
      }
    }
  }

  private fun startCameraAfterPipelineReady(onAccepted: ((Boolean) -> Unit)? = null) {
    if (useOesInput) {
      startCamera(onAccepted)
      return
    }
    val view = activeView
    val preview = previewView
    if (view == null || preview == null) {
      startCamera(onAccepted)
      return
    }
    val token = view.controllerToken
    val expectedSession = sessionGeneration
    val existing = yuvPipelineStartAttempt
    if (
      existing != null &&
      existing.matches(view, preview, token, expectedSession)
    ) {
      existing.addCallback(onAccepted)
      return
    }
    if (existing != null) {
      cancelYuvPipelineStart("A newer YUV pipeline start superseded the pending request")
    }

    val attempt = YuvPipelineStartAttempt(
      view,
      preview,
      token,
      expectedSession,
      onAccepted
    )
    yuvPipelineStartAttempt = attempt
    val waitGeneration = ++yuvPipelineWaitGeneration
    waitForYuvPipeline(
      view,
      preview,
      token,
      expectedSession,
      waitGeneration,
      onReady = {
        if (
          yuvPipelineStartAttempt !== attempt ||
          waitGeneration != yuvPipelineWaitGeneration
        ) {
          return@waitForYuvPipeline
        }
        yuvPipelineStartAttempt = null
        startCamera { accepted -> attempt.settle(accepted) }
      }
    ) { code, message ->
      if (
        yuvPipelineStartAttempt !== attempt ||
        waitGeneration != yuvPipelineWaitGeneration
      ) {
        return@waitForYuvPipeline
      }
      yuvPipelineStartAttempt = null
      cameraStartSettlementFailure = CameraFailure(code, message, null)
      attempt.settle(false)
      if (code != ERROR_OPERATION_CANCELLED) {
        processing = false
        tryStopProcessing()
        stopCameraAsync()
      }
    }
  }

  private fun configureCameraHelper(
    helper: NosmaiCamera2Helper,
    preview: NosmaiPreviewView,
    view: NosmaiCameraView,
    token: Long,
    generation: Long,
    attempt: CameraStartAttempt
  ) {
    helper.setTargetDimensions(targetWidth, targetHeight)
    helper.setFacing(desiredFrontCamera)
    helper.setFlashMode(desiredFlashMode)
    helper.setTorchMode(desiredTorchMode)
    helper.setCameraConfigurationCallback { width, height, sensor, actualFront ->
      runOnMain {
        if (!isCurrentCamera(view, token, generation, helper)) return@runOnMain
        runCatching { preview.setCameraOrientation(actualFront, sensor) }
        runCatching { NosmaiSDK.setCameraFacing(actualFront) }
        runCatching { NosmaiSDK.setMirrorX(desiredMirror) }
        if (attempt.startedWithOes) {
          runCatching {
            preview.setOesInputFrameInfo(
              width,
              height,
              frameRotationMode(sensor, actualFront)
            )
          }
        }
      }
    }
    helper.setCameraReadyCallback {
      runOnMain {
        if (!isCurrentCamera(view, token, generation, helper)) return@runOnMain
        if (!attempt.resultKnown) {
          attempt.readyBeforeResult = true
        } else {
          markCameraReady(
            view,
            preview,
            token,
            generation,
            helper,
            attempt.startedWithOes
          )
        }
      }
    }
    helper.setCameraErrorCallback { code, message, cause ->
      runOnMain {
        if (!isCurrentCamera(view, token, generation, helper)) return@runOnMain
        val failure = CameraFailure(code, message, cause)
        if (!attempt.resultKnown) {
          attempt.pendingFailure = failure
        } else {
          handleCameraFailure(
            view,
            preview,
            token,
            generation,
            helper,
            attempt,
            failure
          )
        }
      }
    }

    if (attempt.startedWithOes) {
      helper.setInputMode(NosmaiCamera2Helper.InputMode.OES)
      helper.setOesPreviewSurfaceTexture(currentOesSurfaceTexture(preview))
      helper.setFrameCallback(null)
      preview.setOnOesFrameProcessedListener {
        onFirstProcessedFrame(view, token, generation)
      }
    } else {
      helper.setInputMode(NosmaiCamera2Helper.InputMode.YUV)
      helper.setFrameCallback { y, u, v, width, height, yStride, uStride,
        vStride, uPixelStride, vPixelStride ->
        if (!isCurrentCameraOffMain(view, token, generation, helper)) {
          return@setFrameCallback
        }
        if (!NosmaiPreviewReadinessAdapter.isReady(preview)) {
          return@setFrameCallback
        }
        preview.processYuvFrame(
          y,
          u,
          v,
          width,
          height,
          yStride,
          uStride,
          vStride,
          uPixelStride,
          vPixelStride,
          frameRotationMode(helper.getSensorOrientation(), helper.isFrontCamera())
        )
        preview.requestRenderUpdate()
        onFirstProcessedFrame(view, token, generation)
      }
    }
  }

  private fun markCameraReady(
    view: NosmaiCameraView,
    preview: NosmaiPreviewView,
    token: Long,
    generation: Long,
    helper: NosmaiCamera2Helper,
    startedWithOes: Boolean
  ) {
    if (!isCurrentCamera(view, token, generation, helper)) return
    cameraStarting = false
    cameraRunning = true
    cameraPaused = false
    cameraStartAttempt = null
    if (startedWithOes && useOesInput) {
      armOesWatchdog(view, preview, token, generation)
    }
  }

  private fun handleCameraFailure(
    view: NosmaiCameraView,
    preview: NosmaiPreviewView,
    token: Long,
    generation: Long,
    helper: NosmaiCamera2Helper,
    attempt: CameraStartAttempt,
    failure: CameraFailure
  ) {
    if (!isCurrentCamera(view, token, generation, helper)) return
    cameraStarting = false
    cameraRunning = false
    cameraStartAttempt = null
    if (attempt.startedWithOes && useOesInput && isOesRecoverableFailure(failure.code)) {
      fallbackToYuv(failure.message)
    } else {
      emitCameraError(failure.code, failure.message, failure.cause)
      stopCameraAsync()
    }
  }

  private fun isOesRecoverableFailure(code: String): Boolean =
    code == NosmaiCamera2Helper.ERROR_OES_SURFACE ||
      code == NosmaiCamera2Helper.ERROR_NO_OUTPUT ||
      code == NosmaiCamera2Helper.ERROR_SESSION_CONFIGURE ||
      code == NosmaiCamera2Helper.ERROR_PREVIEW_START

  private fun restartCamera(onAccepted: ((Boolean) -> Unit)? = null) {
    val callbacks = takePendingCameraStartCallbacks()
    onAccepted?.let(callbacks::add)
    pendingCameraStart = false
    invalidateOesWatchdog()
    stopCameraAsync {
      val stopFailure = cameraStopSettlementFailure
      if (stopFailure != null) {
        cameraStartSettlementFailure = CameraFailure(
          NosmaiCamera2Helper.ERROR_CLOSE_TIMEOUT,
          stopFailure.message ?: "The previous camera did not close cleanly",
          stopFailure
        )
        deliverCameraStartResult(callbacks, false, "Camera restart was rejected")
        return@stopCameraAsync
      }
      if (!processing || cameraPaused || hostPaused || destroyed) {
        cameraStartSettlementFailure = CameraFailure(
          ERROR_OPERATION_CANCELLED,
          "Camera restart was cancelled by the current session state",
          null
        )
        deliverCameraStartResult(callbacks, false, "Camera restart was cancelled")
      } else {
        firstFrameDelivered = false
        startCameraAfterPipelineReady { accepted ->
          deliverCameraStartResult(callbacks, accepted, "Camera restart was rejected")
        }
      }
    }
  }

  private fun stopCameraAsync(after: (() -> Unit)? = null) {
    after?.let(cameraStopWaiters::add)
    if (cameraStopping) return
    cameraStopSettlementFailure = null

    val helper = cameraHelper
    val attempt = cameraStartAttempt
    cameraHelper = null
    cameraStartAttempt = null
    ++cameraGeneration
    val stopGeneration = cameraGeneration
    cameraRunning = false
    cameraStarting = false
    cameraStopping = helper != null
    if (attempt != null) {
      if (helper == null) attempt.settle(false)
      else cameraAttemptsAwaitingStop += attempt
    }
    invalidateOesWatchdog()

    if (helper == null) {
      cameraCloseBarrierHelper?.let {
        cameraStopSettlementFailure = IllegalStateException(
          "The previous camera is still completing a physical close"
        )
      }
      finishCameraStop()
      return
    }

    executeCameraTask(
      onRejected = {
        if (stopGeneration != cameraGeneration || !cameraStopping) {
          return@executeCameraTask
        }
        cameraStopSettlementFailure = IllegalStateException(
          "The camera control executor rejected camera stop"
        )
        cameraCloseBarrierHelper = helper
        finishCameraStop()
      }
    ) {
      try {
        helper.stopCamera { failure, safeToRestart ->
          runOnMain {
            if (safeToRestart && cameraCloseBarrierHelper === helper) {
              cameraCloseBarrierHelper = null
            }
            if (stopGeneration != cameraGeneration || !cameraStopping) {
              return@runOnMain
            }
            if (!safeToRestart) {
              cameraCloseBarrierHelper = helper
            }
            if (failure != null) {
              cameraStopSettlementFailure = failure
              emitCameraError(
                if (failure is InterruptedException) {
                  NosmaiCamera2Helper.ERROR_INTERRUPTED
                } else {
                  NosmaiCamera2Helper.ERROR_CLOSE_TIMEOUT
                },
                failure.message ?: "Camera stop failed unexpectedly",
                failure
              )
            }
            finishCameraStop()
          }
        }
      } catch (error: Throwable) {
        runOnMain {
          if (stopGeneration != cameraGeneration || !cameraStopping) {
            return@runOnMain
          }
          cameraStopSettlementFailure = error
          cameraCloseBarrierHelper = helper
          emitCameraError(
            NosmaiCamera2Helper.ERROR_CLOSE_TIMEOUT,
            error.message ?: "Camera stop failed unexpectedly",
            error
          )
          finishCameraStop()
        }
      }
    }
  }

  private fun finishCameraStop() {
    cameraStopping = false
    val attempts = cameraAttemptsAwaitingStop.toList()
    cameraAttemptsAwaitingStop.clear()
    if (attempts.isNotEmpty()) {
      val stopFailure = cameraStopSettlementFailure
      cameraStartSettlementFailure = if (stopFailure != null) {
        CameraFailure(
          NosmaiCamera2Helper.ERROR_CLOSE_TIMEOUT,
          stopFailure.message ?: "The previous camera did not close cleanly",
          stopFailure
        )
      } else {
        CameraFailure(
          ERROR_OPERATION_CANCELLED,
          "Camera start was cancelled by a stop transition",
          null
        )
      }
    }
    attempts.forEach { it.settle(false) }

    val shouldServicePendingStart = pendingCameraStart
    val pendingCallbacks = pendingCameraStartCallbacks.toList()
    pendingCameraStartCallbacks.clear()
    pendingCameraStart = false

    val waiters = cameraStopWaiters.toList()
    cameraStopWaiters.clear()
    var waiterFailure: Throwable? = null
    waiters.forEach { waiter ->
      runCatching(waiter).onFailure { error ->
        if (waiterFailure == null) waiterFailure = error
        if (cameraStopSettlementFailure == null) cameraStopSettlementFailure = error
        emitError(
          ERROR_PROCESSING_STOP,
          error.message ?: "A camera stop completion failed",
          error
        )
      }
    }

    if (waiterFailure != null && processingStopping) {
      cancelPendingProcessingStarts(
        ERROR_OPERATION_CANCELLED,
        "Camera stop finalization failed"
      )
      completeProcessingStop(waiterFailure, drainQueuedStarts = false)
    }

    val stopFailure = cameraStopSettlementFailure
    if (stopFailure != null) {
      cameraStartSettlementFailure = CameraFailure(
        NosmaiCamera2Helper.ERROR_CLOSE_TIMEOUT,
        stopFailure.message ?: "The previous camera did not close cleanly",
        stopFailure
      )
      pendingCallbacks.forEach { callback -> runCatching { callback(false) } }
      return
    }

    if (!shouldServicePendingStart && pendingCallbacks.isEmpty()) return
    when {
      cameraRunning -> pendingCallbacks.forEach { callback -> runCatching { callback(true) } }
      cameraStarting && cameraStartAttempt != null ->
        pendingCallbacks.forEach { cameraStartAttempt?.addCallback(it) }
      processing &&
        !processingStopping &&
        !cameraPaused &&
        !hostPaused &&
        !cameraStarting &&
        !cameraRunning ->
        startCameraAfterPipelineReady { accepted ->
          deliverCameraStartResult(
            pendingCallbacks,
            accepted,
            "Queued camera start was rejected"
          )
        }
      else -> {
        cameraStartSettlementFailure = CameraFailure(
          ERROR_OPERATION_CANCELLED,
          "Queued camera start was cancelled by a session transition",
          null
        )
        pendingCallbacks.forEach { callback -> runCatching { callback(false) } }
      }
    }
  }

  private fun createAndMountPreview(view: NosmaiCameraView, token: Long) {
    check(retiredPreviewPendingTeardown == null) {
      "A retired native preview is still awaiting teardown"
    }
    val preview = NosmaiPreviewView(view.context)
    previewView = preview
    oesSurfaceTexture = null
    pendingCameraStart = false
    firstFrameDelivered = false
    useOesInput = isOesSafeDevice()

    try {
      preview.enableOesInput(useOesInput)
    } catch (error: Throwable) {
      useOesInput = false
      emitError(ERROR_OES_UNAVAILABLE, "OES input is unavailable; using YUV", error)
      runCatching { preview.enableOesInput(false) }
    }

    preview.addOnOesReadyListener(object : NosmaiPreviewView.OnOesReadyListener {
      override fun onOesReady(surfaceTexture: SurfaceTexture) {
        runOnMain {
          if (!isActivePreview(view, preview, token) || !useOesInput) return@runOnMain
          val isReplacement =
            oesSurfaceTexture != null && oesSurfaceTexture !== surfaceTexture
          oesSurfaceTexture = surfaceTexture
          if (isReplacement && (cameraRunning || cameraStarting)) {
            firstFrameDelivered = false
            view.showTransitionOverlay()
            runCatching { cameraHelper?.quiesceForSurfaceRelease() }
            restartCamera()
          } else if (processing && !cameraPaused && !hostPaused) {
            val callbacks = pendingCameraStartCallbacks.toList()
            pendingCameraStartCallbacks.clear()
            pendingCameraStart = false
            startCamera { accepted ->
              deliverCameraStartResult(
                callbacks,
                accepted,
                "Camera start after OES readiness was rejected"
              )
            }
          }
        }
      }
    })
    preview.addOnOesInputErrorListener { reason ->
      runOnMain {
        if (isActivePreview(view, preview, token)) {
          fallbackToYuv(reason ?: "OES input failed")
        }
      }
    }
    view.mountPreview(preview)
    if (sdkInitialized) initializePreviewPipeline(preview)
  }

  private fun currentOesSurfaceTexture(preview: NosmaiPreviewView): SurfaceTexture? {
    oesSurfaceTexture?.let { return it }
    return runCatching { preview.cameraSurfaceTexture }.getOrNull()?.also {
      oesSurfaceTexture = it
    }
  }

  private fun fallbackToYuv(
    reason: String,
    completion: ((Boolean) -> Unit)? = null
  ) {
    if (!useOesInput || destroyed) {
      cameraStartSettlementFailure = CameraFailure(
        ERROR_OPERATION_CANCELLED,
        "OES fallback was cancelled by a session transition",
        null
      )
      runCatching { completion?.invoke(false) }
      return
    }
    val fallbackSession = sessionGeneration
    val preview = previewView
    val view = activeView
    val token = view?.controllerToken ?: 0L
    val callbacks = takePendingCameraStartCallbacks()
    completion?.let(callbacks::add)
    useOesInput = false
    pendingCameraStart = false
    invalidateOesWatchdog()
    stopCameraAsync {
      runCatching { preview?.setOnOesFrameProcessedListener(null) }
      val disableFailure = runCatching { preview?.enableOesInput(false) }.exceptionOrNull()
      oesSurfaceTexture = null
      runCatching {
        emitError(ERROR_OES_FALLBACK, "OES preview failed; switched to YUV", null, reason)
      }

      val stopFailure = cameraStopSettlementFailure
      if (stopFailure != null || disableFailure != null) {
        val failure = stopFailure ?: disableFailure!!
        cameraStartSettlementFailure = CameraFailure(
          if (stopFailure != null) {
            NosmaiCamera2Helper.ERROR_CLOSE_TIMEOUT
          } else {
            ERROR_OES_UNAVAILABLE
          },
          failure.message ?: "Unable to transition from OES to YUV",
          failure
        )
        processing = false
        tryStopProcessing()
        callbacks.forEach { callback -> runCatching { callback(false) } }
        if (callbacks.isEmpty()) {
          emitCameraError(
            cameraStartSettlementFailure!!.code,
            cameraStartSettlementFailure!!.message,
            failure
          )
        }
        return@stopCameraAsync
      }

      if (
        fallbackSession != sessionGeneration ||
        view == null ||
        preview == null ||
        !isActivePreview(view, preview, token) ||
        !processing ||
        processingStopping ||
        cameraPaused ||
        hostPaused
      ) {
        cameraStartSettlementFailure = CameraFailure(
          ERROR_OPERATION_CANCELLED,
          "OES fallback was cancelled by a session transition",
          null
        )
        callbacks.forEach { callback -> runCatching { callback(false) } }
        return@stopCameraAsync
      }

      firstFrameDelivered = false
      val overlayFailure = runCatching { view.showTransitionOverlay() }.exceptionOrNull()
      if (overlayFailure != null) {
        cameraStartSettlementFailure = CameraFailure(
          ERROR_CAMERA_START,
          overlayFailure.message ?: "Unable to prepare the YUV preview",
          overlayFailure
        )
        processing = false
        tryStopProcessing()
        deliverCameraStartResult(callbacks, false, "Unable to prepare the YUV preview")
        return@stopCameraAsync
      }
      startCameraAfterPipelineReady { accepted ->
        deliverCameraStartResult(callbacks, accepted, "YUV fallback was rejected")
      }
    }
  }

  private fun waitForYuvPipeline(
    view: NosmaiCameraView,
    preview: NosmaiPreviewView,
    token: Long,
    expectedSession: Long,
    expectedWaitGeneration: Long,
    onReady: () -> Unit,
    onFailure: (String, String) -> Unit
  ) {
    val deadline = SystemClock.uptimeMillis() + PIPELINE_READY_TIMEOUT_MS
    val poll = object : Runnable {
      override fun run() {
        if (expectedWaitGeneration != yuvPipelineWaitGeneration) {
          return
        }
        if (
          expectedSession != sessionGeneration ||
          !isActivePreview(view, preview, token) ||
          !processing ||
          processingStopping ||
          cameraPaused ||
          hostPaused
        ) {
          onFailure(
            ERROR_OPERATION_CANCELLED,
            "The YUV pipeline wait was cancelled by a session transition"
          )
          return
        }
        if (NosmaiPreviewReadinessAdapter.isReady(preview)) {
          onReady()
        } else if (SystemClock.uptimeMillis() >= deadline) {
          onFailure(
            ERROR_PIPELINE_NOT_READY,
            "The Nosmai YUV pipeline did not become ready in time"
          )
        } else {
          mainHandler.postDelayed(this, PIPELINE_READY_POLL_MS)
        }
      }
    }
    mainHandler.post(poll)
  }

  private fun armOesWatchdog(
    view: NosmaiCameraView,
    preview: NosmaiPreviewView,
    token: Long,
    cameraToken: Long
  ) {
    val watchdog = ++oesWatchdogGeneration
    mainHandler.postDelayed({
      if (
        watchdog == oesWatchdogGeneration &&
        useOesInput &&
        isActivePreview(view, preview, token) &&
        cameraToken == cameraGeneration &&
        processing &&
        cameraRunning &&
        !cameraPaused &&
        !switchingCamera &&
        !firstFrameDelivered
      ) {
        fallbackToYuv("No processed OES frame arrived within ${OES_WATCHDOG_MS}ms")
      }
    }, OES_WATCHDOG_MS)
  }

  private fun armOesSurfaceWatchdog(
    view: NosmaiCameraView,
    preview: NosmaiPreviewView,
    token: Long
  ) {
    val watchdog = ++oesWatchdogGeneration
    mainHandler.postDelayed({
      if (
        watchdog == oesWatchdogGeneration &&
        useOesInput &&
        pendingCameraStart &&
        isActivePreview(view, preview, token) &&
        processing &&
        !cameraPaused &&
        !hostPaused &&
        oesSurfaceTexture == null
      ) {
        fallbackToYuv("OES SurfaceTexture was not ready within ${OES_WATCHDOG_MS}ms")
      }
    }, OES_WATCHDOG_MS)
  }

  private fun armCameraStartWatchdog(
    view: NosmaiCameraView,
    token: Long,
    generation: Long,
    helper: NosmaiCamera2Helper
  ) {
    mainHandler.postDelayed({
      if (
        isCurrentCamera(view, token, generation, helper) &&
        processing &&
        cameraStarting &&
        !cameraRunning
      ) {
        cameraStarting = false
        emitCameraError(
          ERROR_CAMERA_START,
          "Camera did not become ready within ${CAMERA_START_WATCHDOG_MS}ms"
        )
        stopCameraAsync()
      }
    }, CAMERA_START_WATCHDOG_MS)
  }

  private fun onFirstProcessedFrame(
    view: NosmaiCameraView,
    token: Long,
    generation: Long
  ) {
    runOnMain {
      if (
        firstFrameDelivered ||
        !isActiveView(view, token) ||
        generation != cameraGeneration ||
        !processing ||
        !cameraRunning
      ) {
        return@runOnMain
      }
      firstFrameDelivered = true
      invalidateOesWatchdog()
      view.hideTransitionOverlay()
      view.dispatchCameraReady()
    }
  }

  private fun initializePreviewPipeline(preview: NosmaiPreviewView) {
    preview.initializePipeline()
    if (hostPaused || cameraPaused) {
      preview.onPause()
    } else {
      preview.onResume()
    }
  }

  private fun applyCameraPresentation() {
    if (!sdkInitialized) return
    runCatching { NosmaiSDK.setCameraFacing(desiredFrontCamera) }
    runCatching { NosmaiSDK.setMirrorX(desiredMirror) }
  }

  private fun canApplyEffect(): Boolean =
    sdkInitialized &&
      processing &&
      !processingStopping &&
      !cleanupInProgress &&
      !cameraPaused &&
      !hostPaused

  private fun visualAdmissionFailure(
    capability: NosmaiVisualCapability
  ): NosmaiEffectOperationResult? {
    if (!canApplyEffect()) {
      return NosmaiEffectOperationResult(
        code = ERROR_INVALID_STATE,
        message =
          "Start processing and keep the app active before using visual controls"
      )
    }

    val pipelineReady = try {
      NosmaiSDK.isPipelineReadyFlag()
    } catch (error: Throwable) {
      return visualOperationFailure(
        error,
        NosmaiAndroidVisualEffects.ERROR_VISUAL_CONTROL,
        "Unable to inspect the native visual pipeline"
      )
    }
    if (!pipelineReady) {
      return NosmaiEffectOperationResult(
        code = ERROR_PIPELINE_NOT_READY,
        message = "The native visual pipeline is not ready"
      )
    }

    return try {
      when (capability) {
        NosmaiVisualCapability.NONE -> null
        NosmaiVisualCapability.BEAUTY -> {
          if (visualEffects.isBeautyEffectEnabled()) {
            null
          } else {
            NosmaiEffectOperationResult(
              code = NosmaiAndroidVisualEffects.ERROR_BEAUTY_DISABLED,
              message = "Beauty effects are not enabled by this Nosmai license"
            )
          }
        }
        NosmaiVisualCapability.ADVANCED -> {
          if (visualEffects.isAdvancedFiltersEnabled()) {
            null
          } else {
            NosmaiEffectOperationResult(
              code = NosmaiAndroidVisualEffects.ERROR_ADVANCED_FILTERS_DISABLED,
              message = "Advanced filters are not enabled by this Nosmai license"
            )
          }
        }
      }
    } catch (error: Throwable) {
      visualOperationFailure(
        error,
        NosmaiAndroidVisualEffects.ERROR_VISUAL_CONTROL,
        "Unable to read the licensed visual capabilities"
      )
    }
  }

  private fun enqueueVisualMutation(
    owner: NosmaiModuleEventSink,
    promise: Promise,
    capability: NosmaiVisualCapability = NosmaiVisualCapability.NONE,
    condition: (NosmaiPipelineState) -> Boolean = { true },
    mutation: () -> Unit
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      visualAdmissionFailure(capability)?.let { failure ->
        rejectVisualPreflight(promise, failure)
        return@runOnMain
      }

      enqueueEffectOperation(owner, promise) { finish ->
        visualAdmissionFailure(capability)?.let { failure ->
          finish(failure)
          return@enqueueEffectOperation
        }
        try {
          mutation()
          settleVisualMutation(owner, finish, condition)
        } catch (error: Throwable) {
          finish(
            visualOperationFailure(
              error,
              NosmaiAndroidVisualEffects.ERROR_VISUAL_CONTROL,
              "Unable to update the visual controls"
            )
          )
        }
      }
    }
  }

  private fun enqueueVisualQuery(
    owner: NosmaiModuleEventSink,
    promise: Promise,
    query: () -> Any?
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      visualAdmissionFailure(NosmaiVisualCapability.NONE)?.let { failure ->
        rejectVisualPreflight(promise, failure)
        return@runOnMain
      }

      enqueueEffectOperation(owner, promise) { finish ->
        visualAdmissionFailure(NosmaiVisualCapability.NONE)?.let { failure ->
          finish(failure)
          return@enqueueEffectOperation
        }
        try {
          finish(NosmaiEffectOperationResult(value = query()))
        } catch (error: Throwable) {
          finish(
            visualOperationFailure(
              error,
              NosmaiAndroidVisualEffects.ERROR_VISUAL_CONTROL,
              "Unable to read the visual-control state"
            )
          )
        }
      }
    }
  }

  private fun settleVisualMutation(
    owner: NosmaiModuleEventSink,
    finish: NosmaiEffectOperationCompletion,
    condition: (NosmaiPipelineState) -> Boolean = { true }
  ) {
    fenceEffectMutationQueues(previewView) { fenceFailure ->
      if (fenceFailure != null) {
        effectPipelinePoisoned = true
        finish(
          NosmaiEffectOperationResult(
            code = NosmaiAndroidVisualEffects.ERROR_VISUAL_CONTROL,
            message = fenceFailure.message
              ?: "The native visual mutation queues did not settle",
            cause = fenceFailure.cause
          )
        )
        return@fenceEffectMutationQueues
      }

      try {
        visualEffects.reconcileBuiltInState()
        val state = NosmaiEffects.getCurrentPipelineState()
          ?: throw IllegalStateException(
            "The native visual pipeline returned no active state"
          )
        val writableState = NosmaiStateMapper.toWritableMap(state)
        runCatching {
          if (isModuleOwner(owner)) {
            owner.onActiveEffectsChanged(writableState)
          } else {
            moduleSink?.onActiveEffectsChanged(writableState)
          }
        }
        if (!condition(state)) {
          finish(
            NosmaiEffectOperationResult(
              code = NosmaiAndroidVisualEffects.ERROR_VISUAL_CONTROL,
              message = "The native visual pipeline did not accept the requested state"
            )
          )
        } else {
          finish(NosmaiEffectOperationResult())
        }
      } catch (error: Throwable) {
        finish(
          visualOperationFailure(
            error,
            NosmaiAndroidVisualEffects.ERROR_VISUAL_CONTROL,
            "Unable to publish the updated visual state"
          )
        )
      }
    }
  }

  private fun rejectVisualPreflight(
    promise: Promise,
    failure: NosmaiEffectOperationResult
  ) {
    reject(
      promise,
      failure.code ?: NosmaiAndroidVisualEffects.ERROR_VISUAL_CONTROL,
      failure.message ?: "The native visual operation was rejected",
      failure.cause
    )
  }

  private fun visualOperationFailure(
    error: Throwable,
    fallbackCode: String,
    fallbackMessage: String
  ): NosmaiEffectOperationResult {
    val visualError = error as? NosmaiVisualException
    return NosmaiEffectOperationResult(
      code = visualError?.code ?: fallbackCode,
      message = error.message ?: fallbackMessage,
      cause = error
    )
  }

  private fun effectParameterFailure(
    error: Throwable,
    fallbackMessage: String
  ): NosmaiEffectOperationResult = NosmaiEffectOperationResult(
    code = ERROR_EFFECT_PARAMETER,
    message = error.message ?: fallbackMessage,
    cause = error
  )

  private fun setCameraLightMode(
    owner: NosmaiModuleEventSink,
    mode: String,
    torch: Boolean,
    promise: Promise
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      val supportedModes = if (torch) SUPPORTED_TORCH_MODES else SUPPORTED_LIGHT_MODES
      if (mode !in supportedModes) {
        reject(
          promise,
          ERROR_ARGUMENT,
          if (torch) "torch mode must be off or on" else "flash mode must be off, on, or auto"
        )
        return@runOnMain
      }
      if (mode != LIGHT_MODE_OFF && !cameraLightCapability(torch)) {
        promise.resolve(false)
        return@runOnMain
      }

      val helper = cameraHelper
      val accepted = if (helper == null) {
        true
      } else if (torch) {
        helper.setTorchMode(mode)
      } else {
        helper.setFlashMode(mode)
      }
      if (accepted) {
        if (torch) desiredTorchMode = mode else desiredFlashMode = mode
      }
      promise.resolve(accepted)
    }
  }

  private fun cameraLightCapability(torch: Boolean): Boolean {
    val helper = cameraHelper
    if (helper != null) {
      return if (torch) helper.hasTorch() else helper.hasFlash()
    }
    val context = reactContext ?: return false
    return runCatching {
      val probe = NosmaiCamera2Helper(context.applicationContext, desiredFrontCamera)
      if (torch) probe.hasTorch() else probe.hasFlash()
    }.getOrDefault(false)
  }

  private fun resetCameraLightModes() {
    desiredFlashMode = LIGHT_MODE_OFF
    desiredTorchMode = LIGHT_MODE_OFF
    cameraHelper?.let { helper ->
      runCatching { helper.setFlashMode(LIGHT_MODE_OFF) }
      runCatching { helper.setTorchMode(LIGHT_MODE_OFF) }
    }
  }

  private fun normalizedEffectParameterName(value: String): String? {
    if (
      value.length > MAX_EFFECT_PARAMETER_NAME_LENGTH ||
      value.any { it.code < 0x20 || it.code == 0x7f }
    ) {
      return null
    }
    val name = value.trim()
    if (name.isEmpty()) {
      return null
    }
    return name
  }

  private fun isSafeEffectParameterString(value: String): Boolean =
    value.length <= MAX_EFFECT_PARAMETER_STRING_LENGTH &&
      value.none { it.code < 0x20 || it.code == 0x7f }

  private fun enqueueEffectOperation(
    owner: NosmaiModuleEventSink,
    promise: Promise,
    operation: NosmaiEffectOperation
  ) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "Effect operations must be enqueued on the main looper"
    }
    val queuedOperation = queuedOperation@{
      var settled = false
      val watchdog = Runnable {
        if (settled) return@Runnable
        effectPipelinePoisoned = true
        settled = true
        try {
          reject(
            promise,
            ERROR_CLEANUP,
            "The native effect operation did not complete in time; retry cleanup"
          )
        } finally {
          finishActiveEffectOperation()
        }
      }
      val finish: NosmaiEffectOperationCompletion = { result ->
        runOnMain finishOnMain@{
          if (settled) return@finishOnMain
          settled = true
          mainHandler.removeCallbacks(watchdog)
          try {
            if (result.code == null) {
              promise.resolve(result.value)
            } else {
              reject(
                promise,
                result.code,
                result.message ?: "The native effect operation failed",
                result.cause
              )
            }
          } finally {
            finishActiveEffectOperation()
          }
        }
      }

      if (!isModuleOwner(owner)) {
        finish(
          NosmaiEffectOperationResult(
            code = ERROR_OPERATION_CANCELLED,
            message = "The effect operation belongs to a retired React Native module"
          )
        )
        return@queuedOperation
      }
      if (cleanupInProgress) {
        finish(
          NosmaiEffectOperationResult(
            code = ERROR_OPERATION_CANCELLED,
            message = "The effect operation was cancelled by session cleanup"
          )
        )
        return@queuedOperation
      }
      if (effectPipelinePoisoned || teardownPoisoned) {
        finish(
          NosmaiEffectOperationResult(
            code = ERROR_CLEANUP,
            message = "The effect pipeline requires cleanup before more mutations"
          )
        )
        return@queuedOperation
      }

      mainHandler.postDelayed(watchdog, EFFECT_OPERATION_TIMEOUT_MS)
      try {
        operation(finish)
      } catch (error: Throwable) {
        finish(
          NosmaiEffectOperationResult(
            code = ERROR_EFFECT_STATE,
            message = error.message ?: "The native effect operation failed",
            cause = error
          )
        )
      }
    }
    effectOperations.addLast(queuedOperation)
    runNextEffectOperation()
  }

  private fun runNextEffectOperation() {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "Effect operations must run on the main looper"
    }
    if (effectOperationActive) return

    val operation = effectOperations.removeFirstOrNull()
    if (operation != null) {
      effectOperationActive = true
      operation()
      return
    }

    val callbacks = effectDrainCallbacks.toList()
    effectDrainCallbacks.clear()
    callbacks.forEach { callback -> runCatching(callback) }
  }

  private fun finishActiveEffectOperation() {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "Effect operations must finish on the main looper"
    }
    if (!effectOperationActive) return
    effectOperationActive = false
    runNextEffectOperation()
  }

  private fun whenEffectOperationsDrained(completion: () -> Unit) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "Effect drain barriers must run on the main looper"
    }
    if (!effectOperationActive && effectOperations.isEmpty()) {
      completion()
    } else {
      effectDrainCallbacks += completion
    }
  }

  private fun clearEffectSlot(
    slot: NosmaiEffectMutationAdapter.Slot,
    errorCode: String,
    finish: NosmaiEffectOperationCompletion,
    result: Any?,
    condition: (NosmaiPipelineState) -> Boolean
  ) {
    clearNativeEffectSlot(slot) { errorMessage, cause ->
      if (errorMessage != null) {
        finish(
          NosmaiEffectOperationResult(
            code = errorCode,
            message = errorMessage,
            cause = cause
          )
        )
        return@clearNativeEffectSlot
      }
      waitForEffectPipelineCondition(condition) { failure ->
        if (failure == null) {
          finish(NosmaiEffectOperationResult(value = result))
        } else {
          finish(
            NosmaiEffectOperationResult(
              code = errorCode,
              message = failure.message,
              cause = failure.cause
            )
          )
        }
      }
    }
  }

  /**
   * The SDK's private scoped-clear callback is emitted before its transition
   * monitor is released. Do not advance the bridge FIFO until both the callback
   * and the SDK transition have completed.
   */
  private fun clearNativeEffectSlot(
    slot: NosmaiEffectMutationAdapter.Slot,
    completion: (String?, Throwable?) -> Unit
  ) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "Native effect slots must be cleared from the main looper"
    }
    var settled = false
    val deadline = SystemClock.uptimeMillis() + EFFECT_SLOT_TIMEOUT_MS
    lateinit var timeout: Runnable

    fun finish(errorMessage: String?, cause: Throwable? = null) {
      if (settled) return
      settled = true
      mainHandler.removeCallbacks(timeout)
      completion(errorMessage, cause)
    }

    timeout = Runnable {
      if (settled) return@Runnable
      effectPipelinePoisoned = true
      finish("The native scoped effect clear did not complete in time")
    }
    mainHandler.postDelayed(timeout, EFFECT_SLOT_TIMEOUT_MS)

    try {
      effectMutationAdapter.clearSlot(slot) { callbackError ->
        runOnMain callbackOnMain@{
          if (settled) return@callbackOnMain
          val poll = object : Runnable {
            override fun run() {
              if (settled) return
              try {
                if (effectMutationAdapter.isTransitionIdle()) {
                  finish(callbackError)
                } else if (SystemClock.uptimeMillis() >= deadline) {
                  effectPipelinePoisoned = true
                  finish("The native effect transition did not become idle in time")
                } else {
                  mainHandler.postDelayed(this, EFFECT_TRANSITION_POLL_MS)
                }
              } catch (error: Throwable) {
                effectPipelinePoisoned = true
                finish(
                  error.message ?: "Unable to inspect the native effect transition",
                  error
                )
              }
            }
          }
          mainHandler.post(poll)
        }
      }
    } catch (error: Throwable) {
      effectPipelinePoisoned = true
      finish(
        error.message ?: "Unable to clear the native effect slot",
        error
      )
    }
  }

  private fun clearAllEffectState(
    completion: NosmaiEffectOperationCompletion,
    result: Any?,
    fencePreview: NosmaiPreviewView? = previewView
  ) {
    var settled = false
    val timeout = Runnable {
      if (settled) return@Runnable
      settled = true
      effectPipelinePoisoned = true
      completion(
        NosmaiEffectOperationResult(
          code = ERROR_EFFECT_CLEAR,
          message = "The native full effect cleanup did not complete in time"
        )
      )
    }
    fun finish(resultValue: NosmaiEffectOperationResult) {
      if (settled) return
      settled = true
      mainHandler.removeCallbacks(timeout)
      completion(resultValue)
    }
    mainHandler.postDelayed(timeout, EFFECT_CLEAR_ALL_TIMEOUT_MS)

    val slots = arrayOf(
      NosmaiEffectMutationAdapter.Slot.COLOR,
      NosmaiEffectMutationAdapter.Slot.AR,
      NosmaiEffectMutationAdapter.Slot.BACKGROUND
    )

    fun clearNext(index: Int) {
      if (settled) return
      if (index < slots.size) {
        clearNativeEffectSlot(slots[index]) { errorMessage, cause ->
          if (errorMessage != null) {
            finish(
              NosmaiEffectOperationResult(
                code = ERROR_EFFECT_CLEAR,
                message = errorMessage,
                cause = cause
              )
            )
          } else {
            clearNext(index + 1)
          }
        }
        return
      }

      val accepted = try {
        NosmaiSDK.clearAllEffects()
      } catch (error: Throwable) {
        finish(
          NosmaiEffectOperationResult(
            code = ERROR_EFFECT_CLEAR,
            message = error.message ?: "Unable to clear the Nosmai pipeline",
            cause = error
          )
        )
        return
      }
      if (!accepted) {
        finish(
          NosmaiEffectOperationResult(
            code = ERROR_EFFECT_CLEAR,
            message = "The SDK rejected clearAll"
          )
        )
        return
      }

      // SDK 3.0.x exposes no clearAll completion. Its single-thread executor
      // performs package/background work which can post main-thread tasks that
      // enqueue final GL cleanup. Fence in that exact order before inspecting
      // state or allowing processing teardown.
      fenceEffectMutationQueues(fencePreview) { fenceFailure ->
        if (settled) return@fenceEffectMutationQueues
        if (fenceFailure != null) {
          effectPipelinePoisoned = true
          finish(
            NosmaiEffectOperationResult(
              code = ERROR_EFFECT_CLEAR,
              message = fenceFailure.message,
              cause = fenceFailure.cause
            )
          )
          return@fenceEffectMutationQueues
        }
        waitForEffectPipelineCondition(::isEffectPipelineEmpty) { failure ->
          if (settled) return@waitForEffectPipelineCondition
          if (failure == null) {
            effectPipelinePoisoned = false
            visualEffects.resetTracking()
            finish(NosmaiEffectOperationResult(value = result))
          } else {
            effectPipelinePoisoned = true
            finish(
              NosmaiEffectOperationResult(
                code = ERROR_EFFECT_CLEAR,
                message = failure.message,
                cause = failure.cause
              )
            )
          }
        }
      }
    }

    clearNext(0)
  }

  private fun fenceEffectMutationQueues(
    preview: NosmaiPreviewView?,
    completion: (NosmaiEffectOperationResult?) -> Unit
  ) {
    var settled = false
    var executorFencePending = true
    lateinit var executorTimeout: Runnable
    var glTimeout: Runnable? = null

    fun complete(result: NosmaiEffectOperationResult?) {
      if (settled) return
      settled = true
      mainHandler.removeCallbacks(executorTimeout)
      glTimeout?.let(mainHandler::removeCallbacks)
      completion(result)
    }

    fun finish(result: NosmaiEffectOperationResult?) {
      runOnMain finishOnMain@{
        if (settled) return@finishOnMain
        complete(result)
      }
    }

    executorTimeout = Runnable {
      if (!settled && executorFencePending) {
        finish(
          NosmaiEffectOperationResult(
            message = "The native effects executor did not drain in time"
          )
        )
      }
    }
    mainHandler.postDelayed(executorTimeout, EFFECT_EXECUTOR_FENCE_TIMEOUT_MS)
    effectMutationAdapter.fenceBackgroundExecutor { executorError ->
      runOnMain executorFenceOnMain@{
        if (settled) return@executorFenceOnMain
        executorFencePending = false
        mainHandler.removeCallbacks(executorTimeout)
        if (executorError != null) {
          finish(NosmaiEffectOperationResult(message = executorError))
          return@executorFenceOnMain
        }

        if (preview == null) {
          finish(null)
          return@executorFenceOnMain
        }

        try {
          glTimeout = Runnable {
            finish(
              NosmaiEffectOperationResult(
                message = "The native GL cleanup queue did not drain in time"
              )
            )
          }
          mainHandler.postDelayed(glTimeout!!, EFFECT_GL_FENCE_TIMEOUT_MS)
          effectMutationAdapter.fenceGlQueue(preview) { glError ->
            if (glError == null) {
              finish(null)
            } else {
              finish(NosmaiEffectOperationResult(message = glError))
            }
          }
        } catch (error: Throwable) {
          finish(
            NosmaiEffectOperationResult(
              message = error.message ?: "Unable to fence the native GL cleanup queue",
              cause = error
            )
          )
        }
      }
    }
  }

  private fun isEffectPipelineEmpty(state: NosmaiPipelineState): Boolean =
    state.activeFilterPath.isNullOrBlank() &&
      state.activeEffectPath.isNullOrBlank() &&
      state.activeBackgroundPackagePath.isNullOrBlank() &&
      state.activeBackgroundConfig == null &&
      !runCatching { NosmaiEffectsEngine.hasActiveBeautyFilters() }
        .getOrDefault(false)

  private fun clearScopedEffect(
    owner: NosmaiModuleEventSink,
    promise: Promise,
    slot: String,
    condition: (NosmaiPipelineState) -> Boolean
  ) {
    runOnMain {
      if (!ensureModuleOwner(owner, promise)) return@runOnMain
      if (!ensureInitialized(promise)) return@runOnMain
      enqueueEffectOperation(owner, promise) { finish ->
        clearEffectSlot(
          if (slot == "filter") {
            NosmaiEffectMutationAdapter.Slot.COLOR
          } else {
            NosmaiEffectMutationAdapter.Slot.AR
          },
          ERROR_EFFECT_CLEAR,
          finish,
          null,
          condition
        )
      }
    }
  }

  private fun waitForEffectPipelineCondition(
    condition: (NosmaiPipelineState) -> Boolean,
    completion: (NosmaiEffectOperationResult?) -> Unit
  ) {
    val deadline = SystemClock.uptimeMillis() + EFFECT_STATE_TIMEOUT_MS
    val poll = object : Runnable {
      override fun run() {
        try {
          val state = NosmaiEffects.getCurrentPipelineState()
          if (state != null && condition(state)) {
            completion(null)
          } else if (SystemClock.uptimeMillis() >= deadline) {
            completion(
              NosmaiEffectOperationResult(
                message = "The native pipeline did not reach the requested state in time"
              )
            )
          } else {
            mainHandler.postDelayed(this, EFFECT_STATE_POLL_MS)
          }
        } catch (error: Throwable) {
          completion(
            NosmaiEffectOperationResult(
              message = error.message ?: "Unable to read the native pipeline state",
              cause = error
            )
          )
        }
      }
    }
    mainHandler.post(poll)
  }

  private fun cleanupSession(after: ((List<Throwable>) -> Unit)? = null) {
    if (mediaRecorder.isBusy()) {
      stopRecordingForTeardown("Recording was interrupted by session cleanup") {
        cleanupSession(after)
      }
      return
    }
    frameStream.stop()
    val requiresNativeTeardown = requiresNativeSessionTeardown()
    val transition = ++transitionGeneration
    ++cameraConfigurationGeneration
    ++sessionGeneration
    processing = false
    processingStopping = true
    cameraPaused = false
    pendingCameraStart = false
    resumeAfterHost = false
    resumeAfterViewReplacement = false
    resumeReplacementCameraPaused = false
    cameraStartSettlementFailure = CameraFailure(
      ERROR_OPERATION_CANCELLED,
      "Camera start was cancelled by session cleanup",
      null
    )
    cameraStartAttempt?.settle(false)
    cancelYuvPipelineStart("Session cleanup cancelled the pending camera start")
    cancelPendingCameraStartCallbacks()
    cancelPendingProcessingStarts(
      ERROR_OPERATION_CANCELLED,
      "The queued processing start was cancelled by cleanup"
    )
    invalidateOesWatchdog()
    val retiredPreview = previewView ?: retiredPreviewPendingTeardown
    retainPreviewForTeardown(retiredPreview)
    // Cleanup may be requested while the host is paused. Keep the GL worker
    // alive just long enough for an already-entered native mutation and the
    // fenced clear to release their GPU resources; Camera2 admission is closed.
    runCatching { retiredPreview?.onResume() }
    val failures = mutableListOf<Throwable>()

    if (!requiresNativeTeardown) {
      unregisterGameEventListener()
      stopCameraAsync {
        cameraStopSettlementFailure?.let(failures::add)
        runCatching { unregisterPipelineStateListener() }
          .exceptionOrNull()?.let(failures::add)
        ++licenseGeneration
        runCatching { licenseAdapter.clear() }
          .exceptionOrNull()?.let(failures::add)
        visualEffects.resetTracking()
        sdkInitialized = false
        completeProcessingStop(failures.firstOrNull(), drainQueuedStarts = false)
        releaseRetiredPreview(retiredPreview)
        runCatching { after?.invoke(failures) }.onFailure { error ->
          completeCleanup(failures + error)
        }
      }
      return
    }

    // Native apply cannot be cancelled safely. Let the active native operation
    // settle while queued operations revalidate cleanup state and cancel before
    // native entry. Then run a transition/executor/main/GL-fenced full clear
    // before stopping the render pipeline.
    whenEffectOperationsDrained {
      clearAllEffectState(
        completion = { clearResult ->
          runOnMain {
            val effectCleanupSucceeded = clearResult.code == null
            if (clearResult.code != null) {
              effectPipelinePoisoned = true
              failures += clearResult.cause ?: IllegalStateException(
                clearResult.message ?: "Unable to clear the native effect pipeline"
              )
            } else {
              effectPipelinePoisoned = false
            }

            stopCameraAsync stopCamera@{
              if (transition != transitionGeneration) {
                failures += IllegalStateException(
                  "Cleanup was superseded by a newer session"
                )
                runCatching { after?.invoke(failures) }.onFailure { error ->
                  completeCleanup(failures + error)
                }
                return@stopCamera
              }
              val nativeTeardownFailureCountBefore = failures.size
              cameraStopSettlementFailure?.let(failures::add)
              runCatching { NosmaiSDK.stopProcessing() }
                .exceptionOrNull()?.let(failures::add)
              runCatching { retiredPreview?.setOnOesFrameProcessedListener(null) }
                .exceptionOrNull()?.let(failures::add)
              runCatching { retiredPreview?.onPause() }
                .exceptionOrNull()?.let(failures::add)
              runCatching { unregisterPipelineStateListener() }
                .exceptionOrNull()?.let(failures::add)
              // Keep the retired GL preview as the retry fence whenever any
              // native camera/render teardown step fails. Releasing it after a
              // partial teardown would make a later cleanup unable to fence the
              // original pipeline safely.
              if (
                effectCleanupSucceeded &&
                failures.size == nativeTeardownFailureCountBefore
              ) {
                releaseRetiredPreview(retiredPreview)
              }
              ++licenseGeneration
              runCatching { licenseAdapter.clear() }
                .exceptionOrNull()?.let(failures::add)
              unregisterGameEventListener()
              sdkInitialized = false
              completeProcessingStop(failures.firstOrNull(), drainQueuedStarts = false)
              runCatching { activeView?.showTransitionOverlay() }
                .exceptionOrNull()?.let(failures::add)
              runCatching { after?.invoke(failures) }.onFailure { error ->
                completeCleanup(failures + error)
              }
            }
          }
        },
        result = null,
        fencePreview = retiredPreview
      )
    }
  }

  private fun completeCleanup(failures: List<Throwable>) {
    val resolvedFailures = failures.toMutableList()
    if (mediaRecorder.isNativeStateUncertain() && resolvedFailures.isEmpty()) {
      resolvedFailures += IllegalStateException(
        "The native recorder did not settle; restart the host process before using the camera again"
      )
    }
    teardownPoisoned = resolvedFailures.isNotEmpty()
    val retiringContext = contextRetirementPending
    if (retiringContext) {
      activeView?.controllerToken = 0L
      activeView = null
      previewView = null
    }

    val nextContext = if (retiringContext) pendingReactContextAfterCleanup else null
    val nextModule = if (
      nextContext != null &&
      pendingModuleSinkAfterCleanup?.nosmaiReactContext === nextContext &&
      pendingModuleSinkAfterCleanup?.nosmaiOwnerValid == true
    ) {
      pendingModuleSinkAfterCleanup
    } else {
      null
    }

    if (retiringContext) {
      pendingReactContextAfterCleanup = null
      pendingModuleSinkAfterCleanup = null
      contextRetirementPending = false
      reactContext = nextContext
      if (!destroyed && nextContext != null) {
        val listenerGeneration = ++contextGeneration
        lifecycleListener = createLifecycleListener(listenerGeneration).also {
          nextContext.addLifecycleEventListener(it)
        }
        hostPaused = nextContext.lifecycleState != LifecycleState.RESUMED
        moduleSink = nextModule
      } else {
        lifecycleListener = null
        hostPaused = true
        moduleSink = null
      }
    }

    cleanupInProgress = false
    pendingViewAfterCleanup?.takeIf { pendingView ->
      !retiringContext || (nextContext != null && pendingView.belongsTo(nextContext))
    }?.let { pendingView ->
      activeView?.takeIf { it !== pendingView }?.controllerToken = 0L
      activeView = pendingView
      pendingViewAfterCleanup = null
      previewView = null
      desiredFrontCamera = pendingView.cameraPosition != "back"
      desiredMirror = pendingView.mirrorPreview
      pendingView.showTransitionOverlay()
    }
    if (pendingViewAfterCleanup != null && retiringContext) {
      pendingViewAfterCleanup?.controllerToken = 0L
      pendingViewAfterCleanup = null
    }
    val promises = cleanupPromises.toList()
    cleanupPromises.clear()
    promises.forEach { promise ->
      runCatching {
        if (resolvedFailures.isEmpty()) {
          promise.resolve(null)
        } else {
          reject(
            promise,
            ERROR_CLEANUP,
            "Session cleanup completed with ${resolvedFailures.size} native failure(s)",
            resolvedFailures.first()
          )
        }
      }
    }

    val queuedInitialization = pendingInitialization
    pendingInitialization = null
    if (
      queuedInitialization != null &&
      resolvedFailures.isEmpty() &&
      !destroyed &&
      moduleSink === queuedInitialization.owner &&
      queuedInitialization.owner.nosmaiOwnerValid
    ) {
      queuedInitialization.promises.forEach { queuedPromise ->
        initializeSdk(
          queuedInitialization.owner,
          queuedInitialization.licenseKey,
          queuedPromise
        )
      }
    } else if (queuedInitialization != null) {
      queuedInitialization.promises.forEach { queuedPromise ->
        if (resolvedFailures.isEmpty()) {
          reject(
            queuedPromise,
            ERROR_OPERATION_CANCELLED,
            "React Native context activation was cancelled"
          )
        } else {
          reject(
            queuedPromise,
            ERROR_CLEANUP,
            "React Native context activation requires a successful cleanup retry",
            resolvedFailures.first()
          )
        }
      }
    }

    if (destroyed) {
      cloudFilters.shutdown()
      mediaCapture.shutdown()
      mediaGallery.shutdown()
      mediaRecorder.shutdown()
      visualEffects.shutdown()
      cameraExecutor.shutdown()
    }
  }

  private fun registerPipelineStateListener() {
    if (pipelineListenerRegistered) return
    val listenerGeneration = ++pipelineListenerGeneration
    val listener = NosmaiEffectsEngine.PipelineStateListener { state ->
      runOnMain {
        if (
          !destroyed &&
          pipelineListenerRegistered &&
          listenerGeneration == pipelineListenerGeneration
        ) {
          runCatching {
            moduleSink?.onActiveEffectsChanged(NosmaiStateMapper.toWritableMap(state))
          }
        }
      }
    }
    pipelineStateListener = listener
    NosmaiEffects.addPipelineStateListener(listener)
    pipelineListenerRegistered = true
  }

  private fun unregisterPipelineStateListener() {
    ++pipelineListenerGeneration
    val listener = pipelineStateListener
    val removalFailure = if (pipelineListenerRegistered && listener != null) {
      runCatching { NosmaiEffects.removePipelineStateListener(listener) }
        .exceptionOrNull()
    } else {
      null
    }
    pipelineStateListener = null
    pipelineListenerRegistered = false
    removalFailure?.let { throw it }
  }

  private fun tryStopProcessing() {
    runCatching { NosmaiSDK.stopProcessing() }
  }

  private fun cancelYuvPipelineStart(message: String) {
    ++yuvPipelineWaitGeneration
    val attempt = yuvPipelineStartAttempt ?: return
    yuvPipelineStartAttempt = null
    cameraStartSettlementFailure = CameraFailure(
      ERROR_OPERATION_CANCELLED,
      message,
      null
    )
    attempt.settle(false)
  }

  private fun takePendingCameraStartCallbacks(): MutableList<(Boolean) -> Unit> {
    val callbacks = pendingCameraStartCallbacks.toMutableList()
    pendingCameraStartCallbacks.clear()
    val attempt = cameraStartAttempt
    if (attempt != null) {
      callbacks += attempt.transferCallbacks()
    }
    return callbacks
  }

  private fun deliverCameraStartResult(
    callbacks: List<(Boolean) -> Unit>,
    accepted: Boolean,
    fallbackMessage: String
  ) {
    if (callbacks.isNotEmpty()) {
      callbacks.forEach { callback -> runCatching { callback(accepted) } }
      return
    }
    if (!accepted && cameraStartSettlementFailure?.code != ERROR_OPERATION_CANCELLED) {
      rejectCameraStart(null, fallbackMessage)
    }
  }

  private fun cancelPendingCameraStartCallbacks() {
    pendingCameraStart = false
    val callbacks = pendingCameraStartCallbacks.toList()
    pendingCameraStartCallbacks.clear()
    if (callbacks.isNotEmpty()) {
      cameraStartSettlementFailure = CameraFailure(
        ERROR_OPERATION_CANCELLED,
        "Queued camera start was cancelled by a session transition",
        null
      )
    }
    callbacks.forEach { callback -> runCatching { callback(false) } }
  }

  private fun cancelPendingProcessingStarts(code: String, message: String) {
    val queued = pendingProcessingStarts.toList()
    pendingProcessingStarts.clear()
    queued.forEach { promise ->
      if (promise != null) runCatching { reject(promise, code, message) }
    }
  }

  private fun drainPendingProcessingStarts() {
    if (processingStopping || cleanupInProgress) return
    val queued = pendingProcessingStarts.toList()
    pendingProcessingStarts.clear()
    queued.forEach { promise ->
      runCatching { startProcessingInternal(promise) }.onFailure { error ->
        rejectOrEmit(
          promise,
          ERROR_PROCESSING_START,
          error.message ?: "Unable to restart Nosmai processing",
          error
        )
      }
    }
  }

  private fun completeProcessingStop(
    failure: Throwable? = null,
    drainQueuedStarts: Boolean = true
  ) {
    processingStopping = false
    val promises = processingStopPromises.toList()
    processingStopPromises.clear()
    promises.forEach { promise ->
      runCatching {
        if (failure == null) {
          promise.resolve(null)
        } else {
          reject(
            promise,
            ERROR_PROCESSING_STOP,
            failure.message ?: "Unable to stop Nosmai processing",
            failure
          )
        }
      }
    }
    if (drainQueuedStarts) drainPendingProcessingStarts()
  }

  private fun digestLicenseKey(value: String): String {
    val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
    return bytes.joinToString(separator = "") { byte -> "%02x".format(byte) }
  }

  private fun resolvePackageSource(rawPath: String): NosmaiPackageSource? {
    val trimmed = rawPath.trim()
    if (trimmed.isEmpty()) return null

    normalizeDebugAssetPackagePath(trimmed)?.let { assetPath ->
      cacheDebugAssetPackage(assetPath)?.let { file ->
        return NosmaiPackageSource(sdkPath = file.absolutePath, file = file)
      }
    }

    if (!trimmed.startsWith("file://") && !trimmed.contains("://")) {
      normalizeProductionAssetPackagePath(trimmed)?.let { assetPath ->
        if (isAssetPackageReadable(assetPath)) {
          return NosmaiPackageSource(sdkPath = assetPath)
        }
      }
    }

    val filePath = if (trimmed.startsWith("file://")) {
      Uri.parse(trimmed).path ?: return null
    } else {
      if (trimmed.contains("://")) return null
      trimmed
    }
    val file = runCatching { File(filePath).canonicalFile }.getOrNull()?.takeIf {
      it.isFile && it.canRead() && it.extension.equals("nosmai", ignoreCase = true)
    } ?: return null
    return NosmaiPackageSource(sdkPath = file.absolutePath, file = file)
  }

  private fun normalizeDebugAssetPackagePath(rawPath: String): String? {
    if ('\\' in rawPath) return null
    val normalized = rawPath
      .removePrefix("file:///android_asset/")
      .removePrefix("asset:///")
      .removePrefix("asset://")
      .removePrefix("android_asset/")
      .removePrefix("assets/")
    if (normalized.startsWith('/')) return null

    val segments = normalized.split('/')
    val fileName = when {
      segments.size == 1 -> segments[0]
      segments.size == 2 && segments[0] == DEBUG_ASSET_DIRECTORY -> segments[1]
      else -> return null
    }
    if (
      fileName.isBlank() ||
      fileName == "." ||
      fileName == ".." ||
      !fileName.endsWith(".nosmai", ignoreCase = true)
    ) {
      return null
    }
    return "$DEBUG_ASSET_DIRECTORY/$fileName"
  }

  private fun cacheDebugAssetPackage(assetPath: String): File? {
    val context = reactContext?.applicationContext ?: return null
    return runCatching {
      val packageUpdatedAt = context.packageManager
        .getPackageInfo(context.packageName, 0)
        .lastUpdateTime
      val cacheDirectory = File(
        context.cacheDir,
        "$DEBUG_CACHE_DIRECTORY/$packageUpdatedAt"
      )
      check(cacheDirectory.exists() || cacheDirectory.mkdirs()) {
        "Unable to create the debug package cache"
      }

      val destination = File(cacheDirectory, File(assetPath).name).canonicalFile
      check(destination.parentFile == cacheDirectory.canonicalFile) {
        "Invalid debug package cache path"
      }
      if (!destination.isFile || destination.length() == 0L) {
        context.assets.open(assetPath).use { input ->
          destination.outputStream().use(input::copyTo)
        }
      }
      destination.takeIf { it.isFile && it.canRead() && it.length() > 0L }
    }.getOrNull()
  }

  private fun normalizeProductionAssetPackagePath(rawPath: String): String? {
    if (rawPath.startsWith('/') || '\\' in rawPath) return null
    val segments = rawPath.split('/')
    if (segments.size != 3 || segments[0] != PRODUCTION_ASSET_DIRECTORY) return null
    val packageName = segments[1]
    if (
      packageName.isBlank() ||
      packageName == "." ||
      packageName == ".." ||
      segments[2] != "$packageName.nosmai"
    ) {
      return null
    }
    return rawPath
  }

  private fun isAssetPackageReadable(assetPath: String): Boolean {
    val context = reactContext?.applicationContext ?: return false
    return runCatching {
      context.assets.open(assetPath).use { stream ->
        stream.read()
      }
      true
    }.getOrDefault(false)
  }

  private fun isPackageSourceReadable(source: NosmaiPackageSource): Boolean {
    val file = source.file
    return if (file != null) {
      file.isFile && file.canRead()
    } else {
      isAssetPackageReadable(source.sdkPath)
    }
  }

  private fun resolveComparablePath(rawPath: String?): String? {
    val value = rawPath?.trim()?.takeIf(String::isNotEmpty) ?: return null
    normalizeProductionAssetPackagePath(value)?.let { return it }
    val path = if (value.startsWith("file://")) Uri.parse(value).path ?: return null else value
    return runCatching { File(path).canonicalPath }.getOrDefault(path)
  }

  private fun pathsEqual(activePath: String?, requestedPath: String): Boolean {
    val active = resolveComparablePath(activePath) ?: return false
    return active == requestedPath
  }

  private fun dimensionsForPreset(value: String?): Pair<Int, Int>? =
    when (value?.trim()?.lowercase()) {
      null, "", "default", "high", "720p", "1280x720", "hd1280x720" ->
        DEFAULT_WIDTH to DEFAULT_HEIGHT
      "480p", "640x480", "vga640x480" -> 640 to 480
      else -> null
    }

  private fun frameRotationMode(sensorOrientation: Int, front: Boolean): Int =
    if (front) {
      if (sensorOrientation == 270) ROTATE_LEFT else ROTATE_RIGHT_FLIP_HORIZONTAL
    } else {
      if (sensorOrientation == 90) ROTATE_RIGHT else ROTATE_LEFT
    }

  private fun hasCameraPermission(): Boolean =
    reactContext?.applicationContext?.checkSelfPermission(Manifest.permission.CAMERA) ==
      PackageManager.PERMISSION_GRANTED

  private fun isOesSafeDevice(): Boolean {
    if (isYuvForcedByManifest()) return false

    val hardware = Build.HARDWARE.orEmpty().lowercase()
    val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
    val brand = Build.BRAND.orEmpty().lowercase()
    return !(
      hardware.contains("mt") ||
        manufacturer.contains("mediatek") ||
        brand.contains("tecno") ||
        brand.contains("infinix")
      )
  }

  private fun isYuvForcedByManifest(): Boolean {
    val context = reactContext?.applicationContext ?: return false
    return runCatching {
      context.packageManager
        .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        .metaData
        ?.getBoolean(FORCE_YUV_METADATA_KEY, false) == true
    }.getOrDefault(false)
  }

  private fun isActiveView(view: NosmaiCameraView, token: Long): Boolean =
    token != 0L && activeView === view && view.controllerToken == token

  private fun viewBelongsToCurrentOrPendingContext(view: NosmaiCameraView): Boolean {
    val ownerContext = if (contextRetirementPending) {
      pendingReactContextAfterCleanup
    } else {
      reactContext
    }
    return ownerContext != null &&
      !isReactContextRetired(ownerContext) &&
      view.belongsTo(ownerContext)
  }

  private fun isReactContextRetired(context: ReactApplicationContext): Boolean {
    var retired = false
    val iterator = retiredReactContexts.iterator()
    while (iterator.hasNext()) {
      val candidate = iterator.next().get()
      if (candidate == null) {
        iterator.remove()
      } else if (candidate === context) {
        retired = true
      }
    }
    return retired
  }

  private fun markReactContextRetired(context: ReactApplicationContext?) {
    if (context == null || isReactContextRetired(context)) return
    retiredReactContexts += WeakReference(context)
  }

  private fun retainPreviewForTeardown(preview: NosmaiPreviewView?) {
    if (preview != null) retiredPreviewPendingTeardown = preview
  }

  private fun releaseRetiredPreview(preview: NosmaiPreviewView?) {
    if (preview != null && retiredPreviewPendingTeardown === preview) {
      retiredPreviewPendingTeardown = null
    }
  }

  private fun requiresNativeSessionTeardown(): Boolean =
    sdkInitialized ||
      processing ||
      processingStopping ||
      previewView != null ||
      retiredPreviewPendingTeardown != null ||
      cameraHelper != null ||
      cameraCloseBarrierHelper != null ||
      cameraRunning ||
      cameraStarting ||
      cameraStopping ||
      cameraStartAttempt != null ||
      pipelineListenerRegistered ||
      effectOperationActive ||
      effectOperations.isNotEmpty() ||
      effectDrainCallbacks.isNotEmpty() ||
      captureInProgress ||
      mediaRecorder.isBusy() ||
      recordingTeardownInProgress ||
      effectPipelinePoisoned ||
      teardownPoisoned

  private fun isActivePreview(
    view: NosmaiCameraView,
    preview: NosmaiPreviewView,
    token: Long
  ): Boolean = isActiveView(view, token) && previewView === preview

  private fun isCurrentCamera(
    view: NosmaiCameraView,
    token: Long,
    generation: Long,
    helper: NosmaiCamera2Helper
  ): Boolean =
    isActiveView(view, token) &&
      generation == cameraGeneration &&
      cameraHelper === helper

  private fun isCurrentCameraOffMain(
    view: NosmaiCameraView,
    token: Long,
    generation: Long,
    helper: NosmaiCamera2Helper
  ): Boolean =
    !destroyed &&
      view.controllerToken == token &&
      generation == cameraGeneration &&
      cameraHelper === helper &&
      processing

  private fun invalidateCameraCallbacks() {
    ++cameraGeneration
    cameraRunning = false
    cameraStarting = false
  }

  private fun invalidateOesWatchdog() {
    ++oesWatchdogGeneration
  }

  private fun ensureMediaTransitionAllowed(promise: Promise): Boolean {
    if (mediaRecorder.isBusy()) {
      reject(
        promise,
        NosmaiMediaErrorCode.RECORDING_IN_PROGRESS,
        "Stop the active recording before changing the camera session"
      )
      return false
    }
    if (captureInProgress) {
      reject(
        promise,
        NosmaiMediaErrorCode.CAPTURE_IN_PROGRESS,
        "Wait for the active photo capture before changing the camera session"
      )
      return false
    }
    return true
  }

  private fun activeMediaSession(
    promise: Promise,
    errorCode: String,
    message: String
  ): Pair<NosmaiCameraView, NosmaiPreviewView>? {
    val view = activeView
    val preview = previewView
    if (
      view == null ||
      preview == null ||
      !isActiveView(view, view.controllerToken) ||
      !processing ||
      processingStopping ||
      !cameraRunning ||
      !firstFrameDelivered ||
      cameraPaused ||
      hostPaused
    ) {
      reject(promise, errorCode, message)
      return null
    }
    return view to preview
  }

  private fun applyPendingViewConfiguration() {
    if (!pendingViewConfigurationChange || mediaRecorder.isBusy() || captureInProgress) return
    activeView?.let(::applyViewConfiguration)
  }

  private fun emitRecordingProgress(durationSeconds: Double) {
    val event = Arguments.createMap().apply {
      putDouble("durationSeconds", durationSeconds.coerceAtLeast(0.0))
    }
    runCatching { moduleSink?.onRecordingProgress(event) }
  }

  private fun emitCloudDownloadProgress(filterId: String, progress: Double) {
    if (destroyed) return
    val event = Arguments.createMap().apply {
      putString("filterId", filterId)
      putDouble("progress", progress.coerceIn(0.0, 1.0))
    }
    runCatching { moduleSink?.onDownloadProgress(event) }
  }

  private fun emitGameEvent(event: NosmaiGameEvent) {
    if (destroyed) return
    runCatching {
      moduleSink?.onGameEvent(Arguments.makeNativeMap(event.toMap()))
    }
  }

  private fun registerGameEventListener() {
    if (gameEventListenerRegistered) return
    NosmaiEffects.addGameEventListener(gameEventListener)
    gameEventListenerRegistered = true
  }

  private fun unregisterGameEventListener() {
    if (!gameEventListenerRegistered) return
    runCatching { NosmaiEffects.removeGameEventListener(gameEventListener) }
    gameEventListenerRegistered = false
  }

  private fun emitFrameAvailable(metadata: WritableMap) {
    if (destroyed) return
    runCatching { moduleSink?.onFrameAvailable(metadata) }
  }

  private fun NosmaiCapturedPhoto.toWritableMap(): WritableMap = Arguments.createMap().apply {
    putString("uri", uri)
    putInt("width", width)
    putInt("height", height)
    putDouble("fileSizeBytes", fileSizeBytes.toDouble())
    putString("mimeType", mimeType)
  }

  private fun NosmaiRecordedVideo.toWritableMap(): WritableMap = Arguments.createMap().apply {
    putString("uri", uri)
    putDouble("durationSeconds", durationSeconds)
    putDouble("fileSizeBytes", fileSizeBytes.toDouble())
    putString("mimeType", mimeType)
    putBoolean("hasAudio", hasAudio)
  }

  private fun NosmaiGalleryItem.toWritableMap(): WritableMap = Arguments.createMap().apply {
    putString("uri", uri)
    putString("mediaType", mediaType)
  }

  private fun ensureUsable(promise: Promise): Boolean {
    if (!destroyed) return true
    reject(promise, ERROR_DESTROYED, "The native camera session is destroyed")
    return false
  }

  private fun ensureModuleOwner(
    owner: NosmaiModuleEventSink,
    promise: Promise
  ): Boolean {
    if (isModuleOwner(owner)) return true
    reject(
      promise,
      ERROR_OPERATION_CANCELLED,
      "The React Native module belongs to a retired or pending context"
    )
    return false
  }

  private fun isModuleOwner(owner: NosmaiModuleEventSink): Boolean =
    owner.nosmaiOwnerValid &&
      !contextRetirementPending &&
      moduleSink === owner &&
      reactContext === owner.nosmaiReactContext &&
      !isReactContextRetired(owner.nosmaiReactContext)

  private fun queueInitializationDuringContextRetirement(
    owner: NosmaiModuleEventSink,
    licenseKey: String,
    promise: Promise
  ): Boolean {
    if (
      !contextRetirementPending ||
      !owner.nosmaiOwnerValid ||
      pendingModuleSinkAfterCleanup !== owner ||
      pendingReactContextAfterCleanup !== owner.nosmaiReactContext ||
      isReactContextRetired(owner.nosmaiReactContext)
    ) {
      return false
    }

    val digest = digestLicenseKey(licenseKey)
    pendingInitialization?.takeIf {
      it.owner !== pendingModuleSinkAfterCleanup || !it.owner.nosmaiOwnerValid
    }?.let {
      cancelPendingInitialization(
        ERROR_OPERATION_CANCELLED,
        "The queued SDK initialization belongs to a replaced module"
      )
    }
    val queued = pendingInitialization
    if (queued == null) {
      pendingInitialization = PendingInitialization(
        owner,
        licenseKey,
        digest,
        promise
      )
    } else if (queued.owner === owner && queued.licenseDigest == digest) {
      queued.promises += promise
    } else {
      reject(
        promise,
        ERROR_LICENSE_KEY_MISMATCH,
        "A different SDK initialization is already queued for the pending context"
      )
    }
    return true
  }

  private fun cancelPendingInitialization(code: String, message: String) {
    val queued = pendingInitialization ?: return
    pendingInitialization = null
    queued.promises.forEach { promise ->
      runCatching { reject(promise, code, message) }
    }
  }

  private fun ensureAvailable(promise: Promise): Boolean {
    if (!ensureUsable(promise)) return false
    if (cleanupInProgress) {
      reject(promise, ERROR_OPERATION_CANCELLED, "Session cleanup is in progress")
      return false
    }
    if (mediaRecorder.isNativeStateUncertain()) {
      reject(
        promise,
        ERROR_CLEANUP,
        "The native recorder did not settle; restart the host process before reusing the camera"
      )
      return false
    }
    if (effectPipelinePoisoned || teardownPoisoned) {
      reject(
        promise,
        ERROR_CLEANUP,
        "The effect pipeline is unavailable after an incomplete cleanup; retry cleanup"
      )
      return false
    }
    return true
  }

  private fun ensureInitialized(promise: Promise): Boolean {
    if (!ensureAvailable(promise)) return false
    if (sdkInitialized) return true
    reject(promise, ERROR_NOT_INITIALIZED, "Call initialize before using the Nosmai SDK")
    return false
  }

  private fun validateCodegenInteger(
    value: Double,
    field: String,
    minimum: Int,
    maximum: Int,
    promise: Promise
  ): Int? {
    if (!value.isFinite() || value % 1.0 != 0.0 || value < minimum || value > maximum) {
      reject(
        promise,
        ERROR_ARGUMENT,
        "$field must be an integer between $minimum and $maximum"
      )
      return null
    }
    return value.toInt()
  }

  private inline fun resolveSdkQuery(
    promise: Promise,
    errorCode: String,
    query: () -> Any?
  ) {
    try {
      promise.resolve(query())
    } catch (error: Throwable) {
      reject(
        promise,
        errorCode,
        error.message ?: "Unable to read Nosmai SDK state",
        error
      )
    }
  }

  private fun emitLicenseStatus(status: String) {
    runCatching { moduleSink?.onLicenseStatusChanged(status) }
  }

  private fun emitCameraError(code: String, message: String, cause: Throwable? = null) {
    runCatching { activeView?.dispatchCameraError(code, message) }
    emitError(code, message, cause)
  }

  private fun emitError(
    code: String,
    message: String,
    cause: Throwable? = null,
    reason: String? = null
  ) {
    val details = Arguments.createMap().apply {
      cause?.javaClass?.simpleName?.let { putString("nativeCause", it) }
      reason?.let { putString("reason", it) }
    }
    val error = Arguments.createMap().apply {
      putString("code", code)
      putString("message", message)
      if (details.toHashMap().isNotEmpty()) putMap("details", details)
    }
    runCatching { moduleSink?.onNativeError(error) }
  }

  private fun rejectOrEmit(
    promise: Promise?,
    code: String,
    message: String,
    cause: Throwable? = null
  ) {
    if (promise == null) emitError(code, message, cause)
    else reject(promise, code, message, cause)
  }

  private fun rejectCameraStart(
    promise: Promise?,
    fallbackMessage: String
  ) {
    val failure = cameraStartSettlementFailure
    rejectOrEmit(
      promise,
      failure?.code ?: ERROR_CAMERA_START,
      failure?.message ?: fallbackMessage,
      failure?.cause
    )
  }

  private fun reject(
    promise: Promise,
    code: String,
    message: String,
    cause: Throwable? = null
  ) {
    val details = Arguments.createMap().apply {
      cause?.javaClass?.simpleName?.let { putString("nativeCause", it) }
    }
    if (details.toHashMap().isEmpty()) {
      promise.reject(code, message, cause)
    } else {
      promise.reject(code, message, cause, details)
    }
  }

  private fun executeCameraTask(
    onRejected: (() -> Unit)? = null,
    task: () -> Unit
  ) {
    try {
      cameraExecutor.execute(task)
    } catch (_: RejectedExecutionException) {
      runOnMain {
        if (onRejected != null) {
          onRejected.invoke()
        } else if (!destroyed) {
          emitError(ERROR_DESTROYED, "The camera control executor is unavailable")
        }
      }
    }
  }

  private fun runOnMain(block: () -> Unit) {
    if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
  }

  private fun createLifecycleListener(ownerGeneration: Long): LifecycleEventListener =
    object : LifecycleEventListener {
      override fun onHostResume() = handleHostResume(ownerGeneration)
      override fun onHostPause() = handleHostPause(ownerGeneration)
      override fun onHostDestroy() = handleHostDestroy(ownerGeneration)
    }

  private fun stopRecordingForTeardown(reason: String, after: () -> Unit) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "Recording teardown must run on the main looper"
    }
    if (!mediaRecorder.isBusy()) {
      after()
      return
    }
    recordingTeardownCallbacks += after
    if (recordingTeardownInProgress) return

    recordingTeardownInProgress = true
    recordingTeardownShouldEmit = !mediaRecorder.hasPublicStopPending()
    recordingTeardownReason = reason
    mediaRecorder.stopForTeardown { _, error ->
      recordingTeardownInProgress = false
      if (recordingTeardownShouldEmit) {
        emitError(
          NosmaiMediaErrorCode.RECORDING_INTERRUPTED,
          recordingTeardownReason,
          error
        )
      }
      recordingTeardownShouldEmit = false
      recordingTeardownReason = "Recording was interrupted by camera teardown"
      val callbacks = recordingTeardownCallbacks.toList()
      recordingTeardownCallbacks.clear()
      callbacks.forEach { callback -> runCatching(callback) }
      applyPendingViewConfiguration()
    }
  }

  private fun handleHostResume(ownerGeneration: Long) {
    runOnMain {
      if (ownerGeneration != contextGeneration) return@runOnMain
      hostPaused = false
      if (cleanupInProgress) return@runOnMain
      val recordingStopPending = recordingTeardownInProgress && mediaRecorder.isBusy()
      if (cameraPaused) {
        runCatching { previewView?.onPause() }
      } else {
        runCatching { previewView?.onResume() }
      }
      if (recordingStopPending) {
        // Host pause has not stopped Camera2 yet because rendered recording must
        // finalize first. A quick resume can keep that live camera as-is.
        resumeAfterHost = false
        return@runOnMain
      }
      if (resumeAfterHost && processing && !cameraPaused) {
        resumeAfterHost = false
        firstFrameDelivered = false
        activeView?.showTransitionOverlay()
        startCameraAfterPipelineReady { accepted ->
          if (!accepted) rejectCameraStart(null, "Camera resume was rejected")
        }
      }
    }
  }

  private fun handleHostPause(ownerGeneration: Long) {
    runOnMain {
      if (ownerGeneration != contextGeneration) return@runOnMain
      hostPaused = true
      if (cleanupInProgress) return@runOnMain
      frameStream.stop()
      resumeAfterHost = processing && !cameraPaused
      if (mediaRecorder.isBusy()) {
        stopRecordingForTeardown("Recording was interrupted because the app became inactive") {
          if (ownerGeneration == contextGeneration && hostPaused) {
            handleHostPause(ownerGeneration)
          }
        }
        return@runOnMain
      }
      if (resumeAfterHost) {
        pendingCameraStart = false
        cancelYuvPipelineStart("Host pause cancelled the pending camera start")
        cancelPendingCameraStartCallbacks()
        stopCameraAsync {
          if (hostPaused && ownerGeneration == contextGeneration) {
            runCatching { previewView?.onPause() }
          }
        }
      } else {
        runCatching { previewView?.onPause() }
      }
    }
  }

  private fun handleHostDestroy(ownerGeneration: Long) {
    // LifecycleEventListener reports the host Activity lifecycle. An Activity
    // can be destroyed and recreated while ReactApplicationContext identity is
    // retained (for example, a configuration change), so this is a terminal
    // host pause rather than proof that the React bridge has been retired.
    handleHostPause(ownerGeneration)
  }

  private fun beginContextRetirement(nextContext: ReactApplicationContext?) {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "React context retirement must run on the main looper"
    }

    if (nextContext != null && isReactContextRetired(nextContext)) return

    if (contextRetirementPending) {
      if (pendingReactContextAfterCleanup !== nextContext) {
        markReactContextRetired(pendingReactContextAfterCleanup)
        pendingViewAfterCleanup?.takeIf { pendingView ->
          nextContext == null || !pendingView.belongsTo(nextContext)
        }?.let { staleView ->
          staleView.controllerToken = 0L
          pendingViewAfterCleanup = null
        }
        cancelPendingInitialization(
          ERROR_OPERATION_CANCELLED,
          "React Native context activation was superseded"
        )
        pendingModuleSinkAfterCleanup = null
        pendingReactContextAfterCleanup = nextContext
        ++contextGeneration
      }
      return
    }

    pendingReactContextAfterCleanup = nextContext
    pendingModuleSinkAfterCleanup = null
    contextRetirementPending = true
    if (mediaRecorder.isBusy()) {
      stopRecordingForTeardown("Recording was interrupted by React Native context retirement") {
        continueContextRetirement()
      }
      return
    }
    continueContextRetirement()
  }

  private fun continueContextRetirement() {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "React context retirement must run on the main looper"
    }
    if (!contextRetirementPending || cleanupInProgress) return

    val retiredContext = reactContext
    val retiredListener = lifecycleListener
    markReactContextRetired(retiredContext)
    if (retiredContext != null && retiredListener != null) {
      runCatching { retiredContext.removeLifecycleEventListener(retiredListener) }
    }
    lifecycleListener = null
    reactContext = null
    ++contextGeneration
    invalidateCameraCallbacks()
    ++viewGeneration
    activeView?.controllerToken = 0L
    moduleSink = null
    pendingViewAfterCleanup?.takeIf { pendingView ->
      pendingReactContextAfterCleanup == null ||
        !pendingView.belongsTo(pendingReactContextAfterCleanup!!)
    }?.let { staleView ->
      staleView.controllerToken = 0L
      pendingViewAfterCleanup = null
    }

    if (!cleanupInProgress) {
      cleanupInProgress = true
      cleanupSession(::completeCleanup)
    }
  }

  /** Reserved for instrumentation tests and actual process termination only. */
  fun destroy() {
    runOnMain {
      if (destroyed) return@runOnMain
      destroyed = true
      beginContextRetirement(null)
    }
  }

  companion object {
    private val THREAD_COUNTER = AtomicInteger(0)
    private const val DEFAULT_WIDTH = 1280
    private const val DEFAULT_HEIGHT = 720
    private const val CAMERA_SWITCH_DEBOUNCE_MS = 700L
    private const val OES_WATCHDOG_MS = 2_500L
    private const val CAMERA_START_WATCHDOG_MS = 5_000L
    private const val PHOTO_FLASH_PREPARATION_TIMEOUT_MS = 1_500L
    private const val PIPELINE_READY_TIMEOUT_MS = 5_000L
    private const val PIPELINE_READY_POLL_MS = 16L
    private const val EFFECT_STATE_TIMEOUT_MS = 2_000L
    private const val EFFECT_STATE_POLL_MS = 16L
    private const val EFFECT_TRANSITION_POLL_MS = 16L
    private const val EFFECT_SLOT_TIMEOUT_MS = 5_000L
    private const val EFFECT_EXECUTOR_FENCE_TIMEOUT_MS = 5_000L
    private const val EFFECT_GL_FENCE_TIMEOUT_MS = 3_000L
    private const val EFFECT_CLEAR_ALL_TIMEOUT_MS = 20_000L
    private const val EFFECT_OPERATION_TIMEOUT_MS = 30_000L
    private const val PRODUCTION_ASSET_DIRECTORY = "Nosmai_Filters"
    private const val FORCE_YUV_METADATA_KEY =
      "com.nosmai.camerasdk.reactnative.FORCE_YUV"
    private const val LIGHT_MODE_OFF = "off"
    private const val LIGHT_MODE_ON = "on"
    private const val LIGHT_MODE_AUTO = "auto"
    private val SUPPORTED_LIGHT_MODES = setOf(
      LIGHT_MODE_OFF,
      LIGHT_MODE_ON,
      LIGHT_MODE_AUTO
    )
    private val SUPPORTED_TORCH_MODES = setOf(LIGHT_MODE_OFF, LIGHT_MODE_ON)
    private const val MAX_EFFECT_PARAMETER_NAME_LENGTH = 128
    private const val MAX_EFFECT_PARAMETER_STRING_LENGTH = 16_384

    // Nosmai rotation modes, not degrees.
    private const val ROTATE_LEFT = 1
    private const val ROTATE_RIGHT = 2
    private const val ROTATE_RIGHT_FLIP_HORIZONTAL = 6

    private const val ERROR_ARGUMENT = "E_INVALID_ARGUMENT"
    private const val ERROR_INVALID_STATE = "E_INVALID_STATE"
    private const val ERROR_FRAME_STREAM = "E_FRAME_STREAM"
    private const val ERROR_NOT_INITIALIZED = "E_NOT_INITIALIZED"
    private const val ERROR_INITIALIZATION = "E_INITIALIZATION"
    private const val ERROR_LICENSE_CALLBACK = "E_LICENSE_CALLBACK_UNAVAILABLE"
    private const val ERROR_LICENSE_KEY_MISMATCH = "E_LICENSE_KEY_MISMATCH"
    private const val ERROR_CAMERA_PRESET = "E_UNSUPPORTED_CAMERA_PRESET"
    private const val ERROR_CAMERA_START = "E_CAMERA_START"
    private const val ERROR_NO_PREVIEW = "E_NO_PREVIEW"
    private const val ERROR_PROCESSING_START = "E_PROCESSING_START"
    private const val ERROR_PROCESSING_STOP = "E_PROCESSING_STOP"
    private const val ERROR_PIPELINE_NOT_READY = "E_PIPELINE_NOT_READY"
    private const val ERROR_OES_UNAVAILABLE = "E_OES_UNAVAILABLE"
    private const val ERROR_OES_FALLBACK = "E_OES_FALLBACK"
    private const val ERROR_PACKAGE_PATH = "E_INVALID_PACKAGE_PATH"
    private const val ERROR_EFFECT_APPLY = "E_EFFECT_APPLY"
    private const val ERROR_EFFECT_CLEAR = "E_EFFECT_CLEAR"
    private const val ERROR_EFFECT_STATE = "E_EFFECT_STATE"
    private const val ERROR_EFFECT_PARAMETER = "E_EFFECT_PARAMETER"
    private const val ERROR_LOCAL_CATALOG = "E_LOCAL_CATALOG"
    private const val ERROR_DEBUG_FILTERS = "E_DEBUG_FILTERS"
    private const val ERROR_OPERATION_CANCELLED = "E_OPERATION_CANCELLED"
    private const val ERROR_CLEANUP = "E_CLEANUP"
    private const val ERROR_DESTROYED = "E_SESSION_DESTROYED"
    private const val DEBUG_ASSET_DIRECTORY = "filters"
    private const val DEBUG_CACHE_DIRECTORY = "nosmai_debug_filters"
  }
}

internal object NosmaiAndroidControllerRegistry {
  private val lock = Any()
  private var controller: NosmaiAndroidController? = null

  fun get(context: ReactApplicationContext): NosmaiAndroidController = synchronized(lock) {
    val shared = controller ?: NosmaiAndroidController(context).also {
      controller = it
    }
    shared.adoptReactContext(context)
    shared
  }
}
