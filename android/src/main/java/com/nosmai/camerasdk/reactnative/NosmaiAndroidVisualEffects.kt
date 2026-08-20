package com.nosmai.camerasdk.reactnative

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Handler
import android.os.Looper
import com.nosmai.effect.NosmaiBackgroundSegmentationConfig
import com.nosmai.effect.NosmaiEffects
import com.nosmai.effect.NosmaiEffectsEngine
import com.nosmai.effect.api.NosmaiBeauty
import java.io.File
import java.io.FileInputStream
import java.lang.reflect.InvocationTargetException
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.roundToInt

internal class NosmaiVisualException(
  val code: String,
  message: String,
  cause: Throwable? = null
) : Exception(message, cause)

/**
 * Typed Android adapter for built-in visual controls. Native mutations are
 * invoked by the controller's main-thread FIFO; only bounded file inspection
 * and image decoding run on this adapter's private IO worker.
 */
internal class NosmaiAndroidVisualEffects(
  private val mainHandler: Handler
) {
  private val ioExecutor = Executors.newSingleThreadExecutor(
    ThreadFactory { runnable ->
      Thread(
        runnable,
        "NosmaiVisualIO-${THREAD_COUNTER.incrementAndGet()}"
      ).apply { isDaemon = true }
    }
  )

  private var skinSmoothing = 0f
  private var skinWhitening = 0f
  private var teethWhitening = 0f
  private var sharpening = 0f

  private var brightness = 0f
  private var contrast = 1f
  private var red = 1f
  private var green = 1f
  private var blue = 1f
  private var grayscale = false
  private var hue = 0f
  private var saturation = 1f
  private var whiteBalanceTemperature = WHITE_BALANCE_NEUTRAL
  private var whiteBalanceTint = 0f

  private val reshapeValues = FloatArray(RESHAPE_COUNT)

  fun isBeautyEffectEnabled(): Boolean = NosmaiCapabilityAdapter.isBeautyEnabled()

  fun isAdvancedFiltersEnabled(): Boolean =
    NosmaiCapabilityAdapter.isAdvancedFiltersEnabled()

  fun setBeautyValue(control: String, value: Double) {
    requireMainThread()
    val normalized = requireRange(value, "value", 0.0, 1.0).toFloat()
    when (control) {
      "skinSmoothing" -> {
        NosmaiBeauty.applySkinSmoothing(normalized)
        skinSmoothing = normalized
      }
      "skinWhitening" -> {
        NosmaiBeauty.applySkinWhitening(normalized)
        skinWhitening = normalized
      }
      "teethWhitening" -> {
        NosmaiBeauty.applyTeethWhitening(normalized)
        teethWhitening = normalized
      }
      // Accepted for native callers even though the current JS facade routes
      // sharpening through setColorAdjustment.
      "sharpening" -> {
        NosmaiBeauty.applySharpen(normalized)
        sharpening = normalized
      }
      else -> invalidArgument(
        "control must be skinSmoothing, skinWhitening, sharpening, or teethWhitening"
      )
    }
  }

  /** Clears skin beauty plus makeup, reshape, and eye lens without color/background/packages. */
  fun clearBeauty() {
    requireMainThread()
    if (!isNeutral(skinSmoothing)) NosmaiBeauty.applySkinSmoothing(0f)
    if (!isNeutral(skinWhitening)) NosmaiBeauty.applySkinWhitening(0f)
    if (!isNeutral(teethWhitening)) NosmaiBeauty.applyTeethWhitening(0f)
    NosmaiBeauty.removeMakeup(NosmaiBeauty.MAKEUP_ALL)
    NosmaiBeauty.clearReshapes()
    NosmaiBeauty.removeEyeLens()
    skinSmoothing = 0f
    skinWhitening = 0f
    teethWhitening = 0f
    reshapeValues.fill(0f)
  }

  fun applyMakeup(
    makeupType: String,
    style: String,
    red: Double,
    green: Double,
    blue: Double,
    intensity: Double
  ) {
    requireMainThread()
    val makeup = makeupSpec(makeupType)
    val styleId = makeup.styleIds[style]
      ?: invalidArgument("style is not supported for $makeupType")
    val r = requireRange(red, "red", 0.0, 1.0).toFloat()
    val g = requireRange(green, "green", 0.0, 1.0).toFloat()
    val b = requireRange(blue, "blue", 0.0, 1.0).toFloat()
    val level = requireRange(intensity, "intensity", 0.0, 1.0).toFloat()

    when (makeupType) {
      "lipstick" -> NosmaiBeauty.applyLipstickStyle(styleId, r, g, b)
      "eyeshadow" -> NosmaiBeauty.applyEyeshadowStyle(styleId, r, g, b)
      "blusher" -> NosmaiBeauty.applyBlusherStyle(styleId, r, g, b)
      "eyelash" -> NosmaiBeauty.applyEyelashStyle(styleId)
      "eyebrow" -> NosmaiBeauty.applyEyebrowStyle(styleId, r, g, b)
    }
    NosmaiBeauty.setMakeupIntensity(makeup.category, level)
  }

  fun setMakeupIntensity(makeupType: String, intensity: Double) {
    requireMainThread()
    val makeup = makeupSpec(makeupType)
    if (!NosmaiBeauty.isMakeupActive(makeup.category)) {
      throw NosmaiVisualException(
        ERROR_INVALID_STATE,
        "$makeupType makeup is not active"
      )
    }
    NosmaiBeauty.setMakeupIntensity(
      makeup.category,
      requireRange(intensity, "intensity", 0.0, 1.0).toFloat()
    )
  }

  fun removeMakeup(makeupType: String) {
    requireMainThread()
    NosmaiBeauty.removeMakeup(makeupSpec(makeupType).category)
  }

  fun isMakeupActive(makeupType: String): Boolean {
    requireMainThread()
    return NosmaiBeauty.isMakeupActive(makeupSpec(makeupType).category)
  }

  fun clearMakeup() {
    requireMainThread()
    NosmaiBeauty.removeMakeup(NosmaiBeauty.MAKEUP_ALL)
  }

  fun setReshape(reshapeType: String, value: Double) {
    requireMainThread()
    val reshape = RESHAPES[reshapeType]
      ?: invalidArgument("reshapeType is not supported")
    val normalized = requireRange(
      value,
      "value",
      reshape.minimum,
      reshape.maximum
    ).toFloat()
    NosmaiBeauty.setReshape(reshape.id, normalized)
    reshapeValues[reshape.id] = normalized
  }

  fun clearReshapes() {
    requireMainThread()
    NosmaiBeauty.clearReshapes()
    reshapeValues.fill(0f)
  }

  fun setEyeColor(
    red: Double,
    green: Double,
    blue: Double,
    intensity: Double
  ) {
    requireMainThread()
    NosmaiBeauty.applyEyeLens(
      requireRange(red, "red", 0.0, 1.0).toFloat(),
      requireRange(green, "green", 0.0, 1.0).toFloat(),
      requireRange(blue, "blue", 0.0, 1.0).toFloat(),
      requireRange(intensity, "intensity", 0.0, 1.0).toFloat()
    )
  }

  fun setEyeColorIntensity(intensity: Double) {
    requireMainThread()
    if (!NosmaiBeauty.isEyeLensActive()) {
      throw NosmaiVisualException(ERROR_INVALID_STATE, "Eye color is not active")
    }
    NosmaiBeauty.setEyeLensIntensity(
      requireRange(intensity, "intensity", 0.0, 1.0).toFloat()
    )
  }

  fun removeEyeColor() {
    requireMainThread()
    NosmaiBeauty.removeEyeLens()
  }

  fun isEyeColorActive(): Boolean {
    requireMainThread()
    return NosmaiBeauty.isEyeLensActive()
  }

  fun setColorAdjustment(
    control: String,
    value1: Double,
    value2: Double,
    value3: Double
  ) {
    requireMainThread()
    requireFinite(value1, "value1")
    requireFinite(value2, "value2")
    requireFinite(value3, "value3")

    when (control) {
      "brightness" -> {
        brightness = requireRange(value1, "value1", -1.0, 1.0).toFloat()
        NosmaiBeauty.applyBrightness(brightness)
      }
      "contrast" -> {
        contrast = requireRange(value1, "value1", 0.0, 2.0).toFloat()
        NosmaiBeauty.applyContrast(contrast)
      }
      "rgb" -> {
        red = requireRange(value1, "value1", 0.0, 2.0).toFloat()
        green = requireRange(value2, "value2", 0.0, 2.0).toFloat()
        blue = requireRange(value3, "value3", 0.0, 2.0).toFloat()
        NosmaiBeauty.applyRGB(red, green, blue)
      }
      "sharpening" -> {
        sharpening = requireRange(value1, "value1", 0.0, 1.0).toFloat()
        NosmaiBeauty.applySharpen(sharpening)
      }
      "grayscale" -> {
        if (value1 != 0.0 && value1 != 1.0) {
          invalidArgument("value1 must be 0 or 1 for grayscale")
        }
        grayscale = value1 == 1.0
        NosmaiBeauty.setGrayscaleEnabled(grayscale)
      }
      "hue" -> {
        hue = normalizedHue(requireRange(value1, "value1", 0.0, 360.0))
        NosmaiBeauty.applyHue(hue)
      }
      "whiteBalance" -> {
        whiteBalanceTemperature =
          requireRange(value1, "value1", 2000.0, 8000.0).toFloat()
        whiteBalanceTint = requireRange(value2, "value2", -1.0, 1.0).toFloat()
        NosmaiBeauty.applyWhiteBalance(
          whiteBalanceTemperature,
          whiteBalanceTint
        )
      }
      "hsb" -> {
        hue = normalizedHue(requireRange(value1, "value1", -360.0, 360.0))
        saturation = requireRange(value2, "value2", 0.0, 2.0).toFloat()
        brightness =
          (requireRange(value3, "value3", 0.0, 2.0) - 1.0).toFloat()
        // Android exposes the optimized color filter as separate setters. HSB
        // brightness has neutral 1, while applyBrightness has neutral 0.
        NosmaiBeauty.applyHue(hue)
        NosmaiBeauty.applySaturation(saturation)
        NosmaiBeauty.applyBrightness(brightness)
      }
      else -> invalidArgument(
        "control must be brightness, contrast, rgb, sharpening, grayscale, hue, whiteBalance, or hsb"
      )
    }
  }

  fun resetColorAdjustments() {
    requireMainThread()
    if (!hasTrackedColorAdjustments()) return

    NosmaiBeauty.applyBrightness(0f)
    NosmaiBeauty.applyContrast(1f)
    NosmaiBeauty.applyRGB(1f, 1f, 1f)
    NosmaiBeauty.applySharpen(0f)
    NosmaiBeauty.setGrayscaleEnabled(false)
    NosmaiBeauty.applyHue(0f)
    NosmaiBeauty.applySaturation(1f)
    NosmaiBeauty.applyWhiteBalance(WHITE_BALANCE_NEUTRAL, 0f)

    brightness = 0f
    contrast = 1f
    red = 1f
    green = 1f
    blue = 1f
    sharpening = 0f
    grayscale = false
    hue = 0f
    saturation = 1f
    whiteBalanceTemperature = WHITE_BALANCE_NEUTRAL
    whiteBalanceTint = 0f
  }

  /**
   * Validates all bridge values immediately. Image/video file inspection then
   * runs off-main; [completion] is always delivered on the main looper.
   */
  fun prepareBackground(
    mode: String,
    resourceUri: String?,
    red: Double,
    green: Double,
    blue: Double,
    alpha: Double,
    blurStrength: Double,
    completion: (NosmaiBackgroundSegmentationConfig?, NosmaiVisualException?) -> Unit
  ) {
    requireMainThread()
    val r = requireRange(red, "red", 0.0, 1.0)
    val g = requireRange(green, "green", 0.0, 1.0)
    val b = requireRange(blue, "blue", 0.0, 1.0)
    val a = requireRange(alpha, "alpha", 0.0, 1.0)
    val blur = requireRange(blurStrength, "blurStrength", 0.0, 1.0)

    when (mode) {
      "blur" -> {
        requireNoResourceUri(resourceUri, mode)
        completion(
          NosmaiBackgroundSegmentationConfig(
            NosmaiBackgroundSegmentationConfig.Mode.BLUR
          ).apply {
            // The Android config contract is 0..100; JNI normalizes to 0..1.
            this.blurStrength = (blur * 100.0).toFloat()
          },
          null
        )
      }
      "color" -> {
        requireNoResourceUri(resourceUri, mode)
        completion(
          NosmaiBackgroundSegmentationConfig(
            NosmaiBackgroundSegmentationConfig.Mode.COLOR
          ).apply {
            replacementColor = Color.argb(
              channel(a),
              channel(r),
              channel(g),
              channel(b)
            )
          },
          null
        )
      }
      "image", "video" -> {
        val uri = resourceUri
          ?: invalidArgument("resourceUri is required for $mode background")
        executeIo(
          task = {
            if (mode == "image") {
              val file = resolveReadableFile(uri, IMAGE_EXTENSIONS)
              NosmaiBackgroundSegmentationConfig(
                NosmaiBackgroundSegmentationConfig.Mode.IMAGE
              ).apply {
                replacementImage = decodeBoundedBitmap(file)
              }
            } else {
              val file = resolveReadableFile(uri, VIDEO_EXTENSIONS)
              validateVideo(file)
              NosmaiBackgroundSegmentationConfig(
                NosmaiBackgroundSegmentationConfig.Mode.VIDEO
              ).apply {
                replacementVideoPath = file.absolutePath
              }
            }
          },
          completion = completion
        )
      }
      else -> invalidArgument("mode must be blur, color, image, or video")
    }
  }

  fun applyBackground(config: NosmaiBackgroundSegmentationConfig) {
    requireMainThread()
    NosmaiEffects.setBackgroundSegmentation(config)
  }

  fun discardPreparedBackground(config: NosmaiBackgroundSegmentationConfig) {
    requireMainThread()
    config.replacementImage?.takeUnless(Bitmap::isRecycled)?.recycle()
    config.replacementImage = null
  }

  fun clearBackground() {
    requireMainThread()
    NosmaiEffects.clearBackgroundSegmentation()
  }

  /** Repairs the SDK's coarse state flag after category-specific neutral/removal calls. */
  fun reconcileBuiltInState() {
    requireMainThread()
    val active = hasTrackedBuiltInEffect()
    val sdkActive = runCatching { NosmaiEffectsEngine.hasActiveBeautyFilters() }
      .getOrDefault(false)
    if (active && !sdkActive) {
      NosmaiEffectsEngine.notifyBeautyFiltersApplied()
    } else if (!active && sdkActive) {
      // SDK 3.0.x does not emit from this clear notifier, so the controller
      // explicitly publishes the fresh state after every mutation.
      NosmaiEffectsEngine.notifyBeautyFiltersCleared()
    }
  }

  fun resetTracking() {
    requireMainThread()
    resetBeautyTracking()
    sharpening = 0f
    brightness = 0f
    contrast = 1f
    red = 1f
    green = 1f
    blue = 1f
    grayscale = false
    hue = 0f
    saturation = 1f
    whiteBalanceTemperature = WHITE_BALANCE_NEUTRAL
    whiteBalanceTint = 0f
  }

  /**
   * Authored AR/beauty packages replace face retouch state, but color controls
   * have an independent lifetime and must remain represented in coarse state.
   */
  fun resetBeautyTracking() {
    requireMainThread()
    skinSmoothing = 0f
    skinWhitening = 0f
    teethWhitening = 0f
    reshapeValues.fill(0f)
  }

  fun shutdown() {
    ioExecutor.shutdownNow()
  }

  private fun hasTrackedBuiltInEffect(): Boolean =
    !isNeutral(skinSmoothing) ||
      !isNeutral(skinWhitening) ||
      !isNeutral(teethWhitening) ||
      hasTrackedColorAdjustments() ||
      reshapeValues.any { !isNeutral(it) } ||
      runCatching { NosmaiBeauty.isMakeupActive(NosmaiBeauty.MAKEUP_ALL) }
        .getOrDefault(false) ||
      runCatching { NosmaiBeauty.isEyeLensActive() }.getOrDefault(false)

  private fun hasTrackedColorAdjustments(): Boolean =
    !isNeutral(brightness) ||
      !isNeutral(contrast - 1f) ||
      !isNeutral(red - 1f) ||
      !isNeutral(green - 1f) ||
      !isNeutral(blue - 1f) ||
      !isNeutral(sharpening) ||
      grayscale ||
      !isNeutral(hue) ||
      !isNeutral(saturation - 1f) ||
      !isNeutral(whiteBalanceTemperature - WHITE_BALANCE_NEUTRAL) ||
      !isNeutral(whiteBalanceTint)

  private fun executeIo(
    task: () -> NosmaiBackgroundSegmentationConfig,
    completion: (NosmaiBackgroundSegmentationConfig?, NosmaiVisualException?) -> Unit
  ) {
    try {
      ioExecutor.execute {
        val result = runCatching(task)
        mainHandler.post {
          result.fold(
            onSuccess = { completion(it, null) },
            onFailure = { error ->
              completion(
                null,
                if (error is NosmaiVisualException) {
                  error
                } else {
                  NosmaiVisualException(
                    ERROR_BACKGROUND_RESOURCE,
                    error.message ?: "Unable to prepare the background resource",
                    error
                  )
                }
              )
            }
          )
        }
      }
    } catch (error: RejectedExecutionException) {
      completion(
        null,
        NosmaiVisualException(
          ERROR_BACKGROUND_RESOURCE,
          "The background resource worker is unavailable",
          error
        )
      )
    }
  }

  private fun resolveReadableFile(uriString: String, extensions: Set<String>): File {
    if (uriString.indexOf('\u0000') >= 0) {
      backgroundResourceError("resourceUri contains an invalid NUL character")
    }
    val uri = runCatching { Uri.parse(uriString) }.getOrElse { error ->
      throw NosmaiVisualException(
        ERROR_BACKGROUND_RESOURCE,
        "resourceUri is not a valid file URI",
        error
      )
    }
    if (
      uri.scheme != "file" ||
      uri.query != null ||
      uri.fragment != null ||
      (!uri.authority.isNullOrEmpty() && uri.authority != "localhost")
    ) {
      backgroundResourceError(
        "resourceUri must be an absolute local file:// URI without a query or fragment"
      )
    }
    val path = uri.path?.takeIf { it.startsWith('/') && it.length > 1 }
      ?: backgroundResourceError("resourceUri must contain an absolute file path")
    val file = runCatching { File(path).canonicalFile }.getOrElse { error ->
      throw NosmaiVisualException(
        ERROR_BACKGROUND_RESOURCE,
        "Unable to resolve the background resource path",
        error
      )
    }
    if (!file.isFile || !file.canRead()) {
      backgroundResourceError("The background resource is not a readable file")
    }
    if (file.length() <= 0L) {
      backgroundResourceError("The background resource file is empty")
    }
    val extension = file.extension.lowercase(Locale.US)
    if (extension !in extensions) {
      backgroundResourceError(
        "The background resource has an unsupported file extension"
      )
    }
    FileInputStream(file).use { stream ->
      if (stream.read() < 0) backgroundResourceError("The background resource is empty")
    }
    return file
  }

  private fun decodeBoundedBitmap(file: File): Bitmap {
    if (file.length() > MAX_IMAGE_FILE_BYTES) {
      backgroundResourceError("The background image exceeds the compressed size limit")
    }
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeFile(file.absolutePath, bounds)
    val width = bounds.outWidth
    val height = bounds.outHeight
    if (width <= 0 || height <= 0) {
      backgroundResourceError("The background image could not be decoded")
    }
    if (
      width > MAX_SOURCE_IMAGE_DIMENSION ||
      height > MAX_SOURCE_IMAGE_DIMENSION ||
      width.toLong() * height.toLong() > MAX_SOURCE_IMAGE_PIXELS
    ) {
      backgroundResourceError("The background image dimensions are too large")
    }

    var sampleSize = 1
    while (
      width / sampleSize > MAX_DECODED_IMAGE_DIMENSION ||
      height / sampleSize > MAX_DECODED_IMAGE_DIMENSION ||
      (width.toLong() / sampleSize) * (height.toLong() / sampleSize) >
      MAX_DECODED_IMAGE_PIXELS
    ) {
      sampleSize *= 2
    }

    val bitmap = try {
      BitmapFactory.decodeFile(
        file.absolutePath,
        BitmapFactory.Options().apply {
          inSampleSize = sampleSize
          inPreferredConfig = Bitmap.Config.ARGB_8888
        }
      )
    } catch (error: OutOfMemoryError) {
      throw NosmaiVisualException(
        ERROR_BACKGROUND_RESOURCE,
        "The background image is too large to decode safely",
        error
      )
    } ?: backgroundResourceError("The background image could not be decoded")

    if (
      bitmap.width > MAX_DECODED_IMAGE_DIMENSION ||
      bitmap.height > MAX_DECODED_IMAGE_DIMENSION ||
      bitmap.width.toLong() * bitmap.height.toLong() > MAX_DECODED_IMAGE_PIXELS ||
      bitmap.byteCount.toLong() > MAX_DECODED_IMAGE_BYTES
    ) {
      bitmap.recycle()
      backgroundResourceError("The decoded background image exceeds the memory limit")
    }
    return bitmap
  }

  private fun validateVideo(file: File) {
    val retriever = MediaMetadataRetriever()
    try {
      retriever.setDataSource(file.absolutePath)
      val hasVideo = retriever.extractMetadata(
        MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO
      ) == "yes"
      val width = retriever.extractMetadata(
        MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH
      )?.toIntOrNull() ?: 0
      val height = retriever.extractMetadata(
        MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT
      )?.toIntOrNull() ?: 0
      val duration = retriever.extractMetadata(
        MediaMetadataRetriever.METADATA_KEY_DURATION
      )?.toLongOrNull() ?: 0L
      if ((!hasVideo && (width <= 0 || height <= 0)) || duration <= 0L) {
        backgroundResourceError("The background video has no readable video track")
      }
    } catch (error: NosmaiVisualException) {
      throw error
    } catch (error: Throwable) {
      throw NosmaiVisualException(
        ERROR_BACKGROUND_RESOURCE,
        "The background video could not be opened",
        error
      )
    } finally {
      runCatching { retriever.release() }
    }
  }

  private fun requireNoResourceUri(resourceUri: String?, mode: String) {
    if (resourceUri != null) {
      invalidArgument("resourceUri must be null for $mode background")
    }
  }

  private fun makeupSpec(makeupType: String): MakeupSpec =
    MAKEUP_SPECS[makeupType] ?: invalidArgument(
      "makeupType must be lipstick, eyeshadow, blusher, eyelash, or eyebrow"
    )

  private fun requireFinite(value: Double, field: String): Double {
    if (!value.isFinite()) invalidArgument("$field must be finite")
    return value
  }

  private fun requireRange(
    value: Double,
    field: String,
    minimum: Double,
    maximum: Double
  ): Double {
    requireFinite(value, field)
    if (value < minimum || value > maximum) {
      invalidArgument("$field must be from $minimum through $maximum")
    }
    return value
  }

  private fun normalizedHue(value: Double): Float {
    val normalized = ((value % 360.0) + 360.0) % 360.0
    return if (abs(normalized - 360.0) < EPSILON.toDouble()) {
      0f
    } else {
      normalized.toFloat()
    }
  }

  private fun channel(value: Double): Int =
    (value * 255.0).roundToInt().coerceIn(0, 255)

  private fun isNeutral(value: Float): Boolean = abs(value) <= EPSILON

  private fun requireMainThread() {
    check(Looper.myLooper() == Looper.getMainLooper()) {
      "Visual effects must be mutated on the main looper"
    }
  }

  private fun invalidArgument(message: String): Nothing =
    throw NosmaiVisualException(ERROR_ARGUMENT, message)

  private fun backgroundResourceError(message: String): Nothing =
    throw NosmaiVisualException(ERROR_BACKGROUND_RESOURCE, message)

  private data class MakeupSpec(
    val category: Int,
    val styleIds: Map<String, Int>
  )

  private data class ReshapeSpec(
    val id: Int,
    val minimum: Double,
    val maximum: Double
  )

  companion object {
    private val THREAD_COUNTER = AtomicInteger(0)
    private const val EPSILON = 0.0001f
    private const val WHITE_BALANCE_NEUTRAL = 6500f
    private const val RESHAPE_COUNT = 10
    private const val MAX_IMAGE_FILE_BYTES = 64L * 1024L * 1024L
    private const val MAX_SOURCE_IMAGE_DIMENSION = 16_384
    private const val MAX_SOURCE_IMAGE_PIXELS = 128L * 1024L * 1024L
    private const val MAX_DECODED_IMAGE_DIMENSION = 4_096
    private const val MAX_DECODED_IMAGE_PIXELS = 8L * 1024L * 1024L
    private const val MAX_DECODED_IMAGE_BYTES = 32L * 1024L * 1024L

    const val ERROR_ARGUMENT = "E_INVALID_ARGUMENT"
    const val ERROR_INVALID_STATE = "E_INVALID_STATE"
    const val ERROR_BEAUTY_DISABLED = "E_BEAUTY_DISABLED"
    const val ERROR_ADVANCED_FILTERS_DISABLED = "E_ADVANCED_FILTERS_DISABLED"
    const val ERROR_VISUAL_CONTROL = "E_VISUAL_CONTROL"
    const val ERROR_BACKGROUND_RESOURCE = "E_BACKGROUND_RESOURCE"

    private val IMAGE_EXTENSIONS = setOf("jpg", "jpeg", "png", "webp")
    private val VIDEO_EXTENSIONS = setOf("mp4", "m4v", "mov", "webm", "3gp")

    private val MAKEUP_SPECS = mapOf(
      "lipstick" to MakeupSpec(
        NosmaiBeauty.MAKEUP_LIPSTICK,
        mapOf("classic" to 0, "matte" to 1, "natural" to 2)
      ),
      "eyeshadow" to MakeupSpec(
        NosmaiBeauty.MAKEUP_EYESHADOW,
        mapOf("smokey" to 0, "shimmer" to 1, "natural" to 2)
      ),
      "blusher" to MakeupSpec(
        NosmaiBeauty.MAKEUP_BLUSHER,
        mapOf("round" to 0, "contour" to 1, "natural" to 2)
      ),
      "eyelash" to MakeupSpec(
        NosmaiBeauty.MAKEUP_EYELASH,
        mapOf("natural" to 0, "dramatic" to 1, "wispy" to 2)
      ),
      "eyebrow" to MakeupSpec(
        NosmaiBeauty.MAKEUP_EYEBROW,
        mapOf("natural" to 0, "bold" to 1, "arched" to 2)
      )
    )

    private val RESHAPES = mapOf(
      "lip" to ReshapeSpec(0, -1.0, 1.0),
      "faceSlim" to ReshapeSpec(1, -1.2, 1.2),
      "eye" to ReshapeSpec(2, -1.3, 1.3),
      "nose" to ReshapeSpec(3, -0.5, 0.5),
      "chin" to ReshapeSpec(4, -0.8, 0.8),
      "brow" to ReshapeSpec(5, -1.0, 1.0),
      "browThickness" to ReshapeSpec(6, -1.0, 1.0),
      "jaw" to ReshapeSpec(7, -1.0, 1.0),
      "mouthWidth" to ReshapeSpec(8, -1.0, 1.0),
      "forehead" to ReshapeSpec(9, -1.0, 1.0)
    )
  }
}

