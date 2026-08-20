package com.nosmai.camerasdk.reactnative

import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.WritableMap

/** TurboModule boundary backed by the process-scoped Android controller. */
class NosmaiCameraSdkModule(
  private val reactContext: ReactApplicationContext
) : NativeNosmaiCameraSdkSpec(reactContext), NosmaiModuleEventSink {
  private val controller = NosmaiAndroidControllerRegistry.get(reactContext)
  @Volatile private var valid = false

  override val nosmaiReactContext: ReactApplicationContext
    get() = reactContext

  override val nosmaiOwnerValid: Boolean
    get() = valid

  override fun initialize() {
    super.initialize()
    valid = true
    controller.adoptReactContext(reactContext)
    controller.attachModule(this)
  }

  override fun invalidate() {
    valid = false
    controller.detachModule(this)
    super.invalidate()
  }

  override fun initialize(licenseKey: String, promise: Promise) {
    controller.initializeSdk(this, licenseKey, promise)
  }

  override fun configureCamera(
    position: String,
    sessionPreset: String?,
    promise: Promise
  ) {
    controller.configureCamera(this, position, sessionPreset, promise)
  }

  override fun startProcessing(promise: Promise) {
    controller.startProcessing(this, promise)
  }

  override fun stopProcessing(promise: Promise) {
    controller.stopProcessing(this, promise)
  }

  override fun pauseCamera(promise: Promise) {
    controller.pauseCamera(this, promise)
  }

  override fun resumeCamera(promise: Promise) {
    controller.resumeCamera(this, promise)
  }

  override fun switchCamera(promise: Promise) {
    controller.switchCamera(this, promise)
  }

  override fun hasFlash(promise: Promise) {
    controller.hasFlash(this, promise)
  }

  override fun hasTorch(promise: Promise) {
    controller.hasTorch(this, promise)
  }

  override fun setFlashMode(mode: String, promise: Promise) {
    controller.setFlashMode(this, mode, promise)
  }

  override fun setTorchMode(mode: String, promise: Promise) {
    controller.setTorchMode(this, mode, promise)
  }

  override fun getFlashMode(promise: Promise) {
    controller.getFlashMode(this, promise)
  }

  override fun getTorchMode(promise: Promise) {
    controller.getTorchMode(this, promise)
  }

  override fun cleanup(promise: Promise) {
    controller.cleanup(this, promise)
  }

  override fun startFrameStream(maxFramesPerSecond: Double, promise: Promise) {
    controller.startFrameStream(this, maxFramesPerSecond, promise)
  }

  override fun stopFrameStream(promise: Promise) {
    controller.stopFrameStream(this, promise)
  }

  override fun getLatestFrame(promise: Promise) {
    controller.getLatestFrame(this, promise)
  }

  override fun isFrameStreamActive(promise: Promise) {
    controller.isFrameStreamActive(this, promise)
  }

  override fun capturePhoto(promise: Promise) {
    controller.capturePhoto(this, promise)
  }

  override fun startRecording(promise: Promise) {
    controller.startRecording(this, promise)
  }

  override fun stopRecording(promise: Promise) {
    controller.stopRecording(this, promise)
  }

  override fun isRecording(promise: Promise) {
    controller.isRecording(this, promise)
  }

  override fun getCurrentRecordingDuration(promise: Promise) {
    controller.getCurrentRecordingDuration(this, promise)
  }

  override fun saveImageToGallery(imageUri: String, name: String?, promise: Promise) {
    controller.saveImageToGallery(this, imageUri, name, promise)
  }

  override fun saveVideoToGallery(videoUri: String, name: String?, promise: Promise) {
    controller.saveVideoToGallery(this, videoUri, name, promise)
  }

  override fun applyEffect(packagePath: String, promise: Promise) {
    controller.applyEffect(this, packagePath, promise)
  }

  override fun getActiveEffects(promise: Promise) {
    controller.getActiveEffects(this, promise)
  }

  override fun getActiveFilterInfo(promise: Promise) {
    controller.getActiveFilterInfo(this, promise)
  }

  override fun getActiveEffectInfo(promise: Promise) {
    controller.getActiveEffectInfo(this, promise)
  }

  override fun getEffectParameters(promise: Promise) {
    controller.getEffectParameters(this, promise)
  }

  override fun getEffectParameterValue(parameterName: String, promise: Promise) {
    controller.getEffectParameterValue(this, parameterName, promise)
  }

  override fun setEffectParameter(
    parameterName: String,
    value: Double,
    promise: Promise
  ) {
    controller.setEffectParameter(this, parameterName, value, promise)
  }

  override fun setEffectParameterString(
    parameterName: String,
    value: String,
    promise: Promise
  ) {
    controller.setEffectParameterString(this, parameterName, value, promise)
  }

  override fun isGameReady(promise: Promise) {
    controller.isGameReady(this, promise)
  }

  override fun sendGameTap(normalizedX: Double, normalizedY: Double, promise: Promise) {
    controller.sendGameTap(this, normalizedX, normalizedY, promise)
  }

  override fun sendGameInput(
    name: String,
    normalizedX: Double,
    normalizedY: Double,
    value: Double,
    promise: Promise
  ) {
    controller.sendGameInput(this, name, normalizedX, normalizedY, value, promise)
  }

  override fun pauseGame(promise: Promise) {
    controller.pauseGame(this, promise)
  }

  override fun resumeGame(promise: Promise) {
    controller.resumeGame(this, promise)
  }

  override fun restartGame(promise: Promise) {
    controller.restartGame(this, promise)
  }

  override fun getLocalFilters(packageType: String?, promise: Promise) {
    controller.getLocalFilters(this, packageType, promise)
  }

  override fun getDebugFilters(packageType: String?, promise: Promise) {
    controller.getDebugFilters(this, packageType, promise)
  }

  override fun isCloudFilterEnabled(promise: Promise) {
    controller.isCloudFilterEnabled(this, promise)
  }

  override fun getCloudFilters(
    packageType: String?,
    version: String,
    page: Double,
    limit: Double,
    fetchAllPages: Boolean,
    promise: Promise
  ) {
    controller.getCloudFilters(
      this,
      packageType,
      version,
      page,
      limit,
      fetchAllPages,
      promise
    )
  }

  override fun downloadCloudFilter(filterId: String, promise: Promise) {
    controller.downloadCloudFilter(this, filterId, promise)
  }

  override fun removeCloudFilter(filterId: String, promise: Promise) {
    controller.removeCloudFilter(this, filterId, promise)
  }

  override fun removeEffect(packagePath: String, promise: Promise) {
    controller.removeEffect(this, packagePath, promise)
  }

  override fun clearFilter(promise: Promise) {
    controller.clearFilter(this, promise)
  }

  override fun clearAREffect(promise: Promise) {
    controller.clearAREffect(this, promise)
  }

  override fun clearAll(promise: Promise) {
    controller.clearAll(this, promise)
  }

  override fun isBeautyEffectEnabled(promise: Promise) {
    controller.isBeautyEffectEnabled(this, promise)
  }

  override fun isAdvancedFiltersEnabled(promise: Promise) {
    controller.isAdvancedFiltersEnabled(this, promise)
  }

  override fun setBeautyValue(control: String, value: Double, promise: Promise) {
    controller.setBeautyValue(this, control, value, promise)
  }

  override fun clearBeauty(promise: Promise) {
    controller.clearBeauty(this, promise)
  }

  override fun applyMakeup(
    makeupType: String,
    style: String,
    red: Double,
    green: Double,
    blue: Double,
    intensity: Double,
    promise: Promise
  ) {
    controller.applyMakeup(
      this,
      makeupType,
      style,
      red,
      green,
      blue,
      intensity,
      promise
    )
  }

  override fun setMakeupIntensity(
    makeupType: String,
    intensity: Double,
    promise: Promise
  ) {
    controller.setMakeupIntensity(this, makeupType, intensity, promise)
  }

  override fun removeMakeup(makeupType: String, promise: Promise) {
    controller.removeMakeup(this, makeupType, promise)
  }

  override fun isMakeupActive(makeupType: String, promise: Promise) {
    controller.isMakeupActive(this, makeupType, promise)
  }

  override fun clearMakeup(promise: Promise) {
    controller.clearMakeup(this, promise)
  }

  override fun setReshape(
    reshapeType: String,
    value: Double,
    promise: Promise
  ) {
    controller.setReshape(this, reshapeType, value, promise)
  }

  override fun clearReshapes(promise: Promise) {
    controller.clearReshapes(this, promise)
  }

  override fun setEyeColor(
    red: Double,
    green: Double,
    blue: Double,
    intensity: Double,
    promise: Promise
  ) {
    controller.setEyeColor(this, red, green, blue, intensity, promise)
  }

  override fun setEyeColorIntensity(intensity: Double, promise: Promise) {
    controller.setEyeColorIntensity(this, intensity, promise)
  }

  override fun removeEyeColor(promise: Promise) {
    controller.removeEyeColor(this, promise)
  }

  override fun isEyeColorActive(promise: Promise) {
    controller.isEyeColorActive(this, promise)
  }

  override fun setColorAdjustment(
    control: String,
    value1: Double,
    value2: Double,
    value3: Double,
    promise: Promise
  ) {
    controller.setColorAdjustment(
      this,
      control,
      value1,
      value2,
      value3,
      promise
    )
  }

  override fun resetColorAdjustments(promise: Promise) {
    controller.resetColorAdjustments(this, promise)
  }

  override fun setBackground(
    mode: String,
    resourceUri: String?,
    red: Double,
    green: Double,
    blue: Double,
    alpha: Double,
    blurStrength: Double,
    promise: Promise
  ) {
    controller.setBackground(
      this,
      mode,
      resourceUri,
      red,
      green,
      blue,
      alpha,
      blurStrength,
      promise
    )
  }

  override fun clearBackground(promise: Promise) {
    controller.clearBackground(this, promise)
  }

  override fun onActiveEffectsChanged(state: WritableMap) {
    if (valid && reactContext.hasActiveReactInstance()) emitOnActiveEffectsChanged(state)
  }

  override fun onLicenseStatusChanged(status: String) {
    if (valid && reactContext.hasActiveReactInstance()) emitOnLicenseStatusChanged(status)
  }

  override fun onNativeError(error: WritableMap) {
    if (valid && reactContext.hasActiveReactInstance()) emitOnError(error)
  }

  override fun onRecordingProgress(progress: WritableMap) {
    if (valid && reactContext.hasActiveReactInstance()) emitOnRecordingProgress(progress)
  }

  override fun onDownloadProgress(progress: WritableMap) {
    if (valid && reactContext.hasActiveReactInstance()) emitOnDownloadProgress(progress)
  }

  override fun onGameEvent(event: WritableMap) {
    if (valid && reactContext.hasActiveReactInstance()) emitOnGameEvent(event)
  }

  override fun onFrameAvailable(metadata: WritableMap) {
    if (valid && reactContext.hasActiveReactInstance()) emitOnFrameAvailable(metadata)
  }

  companion object {
    const val NAME = NativeNosmaiCameraSdkSpec.NAME
  }
}
