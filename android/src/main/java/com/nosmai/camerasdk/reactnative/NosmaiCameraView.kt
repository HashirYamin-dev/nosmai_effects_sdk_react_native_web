package com.nosmai.camerasdk.reactnative

import android.graphics.Color
import android.view.View
import android.widget.FrameLayout
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.uimanager.UIManagerHelper
import com.facebook.react.uimanager.ThemedReactContext
import com.nosmai.effect.api.NosmaiPreviewView

/** React Native host for the SDK preview. Camera/session ownership stays in the controller. */
class NosmaiCameraView internal constructor(
  private val reactContext: ThemedReactContext,
  internal val controller: NosmaiAndroidController
) : FrameLayout(reactContext) {
  var cameraPosition: String = "front"
    private set

  var mirrorPreview: Boolean = false
    private set

  @Volatile internal var controllerToken: Long = 0L
  private var controllerAttached = false
  private var previewView: NosmaiPreviewView? = null
  private val transitionOverlay = View(reactContext).apply {
    setBackgroundColor(Color.BLACK)
    visibility = View.GONE
  }

  init {
    setBackgroundColor(Color.BLACK)
    clipToPadding = true
    clipChildren = true
  }

  override fun onAttachedToWindow() {
    super.onAttachedToWindow()
    if (!controllerAttached) {
      controllerAttached = true
      controller.attachView(this)
    }
  }

  override fun onDetachedFromWindow() {
    if (controllerAttached) {
      controllerAttached = false
      controller.detachView(this)
    }
    super.onDetachedFromWindow()
  }

  internal fun setCameraPosition(value: String) {
    if (cameraPosition == value) return
    cameraPosition = value
    controller.onViewConfigurationChanged(this)
  }

  internal fun belongsTo(context: ReactApplicationContext): Boolean =
    reactContext.reactApplicationContext === context

  internal fun updateCameraPositionFromController(value: String) {
    cameraPosition = value
  }

  internal fun setMirror(value: Boolean) {
    if (mirrorPreview == value) return
    mirrorPreview = value
    controller.onViewConfigurationChanged(this)
  }

  internal fun mountPreview(preview: NosmaiPreviewView) {
    previewView = preview
    removeAllViews()
    addView(
      preview,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    )
    addView(
      transitionOverlay,
      LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)
    )
    // Fabric has already laid out this host by the time the SDK preview is
    // mounted. A late addView() request does not reliably propagate through
    // the React parent, so explicitly size the native-only children now.
    layoutHostedChildren()
  }

  override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
    super.onLayout(changed, left, top, right, bottom)
    layoutHostedChildren()
  }

  internal fun showTransitionOverlay() {
    transitionOverlay.clearAnimation()
    transitionOverlay.alpha = 1f
    transitionOverlay.visibility = View.VISIBLE
  }

  internal fun hideTransitionOverlay() {
    transitionOverlay.clearAnimation()
    transitionOverlay.animate()
      .alpha(0f)
      .setDuration(80L)
      .withEndAction { transitionOverlay.visibility = View.GONE }
      .start()
  }

  internal fun dispatchCameraReady() {
    val payload = Arguments.createMap().apply {
      putString("platform", "android")
    }
    dispatchEvent(NosmaiCameraViewEvent.CAMERA_READY, payload)
  }

  internal fun dispatchCameraError(code: String, message: String) {
    val payload = Arguments.createMap().apply {
      putString("code", code)
      putString("message", message)
    }
    dispatchEvent(NosmaiCameraViewEvent.CAMERA_ERROR, payload)
  }

  private fun dispatchEvent(name: String, payload: com.facebook.react.bridge.WritableMap) {
    if (id == View.NO_ID || !reactContext.hasActiveReactInstance()) return
    UIManagerHelper.getEventDispatcherForReactTag(reactContext, id)?.dispatchEvent(
      NosmaiCameraViewEvent(UIManagerHelper.getSurfaceId(this), id, name, payload)
    )
  }

  fun dispose() {
    if (controllerAttached) {
      controllerAttached = false
      controller.detachView(this)
    }
    previewView = null
    transitionOverlay.clearAnimation()
    removeAllViews()
  }

  private fun layoutHostedChildren() {
    val hostedWidth = width
    val hostedHeight = height
    if (hostedWidth <= 0 || hostedHeight <= 0) return

    val widthSpec = View.MeasureSpec.makeMeasureSpec(hostedWidth, View.MeasureSpec.EXACTLY)
    val heightSpec = View.MeasureSpec.makeMeasureSpec(hostedHeight, View.MeasureSpec.EXACTLY)
    previewView?.let { preview ->
      preview.measure(widthSpec, heightSpec)
      preview.layout(0, 0, hostedWidth, hostedHeight)
    }
    transitionOverlay.measure(widthSpec, heightSpec)
    transitionOverlay.layout(0, 0, hostedWidth, hostedHeight)
  }
}