/**
 * SDK 3.0.x exposes beauty capability publicly only on its internal facade and
 * has no public advanced-filter query. Reflection is deliberately isolated so
 * a newer AAR can replace this adapter without leaking internals across bridge code.
 */
private object NosmaiCapabilityAdapter {
  private val nosmaiClass: Class<*> by lazy {
    Class.forName("com.nosmai.effect.internal.Nosmai")
  }
  private val beautyMethod by lazy {
    nosmaiClass.getDeclaredMethod("isBeautyEnabled").apply { isAccessible = true }
  }
  private val advancedMethod by lazy {
    nosmaiClass.getDeclaredMethod("nativeIsAdvancedFiltersEnabled").apply {
      isAccessible = true
    }
  }
  private val licenseStatusMethod by lazy {
    nosmaiClass.getDeclaredMethod("getLicenseStatus").apply {
      isAccessible = true
    }
  }

  fun isBeautyEnabled(): Boolean = invokeBoolean(beautyMethod)

  fun isAdvancedFiltersEnabled(): Boolean =
    currentLicenseStatus().equals("VALID", ignoreCase = true) &&
      invokeBoolean(advancedMethod)

  private fun currentLicenseStatus(): String {
    return try {
      licenseStatusMethod.invoke(null)?.toString()?.trim().orEmpty()
    } catch (error: InvocationTargetException) {
      val cause = error.targetException ?: error
      throw NosmaiVisualException(
        NosmaiAndroidVisualEffects.ERROR_VISUAL_CONTROL,
        cause.message ?: "Unable to read the current Nosmai license status",
        cause
      )
    } catch (error: Throwable) {
      throw NosmaiVisualException(
        NosmaiAndroidVisualEffects.ERROR_VISUAL_CONTROL,
        error.message ?: "Unable to read the current Nosmai license status",
        error
      )
    }
  }

  private fun invokeBoolean(method: java.lang.reflect.Method): Boolean {
    return try {
      method.invoke(null) as? Boolean ?: false
    } catch (error: InvocationTargetException) {
      val cause = error.targetException ?: error
      throw NosmaiVisualException(
        NosmaiAndroidVisualEffects.ERROR_VISUAL_CONTROL,
        cause.message ?: "Unable to read the licensed visual capabilities",
        cause
      )
    } catch (error: Throwable) {
      throw NosmaiVisualException(
        NosmaiAndroidVisualEffects.ERROR_VISUAL_CONTROL,
        error.message ?: "Unable to read the licensed visual capabilities",
        error
      )
    }
  }
}
