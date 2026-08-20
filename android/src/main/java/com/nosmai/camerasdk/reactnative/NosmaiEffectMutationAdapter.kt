package com.nosmai.camerasdk.reactnative

import android.opengl.GLSurfaceView
import com.nosmai.effect.NosmaiEffectsEngine
import com.nosmai.effect.api.NosmaiPreviewView
import java.lang.reflect.Field
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Compatibility boundary for SDK 3.0.x scoped-clear completion callbacks.
 *
 * The public SDK exposes asynchronous apply/remove callbacks, but its public
 * scoped-clear methods return before the GL transition has completed. The
 * callback-capable overload exists in 3.0.x as an internal method. Keeping the
 * lookup isolated here lets the bridge preserve strict mutation ordering and
 * fail closed if a future SDK removes that ABI.
 */
internal class NosmaiEffectMutationAdapter {
  private var clearSlotMethod: Method? = null
  private var executorMethod: Method? = null
  private var previewInnerViewField: Field? = null
  private var transitionField: Field? = null
  private var requestLockField: Field? = null
  private var packageSlotClass: Class<*>? = null

  fun clearSlot(slot: Slot, completion: (String?) -> Unit) {
    val settled = AtomicBoolean(false)
    val callback = object : NosmaiEffectsEngine.EffectCallback {
      override fun onSuccess() {
        if (settled.compareAndSet(false, true)) completion(null)
      }

      override fun onError(errorMessage: String?) {
        if (settled.compareAndSet(false, true)) {
          completion(errorMessage ?: "The native scoped clear failed")
        }
      }
    }

    try {
      val slotClass = resolvePackageSlotClass()
      val slotValue = slotClass.enumConstants
        ?.firstOrNull { (it as? Enum<*>)?.name == slot.nativeName }
        ?: throw NoSuchFieldException("$PACKAGE_SLOT_CLASS.${slot.nativeName}")
      resolveClearSlotMethod().invoke(null, slotValue, callback)
    } catch (error: InvocationTargetException) {
      throw error.targetException ?: error
    }
  }

  /**
   * Enqueues [completion] behind every mutation already accepted by the SDK's
   * single-thread effects executor. The controller follows this with a main
   * queue turn and a GLSurfaceView queueEvent fence: background work can post
   * main-thread tasks which in turn enqueue the final GL cleanup.
   */
  fun fenceBackgroundExecutor(completion: (String?) -> Unit) {
    val settled = AtomicBoolean(false)
    fun finish(error: String?) {
      if (settled.compareAndSet(false, true)) completion(error)
    }

    try {
      val executor = resolveExecutorMethod().invoke(null) as? ExecutorService
        ?: throw IllegalStateException("The native effects executor is unavailable")
      executor.execute { finish(null) }
    } catch (error: InvocationTargetException) {
      val cause = error.targetException ?: error
      finish(cause.message ?: "Unable to fence the native effects executor")
    } catch (error: Throwable) {
      finish(error.message ?: "Unable to fence the native effects executor")
    }
  }

  fun fenceGlQueue(
    preview: NosmaiPreviewView,
    completion: (String?) -> Unit
  ) {
    val settled = AtomicBoolean(false)
    fun finish(error: String?) {
      if (settled.compareAndSet(false, true)) completion(error)
    }

    try {
      val glView = resolvePreviewInnerViewField().get(preview) as? GLSurfaceView
        ?: throw IllegalStateException("The native preview GL view is unavailable")
      glView.queueEvent { finish(null) }
    } catch (error: Throwable) {
      finish(error.message ?: "Unable to fence the native GL cleanup queue")
    }
  }

  /**
   * Reads the SDK transition flag while holding the same monitor used by
   * finishTransition(). The lock is essential: finishTransition briefly sets
   * TRANSITION=false before dispatching a queued request and setting it true
   * again, all while REQUEST_LOCK is held.
   */
  fun isTransitionIdle(): Boolean {
    val requestLock = resolveRequestLockField().get(null)
      ?: throw IllegalStateException("The native effect request lock is unavailable")
    val transition = resolveTransitionField().get(null) as? AtomicBoolean
      ?: throw IllegalStateException("The native effect transition flag is unavailable")
    return synchronized(requestLock) { !transition.get() }
  }

  private fun resolvePackageSlotClass(): Class<*> {
    packageSlotClass?.let { return it }
    return Class.forName(PACKAGE_SLOT_CLASS).also { packageSlotClass = it }
  }

  private fun resolveClearSlotMethod(): Method {
    clearSlotMethod?.let { return it }
    val method = NosmaiEffectsEngine::class.java.declaredMethods.singleOrNull {
      it.name == CLEAR_SLOT_METHOD &&
        it.parameterTypes.size == 2 &&
        it.parameterTypes[0].name == PACKAGE_SLOT_CLASS &&
        it.parameterTypes[1] == NosmaiEffectsEngine.EffectCallback::class.java
    } ?: throw NoSuchMethodException(
      "$ENGINE_CLASS.$CLEAR_SLOT_METHOD(PackageSlot, EffectCallback)"
    )
    method.isAccessible = true
    return method.also { clearSlotMethod = it }
  }

  private fun resolveExecutorMethod(): Method {
    executorMethod?.let { return it }
    val method = NosmaiEffectsEngine::class.java.declaredMethods.singleOrNull {
      it.name == EXECUTOR_METHOD &&
        it.parameterTypes.isEmpty() &&
        ExecutorService::class.java.isAssignableFrom(it.returnType)
    } ?: throw NoSuchMethodException("$ENGINE_CLASS.$EXECUTOR_METHOD()")
    method.isAccessible = true
    return method.also { executorMethod = it }
  }

  private fun resolvePreviewInnerViewField(): Field {
    previewInnerViewField?.let { return it }
    val field = NosmaiPreviewView::class.java.getDeclaredField(PREVIEW_INNER_VIEW_FIELD)
    field.isAccessible = true
    return field.also { previewInnerViewField = it }
  }

  private fun resolveTransitionField(): Field {
    transitionField?.let { return it }
    val field = NosmaiEffectsEngine::class.java.getDeclaredField(TRANSITION_FIELD)
    field.isAccessible = true
    return field.also { transitionField = it }
  }

  private fun resolveRequestLockField(): Field {
    requestLockField?.let { return it }
    val field = NosmaiEffectsEngine::class.java.getDeclaredField(REQUEST_LOCK_FIELD)
    field.isAccessible = true
    return field.also { requestLockField = it }
  }

  enum class Slot(internal val nativeName: String) {
    COLOR("COLOR"),
    AR("AR"),
    BACKGROUND("BACKGROUND")
  }

  companion object {
    private const val ENGINE_CLASS = "com.nosmai.effect.NosmaiEffectsEngine"
    private const val PACKAGE_SLOT_CLASS =
      "com.nosmai.effect.NosmaiEffectsEngine\$PackageSlot"
    private const val CLEAR_SLOT_METHOD = "clearSlot"
    private const val EXECUTOR_METHOD = "executor"
    private const val PREVIEW_INNER_VIEW_FIELD = "innerView"
    private const val TRANSITION_FIELD = "TRANSITION"
    private const val REQUEST_LOCK_FIELD = "REQUEST_LOCK"
  }
}
