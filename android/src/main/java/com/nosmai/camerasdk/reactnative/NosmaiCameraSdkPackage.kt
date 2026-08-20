package com.nosmai.camerasdk.reactnative

import com.facebook.react.BaseReactPackage
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.module.model.ReactModuleInfo
import com.facebook.react.module.model.ReactModuleInfoProvider
import com.facebook.react.uimanager.ViewManager

class NosmaiCameraSdkPackage : BaseReactPackage() {
  override fun createViewManagers(
    reactContext: ReactApplicationContext
  ): List<ViewManager<*, *>> = listOf(NosmaiCameraViewManager())

  override fun getModule(
    name: String,
    reactContext: ReactApplicationContext
  ): NativeModule? = if (name == NosmaiCameraSdkModule.NAME) {
    NosmaiCameraSdkModule(reactContext)
  } else {
    null
  }

  override fun getReactModuleInfoProvider() = ReactModuleInfoProvider {
    mapOf(
      NosmaiCameraSdkModule.NAME to ReactModuleInfo(
        name = NosmaiCameraSdkModule.NAME,
        className = NosmaiCameraSdkModule::class.java.name,
        canOverrideExistingModule = false,
        needsEagerInit = false,
        isCxxModule = false,
        isTurboModule = true
      )
    )
  }
}
