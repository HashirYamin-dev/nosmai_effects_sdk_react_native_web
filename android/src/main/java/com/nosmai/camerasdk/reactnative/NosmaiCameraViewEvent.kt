package com.nosmai.camerasdk.reactnative

import com.facebook.react.bridge.WritableMap
import com.facebook.react.uimanager.events.Event

/** Fabric-compatible direct event emitted by [NosmaiCameraView]. */
internal class NosmaiCameraViewEvent(
  surfaceId: Int,
  viewTag: Int,
  private val name: String,
  private val payload: WritableMap
) : Event<NosmaiCameraViewEvent>(surfaceId, viewTag) {
  override fun getEventName(): String = name

  override fun getEventData(): WritableMap = payload

  override fun canCoalesce(): Boolean = false

  companion object {
    const val CAMERA_READY = "topCameraReady"
    const val CAMERA_ERROR = "topCameraError"
  }
}
