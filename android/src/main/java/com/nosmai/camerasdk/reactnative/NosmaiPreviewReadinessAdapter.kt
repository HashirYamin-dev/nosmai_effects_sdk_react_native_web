package com.nosmai.camerasdk.reactnative

import com.nosmai.effect.api.NosmaiPreviewView

/**
 * Compatibility shim for Android SDK builds where YUV readiness is not public.
 * It deliberately fails closed: a frame is never reported as processed until
 * the exact preview instance owns its native raw-data source.
 */
internal object NosmaiPreviewReadinessAdapter {
  private val sourceField = runCatching {
    NosmaiPreviewView::class.java.getDeclaredField("source").apply {
      isAccessible = true
    }
  }.getOrNull()

  internal fun isReady(preview: NosmaiPreviewView): Boolean =
    sourceField?.let { field ->
      runCatching { field.get(preview) != null }.getOrDefault(false)
    } ?: false
}
