package com.nosmai.camerasdk.reactnative

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.system.ErrnoException
import android.system.OsConstants
import com.nosmai.effect.api.NosmaiPreviewView
import com.nosmai.effect.api.NosmaiSDK
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max

/**
 * Main-looper-confined recording state machine. The Nosmai SDK produces the
 * rendered video track while MediaRecorder produces a microphone AAC track;
 * file work and muxing stay on a dedicated serial worker.
 */
internal class NosmaiAndroidRecorder(
  context: Context,
  private val mainHandler: Handler,
  private val progress: (Double) -> Unit
) {
  private enum class State { IDLE, STARTING, RECORDING, STOPPING }

  private data class NativeStopOutcome(
    val outputPath: String?,
    val success: Boolean,
    val message: String?,
    val cause: Throwable?
  )

  private val appContext = context.applicationContext
  private val audioExecutor = Executors.newSingleThreadExecutor(
    ThreadFactory { runnable ->
      Thread(runnable, "NosmaiRecordingAudio").apply { isDaemon = true }
    }
  )
  private val finalizationExecutor = Executors.newSingleThreadExecutor(
    ThreadFactory { runnable ->
      Thread(runnable, "NosmaiRecordingFinalizer").apply { isDaemon = true }
    }
  )

  private var state = State.IDLE
  private var generation = 0L
  private var nativeStartInFlight = false
  private var nativeStopRequested = false
  private var nativeStopSettlementStarted = false
  private var audioStopSettled = false
  private var audioStopFailure: Throwable? = null
  private var nativeStopOutcome: NativeStopOutcome? = null
  private var finalizationStarted = false
  private val audioReleaseInFlight = AtomicBoolean(false)
  private var nativeStateUncertainGeneration: Long? = null
  private var forcedStopRequested = false
  private var audioRecorder: MediaRecorder? = null
  private var audioFile: File? = null
  private var videoFile: File? = null
  private var finalFile: File? = null
  private var audioStartedAtNs = 0L
  private var videoRequestedAtNs = 0L
  private var recordingStartedAtMs = 0L
  private var frozenDurationSeconds = 0.0
  private var startCompletion: ((NosmaiMediaException?) -> Unit)? = null
  private var stopCompletion:
    ((NosmaiRecordedVideo?, NosmaiMediaException?) -> Unit)? = null
  private val teardownCompletions =
    mutableListOf<(NosmaiRecordedVideo?, NosmaiMediaException?) -> Unit>()
  private var startWatchdog: Runnable? = null
  private var audioStopWatchdog: Runnable? = null
  private var nativeStopWatchdog: Runnable? = null
  private var finalizationWatchdog: Runnable? = null

  private val progressRunnable = object : Runnable {
    override fun run() {
      if (state != State.RECORDING) return
      progress(currentDurationSeconds())
      mainHandler.postDelayed(this, PROGRESS_INTERVAL_MS)
    }
  }

  fun isBusy(): Boolean {
    assertMain()
    return state != State.IDLE
  }

  fun currentDurationSeconds(): Double {
    assertMain()
    return when (state) {
      State.RECORDING -> {
        if (recordingStartedAtMs <= 0L) 0.0
        else max(0L, SystemClock.elapsedRealtime() - recordingStartedAtMs) / 1000.0
      }
      State.STOPPING -> frozenDurationSeconds
      else -> 0.0
    }
  }

  fun hasPublicStopPending(): Boolean {
    assertMain()
    return stopCompletion != null
  }

  fun isNativeStateUncertain(): Boolean {
    assertMain()
    return nativeStateUncertainGeneration != null
  }

  fun start(
    preview: NosmaiPreviewView,
    completion: (NosmaiMediaException?) -> Unit
  ) {
    assertMain()
    if (state != State.IDLE) {
      completion(
        NosmaiMediaException(
          NosmaiMediaErrorCode.RECORDING_IN_PROGRESS,
          "A recording operation is already in progress"
        )
      )
      return
    }
    if (nativeStateUncertainGeneration != null) {
      completion(
        NosmaiMediaException(
          NosmaiMediaErrorCode.RECORDING_START,
          "The native recorder did not settle; restart the host process before recording again"
        )
      )
      return
    }
    if (audioReleaseInFlight.get()) {
      completion(
        NosmaiMediaException(
          NosmaiMediaErrorCode.RECORDING_IN_PROGRESS,
          "The previous microphone recording is still stopping"
        )
      )
      return
    }
    if (
      appContext.checkSelfPermission(Manifest.permission.RECORD_AUDIO) !=
      PackageManager.PERMISSION_GRANTED
    ) {
      completion(
        NosmaiMediaException(
          NosmaiMediaErrorCode.RECORDING_PERMISSION,
          "RECORD_AUDIO must be granted by the host before recording"
        )
      )
      return
    }

    state = State.STARTING
    val operationGeneration = ++generation
    forcedStopRequested = false
    nativeStartInFlight = false
    nativeStopRequested = false
    nativeStopSettlementStarted = false
    startCompletion = completion
    scheduleStartWatchdog(operationGeneration)

    try {
      audioExecutor.execute {
        var preparedRecorder: MediaRecorder? = null
        var preparedAudioFile: File? = null
        var preparedVideoFile: File? = null
        var preparedFinalFile: File? = null
        var preparedAudioStartNs = 0L
        var preparationFailure: Throwable? = null
        try {
          val directory = File(appContext.cacheDir, RECORDING_DIRECTORY)
          if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("Unable to create the recording cache directory")
          }
          val identifier = UUID.randomUUID().toString()
          preparedAudioFile = File(directory, "nosmai_audio_$identifier.m4a")
          preparedVideoFile = File(directory, "nosmai_video_$identifier.mp4")
          preparedFinalFile = File(directory, "nosmai_final_$identifier.mp4")
          preparedRecorder = createAudioRecorder(preparedAudioFile)
          preparedRecorder.start()
          preparedAudioStartNs = SystemClock.elapsedRealtimeNanos()
        } catch (error: Throwable) {
          preparationFailure = error
          releaseAudioRecorder(preparedRecorder, stop = true)
          preparedAudioFile?.delete()
          preparedVideoFile?.delete()
          preparedFinalFile?.delete()
          preparedRecorder = null
        }

        mainHandler.post {
          if (
            operationGeneration != generation ||
            state != State.STARTING ||
            forcedStopRequested
          ) {
            releaseAudioRecorderAsync(preparedRecorder, preparedAudioFile)
            preparedVideoFile?.delete()
            preparedFinalFile?.delete()
            if (operationGeneration == generation && state == State.STARTING) {
              cancelStartWatchdog()
              state = State.IDLE
              finishStart(
                NosmaiMediaException(
                  NosmaiMediaErrorCode.RECORDING_INTERRUPTED,
                  "Recording start was cancelled by camera teardown"
                )
              )
              finishTeardown(null, null)
            }
            return@post
          }
          if (preparationFailure != null || preparedRecorder == null) {
            cancelStartWatchdog()
            state = State.IDLE
            finishStart(
              recordingFailure(
                NosmaiMediaErrorCode.RECORDING_START,
                "Unable to start microphone recording",
                preparationFailure
              )
            )
            finishTeardown(null, null)
            return@post
          }

          audioRecorder = preparedRecorder
          audioFile = preparedAudioFile
          videoFile = preparedVideoFile
          finalFile = preparedFinalFile
          audioStartedAtNs = preparedAudioStartNs
          nativeStartInFlight = true
          try {
            NosmaiSDK.startRecording(
              preview,
              preparedVideoFile!!.absolutePath,
              object : NosmaiSDK.RecordingCallback {
                override fun onStarted(success: Boolean, error: String?) {
                  dispatchToMain {
                    handleNativeStart(
                      operationGeneration,
                      success,
                      error
                    )
                  }
                }
              }
            )
          } catch (error: Throwable) {
            handleNativeStart(
              operationGeneration,
              false,
              error.message ?: "Unable to start Nosmai video recording",
              error
            )
          }
        }
      }
    } catch (error: RejectedExecutionException) {
      cancelStartWatchdog()
      state = State.IDLE
      finishStart(
        NosmaiMediaException(
          NosmaiMediaErrorCode.RECORDING_START,
          "The recording audio worker is unavailable",
          error
        )
      )
    }
  }

  fun stop(
    completion: (NosmaiRecordedVideo?, NosmaiMediaException?) -> Unit
  ) {
    assertMain()
    when (state) {
      State.IDLE -> completion(
        null,
        NosmaiMediaException(
          NosmaiMediaErrorCode.NOT_RECORDING,
          "No recording is in progress"
        )
      )
      State.STARTING, State.STOPPING -> completion(
        null,
        NosmaiMediaException(
          NosmaiMediaErrorCode.RECORDING_IN_PROGRESS,
          "The recording is still starting or stopping"
        )
      )
      State.RECORDING -> {
        stopCompletion = completion
        beginStop()
      }
    }
  }

  fun stopForTeardown(
    completion: (NosmaiRecordedVideo?, NosmaiMediaException?) -> Unit
  ) {
    assertMain()
    teardownCompletions += completion
    when (state) {
      State.IDLE -> finishTeardown(null, null)
      State.STARTING -> {
        forcedStopRequested = true
        if (!nativeStartInFlight && audioRecorder == null) {
          cancelStartWatchdog()
          ++generation
          state = State.IDLE
          finishStart(
            NosmaiMediaException(
              NosmaiMediaErrorCode.RECORDING_INTERRUPTED,
              "Recording start was cancelled by camera teardown"
            )
          )
          finishTeardown(null, null)
        }
      }
      State.RECORDING -> {
        forcedStopRequested = true
        beginStop()
      }
      State.STOPPING -> forcedStopRequested = true
    }
  }

  fun shutdown() {
    assertMain()
    cancelAllWatchdogs()
    mainHandler.removeCallbacks(progressRunnable)
    if (nativeStartInFlight || state == State.RECORDING || state == State.STOPPING) {
      bestEffortStopNativeRecording()
    }
    releaseAudioRecorder(audioRecorder, stop = true)
    audioRecorder = null
    cleanupFiles(deleteVideo = true, deleteFinal = true)
    audioExecutor.shutdownNow()
    finalizationExecutor.shutdownNow()
  }

  private fun handleNativeStart(
    operationGeneration: Long,
    success: Boolean,
    message: String?,
    cause: Throwable? = null
  ) {
    assertMain()
    if (operationGeneration != generation || state != State.STARTING) {
      if (nativeStateUncertainGeneration == operationGeneration) {
        if (success) {
          bestEffortStopNativeRecording(operationGeneration)
        } else {
          nativeStateUncertainGeneration = null
        }
      } else if (success) {
        bestEffortStopNativeRecording()
      }
      return
    }
    cancelStartWatchdog()
    nativeStartInFlight = false
    if (!success) {
      val failure = recordingFailure(
        NosmaiMediaErrorCode.RECORDING_START,
        message ?: "Unable to start Nosmai video recording",
        cause
      )
      state = State.IDLE
      releaseAudioRecorderAsync(audioRecorder, audioFile)
      audioRecorder = null
      cleanupFiles(deleteVideo = true, deleteFinal = true)
      finishStart(failure)
      finishTeardown(null, failure.takeIf { forcedStopRequested })
      forcedStopRequested = false
      return
    }

    state = State.RECORDING
    videoRequestedAtNs = SystemClock.elapsedRealtimeNanos()
    recordingStartedAtMs = SystemClock.elapsedRealtime()
    if (forcedStopRequested) {
      finishStart(
        NosmaiMediaException(
          NosmaiMediaErrorCode.RECORDING_INTERRUPTED,
          "Recording start was cancelled by camera teardown"
        )
      )
      beginStop()
      return
    }
    progress(0.0)
    mainHandler.removeCallbacks(progressRunnable)
    mainHandler.postDelayed(progressRunnable, PROGRESS_INTERVAL_MS)
    finishStart(null)
  }

  private fun beginStop() {
    assertMain()
    if (state != State.RECORDING) return
    frozenDurationSeconds = currentDurationSeconds()
    state = State.STOPPING
    nativeStopRequested = false
    nativeStopSettlementStarted = false
    audioStopSettled = false
    audioStopFailure = null
    nativeStopOutcome = null
    finalizationStarted = false
    mainHandler.removeCallbacks(progressRunnable)
    progress(frozenDurationSeconds)
    val operationGeneration = generation
    val recorder = audioRecorder
    val temporaryAudioFile = audioFile
    audioRecorder = null
    audioReleaseInFlight.set(true)
    scheduleAudioStopWatchdog(operationGeneration)

    executeAudio(
      task = {
        val audioStopFailure = try {
          releaseAudioRecorder(recorder, stop = true)
        } finally {
          audioReleaseInFlight.set(false)
        }
        mainHandler.post {
          if (operationGeneration != generation || state != State.STOPPING) {
            temporaryAudioFile?.delete()
            return@post
          }
          finishAudioStop(operationGeneration, audioStopFailure)
        }
      },
      onRejected = { error ->
        val audioStopFailure = try {
          releaseAudioRecorder(recorder, stop = true) ?: error
        } finally {
          audioReleaseInFlight.set(false)
        }
        finishAudioStop(operationGeneration, audioStopFailure)
      }
    )
    stopNativeVideo(operationGeneration)
  }

  private fun finishAudioStop(operationGeneration: Long, failure: Throwable?) {
    assertMain()
    if (!NosmaiRecordingFailurePolicy.acceptsSettlement(
        operationGeneration,
        generation,
        state == State.STOPPING,
        audioStopSettled
      )) {
      return
    }
    audioStopSettled = true
    audioStopFailure = failure
    cancelAudioStopWatchdog()
    finalizeStopWhenReady(operationGeneration)
  }

  private fun stopNativeVideo(operationGeneration: Long) {
    assertMain()
    if (
      operationGeneration != generation ||
      state != State.STOPPING ||
      nativeStopRequested
    ) {
      return
    }
    nativeStopRequested = true
    scheduleNativeStopWatchdog(operationGeneration)
    try {
      NosmaiSDK.stopRecording(
        object : NosmaiSDK.RecordingCallback {
          override fun onCompleted(
            outputPath: String?,
            success: Boolean,
            error: String?
          ) {
            dispatchToMain {
              finishNativeStop(
                operationGeneration,
                outputPath,
                success,
                error,
                nativeCallbackDelivered = true
              )
            }
          }
        }
      )
    } catch (error: Throwable) {
      finishNativeStop(
        operationGeneration,
        videoFile?.absolutePath,
        false,
        error.message,
        error
      )
    }
  }

  private fun finishNativeStop(
    operationGeneration: Long,
    outputPath: String?,
    success: Boolean,
    nativeMessage: String?,
    nativeCause: Throwable? = null,
    nativeCallbackDelivered: Boolean = false
  ) {
    assertMain()
    if (
      nativeCallbackDelivered &&
      nativeStateUncertainGeneration == operationGeneration
    ) {
      nativeStateUncertainGeneration = null
    }
    if (!NosmaiRecordingFailurePolicy.acceptsSettlement(
        operationGeneration,
        generation,
        state == State.STOPPING,
        nativeStopSettlementStarted
      )) {
      return
    }
    nativeStopSettlementStarted = true
    cancelNativeStopWatchdog()
    nativeStopOutcome = NativeStopOutcome(
      outputPath = outputPath,
      success = success,
      message = nativeMessage,
      cause = nativeCause
    )
    finalizeStopWhenReady(operationGeneration)
  }

  private fun finalizeStopWhenReady(operationGeneration: Long) {
    assertMain()
    val nativeOutcome = nativeStopOutcome
    if (
      operationGeneration != generation ||
      state != State.STOPPING ||
      !audioStopSettled ||
      nativeOutcome == null ||
      finalizationStarted
    ) {
      return
    }
    finalizationStarted = true
    val sourceVideo = nativeOutcome.outputPath
      ?.takeIf(String::isNotBlank)
      ?.let(::File)
      ?.takeIf { file -> file.isFile && file.length() > 0L }
      ?: videoFile
    val sourceAudio = audioFile
    val destination = finalFile
    val requestedVideo = videoFile
    val duration = frozenDurationSeconds
    val audioLeadUs = max(0L, (videoRequestedAtNs - audioStartedAtNs) / 1_000L)
    val discardAfterTeardown = forcedStopRequested && stopCompletion == null
    if (discardAfterTeardown) {
      sourceAudio?.delete()
      destination?.delete()
      sourceVideo?.delete()
      if (requestedVideo?.absolutePath != sourceVideo?.absolutePath) requestedVideo?.delete()
      val failure = if (nativeOutcome.success) {
        null
      } else {
        recordingFailure(
          NosmaiMediaErrorCode.RECORDING_STOP,
          nativeOutcome.message ?: "Interrupted recording could not be finalized",
          nativeOutcome.cause
        )
      }
      completeStop(null, failure)
      return
    }
    val fallbackReady = AtomicBoolean(false)
    val fallbackClaimed = AtomicBoolean(false)
    scheduleFinalizationWatchdog(
      operationGeneration,
      sourceVideo,
      duration,
      fallbackReady,
      fallbackClaimed
    )

    executeFinalization(
      task = {
        if (nativeOutcome.success && NosmaiMediaMuxer.hasUsableVideoTrack(sourceVideo)) {
          fallbackReady.set(true)
        }
        val outcome = finalizeFiles(
          sourceVideo,
          sourceAudio,
          destination,
          duration,
          audioLeadUs,
          nativeOutcome.success,
          nativeOutcome.message,
          nativeOutcome.cause,
          audioStopFailure
        )
        if (requestedVideo != null && requestedVideo.absolutePath != sourceVideo?.absolutePath) {
          requestedVideo.delete()
        }
        mainHandler.post {
          if (operationGeneration != generation || state != State.STOPPING) {
            val recording = outcome.first
            if (
              recording != null &&
              (!recordingReferencesFile(recording, sourceVideo) || !fallbackClaimed.get())
            ) {
              deleteRecordedOutput(recording)
            }
            if (
              !fallbackClaimed.get() &&
              (recording == null || !recordingReferencesFile(recording, sourceVideo))
            ) {
              sourceVideo?.delete()
            }
            return@post
          }
          cancelFinalizationWatchdog()
          val finalRecording = outcome.first
          if (finalRecording != null && !recordingReferencesFile(finalRecording, sourceVideo)) {
            sourceVideo?.delete()
          }
          completeStop(finalRecording, outcome.second)
        }
      },
      onRejected = { error ->
        cancelFinalizationWatchdog()
        sourceAudio?.delete()
        destination?.delete()
        sourceVideo?.delete()
        if (requestedVideo?.absolutePath != sourceVideo?.absolutePath) requestedVideo?.delete()
        completeStop(
          null,
          NosmaiMediaException(
            NosmaiMediaErrorCode.RECORDING_WRITE,
            "The recording finalizer is unavailable",
            error
          )
        )
      }
    )
  }

  private fun finalizeFiles(
    sourceVideo: File?,
    sourceAudio: File?,
    destination: File?,
    durationSeconds: Double,
    audioLeadUs: Long,
    nativeSuccess: Boolean,
    nativeMessage: String?,
    nativeCause: Throwable?,
    audioStopFailure: Throwable?
  ): Pair<NosmaiRecordedVideo?, NosmaiMediaException?> {
    if (!nativeSuccess || !NosmaiMediaMuxer.hasUsableVideoTrack(sourceVideo)) {
      sourceAudio?.delete()
      destination?.delete()
      sourceVideo?.delete()
      return null to recordingFailure(
        NosmaiMediaErrorCode.RECORDING_STOP,
        nativeMessage ?: "Unable to finalize Nosmai video recording",
        nativeCause
      )
    }
    val validVideo = requireNotNull(sourceVideo)

    var muxWarning = audioStopFailure
    var resultFile = validVideo
    var muxRequested = false
    var muxCompleted = false
    if (
      muxWarning == null &&
      sourceAudio != null &&
      sourceAudio.isFile &&
      sourceAudio.length() > 0L &&
      destination != null
    ) {
      muxRequested = true
      try {
        NosmaiMediaMuxer.merge(validVideo, sourceAudio, destination, audioLeadUs)
        resultFile = destination
        muxCompleted = true
      } catch (error: Throwable) {
        muxWarning = error
        destination.delete()
      }
    } else if (muxWarning == null) {
      muxWarning = IllegalStateException("The microphone audio track is unavailable")
    }
    sourceAudio?.delete()

    val playableVideo = NosmaiMediaMuxer.hasUsableVideoTrack(resultFile)
    val disposition = NosmaiRecordingFailurePolicy.resolveFinalization(
      playableVideo,
      muxRequested,
      muxCompleted
    )
    if (disposition == NosmaiRecordingFailurePolicy.FinalizationDisposition.FAILURE) {
      resultFile.delete()
      return null to recordingFailure(
        NosmaiMediaErrorCode.RECORDING_WRITE,
        "The finalized recording file is missing or empty",
        muxWarning
      )
    }
    val hasAudio =
      disposition == NosmaiRecordingFailurePolicy.FinalizationDisposition.MUXED
    return NosmaiRecordedVideo(
      uri = Uri.fromFile(resultFile).toString(),
      durationSeconds = max(0.0, durationSeconds),
      fileSizeBytes = resultFile.length(),
      hasAudio = hasAudio,
      muxWarning = muxWarning
    ) to null
  }

  private fun completeStop(
    result: NosmaiRecordedVideo?,
    error: NosmaiMediaException?
  ) {
    assertMain()
    cancelAllWatchdogs()
    state = State.IDLE
    ++generation
    recordingStartedAtMs = 0L
    frozenDurationSeconds = 0.0
    nativeStartInFlight = false
    val wasForced = forcedStopRequested
    forcedStopRequested = false
    val publicCompletion = stopCompletion
    stopCompletion = null
    publicCompletion?.let { completion -> runCatching { completion(result, error) } }
    finishTeardown(result, error.takeIf { wasForced })
    if (publicCompletion == null) result?.let(::deleteRecordedOutput)
    clearReferences()
  }

  private fun finishStart(error: NosmaiMediaException?) {
    val completion = startCompletion
    startCompletion = null
    completion?.let { callback -> runCatching { callback(error) } }
  }

  private fun finishTeardown(
    result: NosmaiRecordedVideo?,
    error: NosmaiMediaException?
  ) {
    val completions = teardownCompletions.toList()
    teardownCompletions.clear()
    completions.forEach { completion -> runCatching { completion(result, error) } }
  }

  private fun clearReferences() {
    audioRecorder = null
    audioFile = null
    videoFile = null
    finalFile = null
    audioStartedAtNs = 0L
    videoRequestedAtNs = 0L
    nativeStopRequested = false
    nativeStopSettlementStarted = false
    audioStopSettled = false
    audioStopFailure = null
    nativeStopOutcome = null
    finalizationStarted = false
  }

  private fun cleanupFiles(deleteVideo: Boolean, deleteFinal: Boolean) {
    audioFile?.delete()
    if (deleteVideo) videoFile?.delete()
    if (deleteFinal) finalFile?.delete()
    clearReferences()
  }

  private fun deleteRecordedOutput(recording: NosmaiRecordedVideo) {
    runCatching {
      val uri = Uri.parse(recording.uri)
      if (uri.scheme == "file") uri.path?.let(::File)?.delete()
    }
  }

  private fun recordingReferencesFile(
    recording: NosmaiRecordedVideo,
    file: File?
  ): Boolean = runCatching {
    file != null && Uri.parse(recording.uri).path == file.absolutePath
  }.getOrDefault(false)

  private fun createAudioRecorder(outputFile: File): MediaRecorder {
    val recorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
      MediaRecorder(appContext)
    } else {
      @Suppress("DEPRECATION")
      MediaRecorder()
    }
    try {
      recorder.setAudioSource(MediaRecorder.AudioSource.MIC)
      recorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
      recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
      recorder.setAudioSamplingRate(AUDIO_SAMPLE_RATE)
      recorder.setAudioEncodingBitRate(AUDIO_BIT_RATE)
      recorder.setOutputFile(outputFile.absolutePath)
      recorder.prepare()
      return recorder
    } catch (error: Throwable) {
      releaseAudioRecorder(recorder, stop = false)
      throw error
    }
  }

  private fun releaseAudioRecorder(
    recorder: MediaRecorder?,
    stop: Boolean
  ): Throwable? {
    if (recorder == null) return null
    var failure: Throwable? = null
    if (stop) {
      try {
        recorder.stop()
      } catch (error: Throwable) {
        failure = error
      }
    }
    try {
      recorder.release()
    } catch (error: Throwable) {
      if (failure == null) failure = error
    }
    return failure
  }

  private fun releaseAudioRecorderAsync(recorder: MediaRecorder?, file: File?) {
    if (recorder == null) {
      file?.delete()
      return
    }
    executeAudio(
      task = {
        releaseAudioRecorder(recorder, stop = true)
        file?.delete()
      },
      onRejected = {
        releaseAudioRecorder(recorder, stop = true)
        file?.delete()
      }
    )
  }

  private fun executeAudio(task: () -> Unit, onRejected: (Throwable) -> Unit) {
    try {
      audioExecutor.execute(task)
    } catch (error: RejectedExecutionException) {
      onRejected(error)
    }
  }

  private fun executeFinalization(task: () -> Unit, onRejected: (Throwable) -> Unit) {
    try {
      finalizationExecutor.execute(task)
    } catch (error: RejectedExecutionException) {
      onRejected(error)
    }
  }

  private fun dispatchToMain(task: () -> Unit) {
    if (Looper.myLooper() == mainHandler.looper) task() else mainHandler.post(task)
  }

  private fun scheduleStartWatchdog(operationGeneration: Long) {
    cancelStartWatchdog()
    startWatchdog = Runnable {
      if (!NosmaiRecordingFailurePolicy.acceptsSettlement(
          operationGeneration,
          generation,
          state == State.STARTING,
          false
        )) return@Runnable
      val wasForced = forcedStopRequested
      if (nativeStartInFlight) nativeStateUncertainGeneration = operationGeneration
      val failure = NosmaiMediaException(
        if (wasForced) {
          NosmaiMediaErrorCode.RECORDING_INTERRUPTED
        } else {
          NosmaiMediaErrorCode.RECORDING_START
        },
        if (wasForced) {
          "Recording start was cancelled by camera teardown"
        } else {
          "Nosmai video recording did not start in time"
        },
        TimeoutException("Recording start timed out")
      )
      if (nativeStartInFlight) bestEffortStopNativeRecording(operationGeneration)
      nativeStartInFlight = false
      state = State.IDLE
      ++generation
      releaseAudioRecorderAsync(audioRecorder, audioFile)
      audioRecorder = null
      cleanupFiles(deleteVideo = true, deleteFinal = true)
      finishStart(failure)
      finishTeardown(null, failure.takeIf { wasForced })
      forcedStopRequested = false
      cancelStartWatchdog()
    }.also { mainHandler.postDelayed(it, START_TIMEOUT_MS) }
  }

  private fun scheduleAudioStopWatchdog(operationGeneration: Long) {
    cancelAudioStopWatchdog()
    audioStopWatchdog = Runnable {
      if (!NosmaiRecordingFailurePolicy.acceptsSettlement(
          operationGeneration,
          generation,
          state == State.STOPPING,
          audioStopSettled
        )) {
        return@Runnable
      }
      finishAudioStop(
        operationGeneration,
        TimeoutException("Microphone recorder stop timed out")
      )
    }.also { mainHandler.postDelayed(it, AUDIO_STOP_TIMEOUT_MS) }
  }

  private fun scheduleNativeStopWatchdog(operationGeneration: Long) {
    cancelNativeStopWatchdog()
    nativeStopWatchdog = Runnable {
      if (!NosmaiRecordingFailurePolicy.acceptsSettlement(
          operationGeneration,
          generation,
          state == State.STOPPING,
          nativeStopSettlementStarted
        )) {
        return@Runnable
      }
      val video = videoFile
      nativeStateUncertainGeneration = operationGeneration
      finishNativeStop(
        operationGeneration = operationGeneration,
        outputPath = video?.absolutePath,
        success = false,
        nativeMessage = "Nosmai video recording did not stop in time",
        nativeCause = TimeoutException("Recording stop timed out")
      )
    }.also { mainHandler.postDelayed(it, NATIVE_STOP_TIMEOUT_MS) }
  }

  private fun scheduleFinalizationWatchdog(
    operationGeneration: Long,
    sourceVideo: File?,
    durationSeconds: Double,
    fallbackReady: AtomicBoolean,
    fallbackClaimed: AtomicBoolean
  ) {
    cancelFinalizationWatchdog()
    finalizationWatchdog = Runnable {
      if (!NosmaiRecordingFailurePolicy.acceptsSettlement(
          operationGeneration,
          generation,
          state == State.STOPPING,
          false
        )) return@Runnable
      val timeout = TimeoutException("Recording audio mux finalization timed out")
      val fallbackVideo = sourceVideo?.takeIf { candidate ->
        fallbackReady.get() && candidate.isFile && candidate.length() > 0L
      }
      if (fallbackVideo != null && NosmaiRecordingFailurePolicy.resolveFinalization(
          true,
          true,
          false
        ) == NosmaiRecordingFailurePolicy.FinalizationDisposition.VIDEO_ONLY) {
        fallbackClaimed.set(true)
        completeStop(
          NosmaiRecordedVideo(
            uri = Uri.fromFile(fallbackVideo).toString(),
            durationSeconds = max(0.0, durationSeconds),
            fileSizeBytes = fallbackVideo.length(),
            hasAudio = false,
            muxWarning = timeout
          ),
          null
        )
      } else {
        completeStop(
          null,
          NosmaiMediaException(
            NosmaiMediaErrorCode.RECORDING_WRITE,
            "The recording file could not be finalized in time",
            timeout
          )
        )
      }
    }.also {
      mainHandler.postDelayed(
        it,
        finalizationTimeoutMs(sourceVideo?.length() ?: 0L, durationSeconds)
      )
    }
  }

  private fun finalizationTimeoutMs(fileSizeBytes: Long, durationSeconds: Double): Long {
    val sizeAllowance = (fileSizeBytes / FINALIZATION_BYTES_PER_SECOND)
      .coerceAtMost(MAX_FINALIZATION_TIMEOUT_MS / 1_000L) * 1_000L
    val durationAllowance = (max(0.0, durationSeconds) * 100.0)
      .toLong()
      .coerceAtMost(MAX_FINALIZATION_TIMEOUT_MS)
    return (MIN_FINALIZATION_TIMEOUT_MS + max(sizeAllowance, durationAllowance))
      .coerceAtMost(MAX_FINALIZATION_TIMEOUT_MS)
  }

  private fun cancelStartWatchdog() {
    startWatchdog?.let(mainHandler::removeCallbacks)
    startWatchdog = null
  }

  private fun cancelAudioStopWatchdog() {
    audioStopWatchdog?.let(mainHandler::removeCallbacks)
    audioStopWatchdog = null
  }

  private fun cancelNativeStopWatchdog() {
    nativeStopWatchdog?.let(mainHandler::removeCallbacks)
    nativeStopWatchdog = null
  }

  private fun cancelFinalizationWatchdog() {
    finalizationWatchdog?.let(mainHandler::removeCallbacks)
    finalizationWatchdog = null
  }

  private fun cancelAllWatchdogs() {
    cancelStartWatchdog()
    cancelAudioStopWatchdog()
    cancelNativeStopWatchdog()
    cancelFinalizationWatchdog()
  }

  private fun bestEffortStopNativeRecording(uncertainGeneration: Long? = null) {
    runCatching {
      NosmaiSDK.stopRecording(
        object : NosmaiSDK.RecordingCallback {
          override fun onCompleted(
            outputPath: String?,
            success: Boolean,
            error: String?
          ) {
            outputPath
              ?.takeIf(String::isNotBlank)
              ?.let(::File)
              ?.let { output -> runCatching { output.delete() } }
            if (success && uncertainGeneration != null) {
              dispatchToMain {
                if (nativeStateUncertainGeneration == uncertainGeneration) {
                  nativeStateUncertainGeneration = null
                }
              }
            }
          }
        }
      )
    }
  }

  private fun recordingFailure(
    code: String,
    message: String,
    cause: Throwable?
  ): NosmaiMediaException {
    val causes = generateSequence(cause) { it.cause }.take(8).toList()
    val errnoNoSpace = causes.any { error ->
      error is ErrnoException && error.errno == OsConstants.ENOSPC
    }
    val resolvedCode = NosmaiRecordingFailurePolicy.resolveErrorCode(
      code,
      NosmaiMediaErrorCode.RECORDING_STORAGE_FULL,
      message,
      causes.mapNotNull(Throwable::message),
      errnoNoSpace
    )
    return NosmaiMediaException(resolvedCode, message, cause)
  }

  private fun assertMain() {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "Recording state must be accessed on the main looper"
    }
  }

  companion object {
    private const val RECORDING_DIRECTORY = "NosmaiRecordings"
    private const val AUDIO_SAMPLE_RATE = 44_100
    private const val AUDIO_BIT_RATE = 128_000
    private const val PROGRESS_INTERVAL_MS = 500L
    private const val START_TIMEOUT_MS = 15_000L
    private const val AUDIO_STOP_TIMEOUT_MS = 5_000L
    private const val NATIVE_STOP_TIMEOUT_MS = 20_000L
    private const val MIN_FINALIZATION_TIMEOUT_MS = 60_000L
    private const val MAX_FINALIZATION_TIMEOUT_MS = 600_000L
    private const val FINALIZATION_BYTES_PER_SECOND = 5L * 1024L * 1024L
  }
}
