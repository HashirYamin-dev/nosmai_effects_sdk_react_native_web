package com.nosmai.camerasdk.reactnative

import java.lang.reflect.Method
import java.lang.reflect.Proxy
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the SDK's current internal-only license callback behind one reflection
 * boundary. Each callback carries its install generation so the controller can
 * ignore work from a superseded or disposed session.
 */
internal class NosmaiLicenseStatusAdapter {
  private data class Registration(
    val generation: Long,
    val proxy: Any,
    val active: AtomicBoolean
  )

  private val lock = Any()
  private var registration: Registration? = null
  private var setCallbackMethod: Method? = null
  private var getStatusMethod: Method? = null

  /**
   * Installs the native callback and returns the SDK's current normalized state.
   * The proxy is strongly retained until [clear] or the next installation.
   */
  fun install(
    generation: Long,
    listener: (generation: Long, status: String) -> Unit
  ): String {
    val nosmaiClass = resolveNosmaiClass()
    val setter = resolveSetCallbackMethod(nosmaiClass)
    val callbackClass = setter.parameterTypes.single()
    val active = AtomicBoolean(true)

    val proxy = Proxy.newProxyInstance(
      callbackClass.classLoader,
      arrayOf(callbackClass)
    ) { proxyInstance, method, args ->
      when (method.name) {
        CALLBACK_METHOD -> {
          if (active.get()) {
            val isValid = args?.getOrNull(0) as? Boolean ?: false
            val rawStatus = args?.getOrNull(1)?.toString()
            runCatching {
              listener(generation, normalize(isValid, rawStatus))
            }
          }
          null
        }
        "toString" -> "NosmaiLicenseStatusCallback(generation=$generation)"
        "hashCode" -> System.identityHashCode(proxyInstance)
        "equals" -> proxyInstance === args?.getOrNull(0)
        else -> null
      }
    }
    val next = Registration(generation, proxy, active)

    synchronized(lock) {
      registration?.active?.set(false)
      try {
        setter.invoke(null, proxy)
        registration = next
      } catch (error: Throwable) {
        active.set(false)
        registration = null
        throw error
      }
    }

    return currentStatus()
  }

  /** Reads and normalizes the SDK's latest status, or `unknown` if unavailable. */
  fun currentStatus(): String = runCatching {
    val nosmaiClass = resolveNosmaiClass()
    val getter = getStatusMethod
      ?: nosmaiClass.getMethod(GET_STATUS_METHOD).also { getStatusMethod = it }
    normalize(null, getter.invoke(null)?.toString())
  }.getOrDefault(UNKNOWN)

  /**
   * Detaches the callback. A callback already racing with teardown is suppressed
  * by the registration's active flag before the SDK's static listener is cleared.
   */
  fun clear(): Boolean = synchronized(lock) {
    val previous = registration
    previous?.active?.set(false)
    registration = null

    runCatching {
        val nosmaiClass = resolveNosmaiClass()
        val setter = resolveSetCallbackMethod(nosmaiClass)
        setter.invoke(null, null as Any?)
        true
      }
      .getOrElse {
        // No installed registration means there was nothing live to detach.
        previous == null
      }
    }

  private fun resolveSetCallbackMethod(nosmaiClass: Class<*>): Method {
    setCallbackMethod?.let { return it }
    return (nosmaiClass.methods.singleOrNull {
      it.name == SET_CALLBACK_METHOD && it.parameterTypes.size == 1
    } ?: throw NoSuchMethodException(
      "$NOSMAI_CLASS.$SET_CALLBACK_METHOD(callback)"
    )).also { setCallbackMethod = it }
  }

  companion object {
    private const val NOSMAI_CLASS = "com.nosmai.effect.internal.Nosmai"
    private const val SET_CALLBACK_METHOD = "setLicenseStatusCallback"
    private const val GET_STATUS_METHOD = "getLicenseStatus"
    private const val CALLBACK_METHOD = "onLicenseStatusChanged"

    const val VALID = "valid"
    const val INVALID = "invalid"
    const val EXPIRED = "expired"
    const val UNVERIFIED = "unverified"
    const val UNKNOWN = "unknown"

    internal fun normalize(isValid: Boolean?, rawStatus: String?): String {
      if (isValid == true) return VALID

      return when (rawStatus?.trim()?.uppercase(Locale.US)) {
        "VALID" -> if (isValid == null) VALID else UNKNOWN
        "INVALID" -> INVALID
        "EXPIRED" -> EXPIRED
        "UNVERIFIED", "PENDING", "VERIFYING" -> UNVERIFIED
        else -> UNKNOWN
      }
    }

    private fun resolveNosmaiClass(): Class<*> =
      Class.forName(NOSMAI_CLASS)
  }
}
