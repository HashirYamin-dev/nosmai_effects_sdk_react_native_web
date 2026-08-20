package com.nosmai.camerasdk.reactnative

import com.facebook.react.module.annotations.ReactModule
import com.facebook.react.uimanager.SimpleViewManager
import com.facebook.react.uimanager.ThemedReactContext
import com.facebook.react.uimanager.ViewManagerDelegate
import com.facebook.react.uimanager.annotations.ReactProp
import com.facebook.react.viewmanagers.NosmaiCameraViewManagerDelegate
import com.facebook.react.viewmanagers.NosmaiCameraViewManagerInterface

@ReactModule(name = NosmaiCameraViewManager.NAME)
class NosmaiCameraViewManager : SimpleViewManager<NosmaiCameraView>(),
  NosmaiCameraViewManagerInterface<NosmaiCameraView> {

  private val delegate = NosmaiCameraViewManagerDelegate(this)

  override fun getDelegate(): ViewManagerDelegate<NosmaiCameraView> = delegate

  override fun getName(): String = NAME

  public override fun createViewInstance(context: ThemedReactContext): NosmaiCameraView {
    val controller = NosmaiAndroidControllerRegistry.get(context.reactApplicationContext)
    return NosmaiCameraView(context, controller)
  }

  @ReactProp(name = "cameraPosition")
  override fun setCameraPosition(view: NosmaiCameraView, value: String?) {
    view.setCameraPosition(if (value == "back") "back" else "front")
  }

  @ReactProp(name = "mirror")
  override fun setMirror(view: NosmaiCameraView, value: Boolean) {
    view.setMirror(value)
  }

  override fun getExportedCustomDirectEventTypeConstants(): Map<String, Any> =
    mapOf(
      NosmaiCameraViewEvent.CAMERA_READY to
        mapOf("registrationName" to "onCameraReady"),
      NosmaiCameraViewEvent.CAMERA_ERROR to
        mapOf("registrationName" to "onCameraError")
    )

  override fun onDropViewInstance(view: NosmaiCameraView) {
    view.dispose()
    super.onDropViewInstance(view)
  }

  companion object {
    const val NAME = "NosmaiCameraView"
  }
}
