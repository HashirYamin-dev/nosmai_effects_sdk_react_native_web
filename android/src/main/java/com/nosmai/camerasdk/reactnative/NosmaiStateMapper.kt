package com.nosmai.camerasdk.reactnative

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.nosmai.effect.NosmaiEffects
import com.nosmai.effect.NosmaiEffectsEngine
import com.nosmai.effect.NosmaiFilterInfo
import com.nosmai.effect.NosmaiPipelineState

/** Converts the native SDK's authoritative pipeline state into the JS contract. */
internal object NosmaiStateMapper {
  fun currentPipelineState(): WritableMap =
    toWritableMap(NosmaiEffects.getCurrentPipelineState())

  fun toWritableMap(state: NosmaiPipelineState?): WritableMap {
    val map = Arguments.createMap()
    val mode = state?.mode ?: NosmaiPipelineState.Mode.IDLE
    val backgroundSource =
      state?.backgroundSource ?: NosmaiPipelineState.BackgroundSource.NONE
    val activeFilterPath = state?.activeFilterPath
    val activeEffectPath = state?.activeEffectPath
    val activeBackgroundPath = state?.activeBackgroundPackagePath
    val activeFilterInfo = activeFilterPath
      ?.takeIf(String::isNotBlank)
      ?.let { runCatching { NosmaiEffects.getActiveFilterInfo() }.getOrNull() }
    val activeEffectInfo = activeEffectPath
      ?.takeIf(String::isNotBlank)
      ?.let { runCatching { NosmaiEffects.getActiveEffectInfo() }.getOrNull() }

    map.putInt("mode", mode.value)
    map.putString("modeName", modeName(mode))
    putNullableString(map, "activeFilterPath", activeFilterPath)
    putNullableString(map, "activeEffectPath", activeEffectPath)
    putNullableString(map, "activeBackgroundPath", activeBackgroundPath)
    putNullableString(
      map,
      "activeBackgroundPackagePath",
      activeBackgroundPath
    )
    map.putBoolean("hasBackground", state?.isBackgroundActive == true)
    map.putBoolean("backgroundActive", state?.isBackgroundActive == true)
    map.putInt("backgroundSource", backgroundSource.value)
    map.putString(
      "backgroundSourceName",
      backgroundSourceName(backgroundSource)
    )
    map.putBoolean(
      "hasBeautyEffect",
      activeEffectInfo?.type == NosmaiFilterInfo.Type.BEAUTY_EFFECT
    )
    map.putBoolean(
      "hasBuiltInBeauty",
      runCatching { NosmaiEffectsEngine.hasActiveBeautyFilters() }
        .getOrDefault(false)
    )
    map.putBoolean("hasManualBackground", state?.activeBackgroundConfig != null)
    map.putBoolean(
      "hasManualBackgroundConfig",
      state?.activeBackgroundConfig != null
    )
    putNullableMap(map, "activeFilterInfo", filterInfoToWritableMap(activeFilterInfo))
    putNullableMap(map, "activeEffectInfo", filterInfoToWritableMap(activeEffectInfo))

    return map
  }

  fun filterInfoToWritableMap(info: NosmaiFilterInfo?): WritableMap? {
    if (info == null) return null

    val map = Arguments.createMap()
    info.toMap().forEach { (key, value) -> putValue(map, key, value) }

    info.path?.takeIf(String::isNotBlank)?.let { path ->
      map.putString("path", path)
      map.putString("effectPath", path)
    }
    map.putString("filterType", packageTypeName(info.type))

    val raw = info.toMap()
    if (!raw.containsKey("type")) map.putString("type", "local")
    if (!raw.containsKey("isDownloaded")) map.putBoolean("isDownloaded", true)

    return map
  }

  private fun modeName(mode: NosmaiPipelineState.Mode): String = when (mode) {
    NosmaiPipelineState.Mode.EFFECTS_FILTERS -> "effectsFilters"
    NosmaiPipelineState.Mode.FILTERS_BACKGROUND -> "filtersBackground"
    NosmaiPipelineState.Mode.BEAUTY_FILTERS -> "beautyFilters"
    NosmaiPipelineState.Mode.BEAUTY_BACKGROUND -> "beautyBackground"
    NosmaiPipelineState.Mode.IDLE -> "idle"
  }

  private fun backgroundSourceName(
    source: NosmaiPipelineState.BackgroundSource
  ): String = when (source) {
    NosmaiPipelineState.BackgroundSource.MANUAL -> "manual"
    NosmaiPipelineState.BackgroundSource.FILTER -> "filter"
    NosmaiPipelineState.BackgroundSource.PACKAGE -> "package"
    NosmaiPipelineState.BackgroundSource.EFFECT -> "effect"
    NosmaiPipelineState.BackgroundSource.NONE -> "none"
  }

  private fun packageTypeName(type: NosmaiFilterInfo.Type): String = when (type) {
    NosmaiFilterInfo.Type.EFFECT -> "effect"
    NosmaiFilterInfo.Type.BEAUTY_EFFECT -> "beauty_effect"
    NosmaiFilterInfo.Type.BACKGROUND -> "background"
    NosmaiFilterInfo.Type.GAME -> "game"
    NosmaiFilterInfo.Type.FILTER -> "filter"
    NosmaiFilterInfo.Type.UNKNOWN -> "filter"
  }

  private fun putNullableString(map: WritableMap, key: String, value: String?) {
    if (value == null) map.putNull(key) else map.putString(key, value)
  }

  private fun putNullableMap(map: WritableMap, key: String, value: WritableMap?) {
    if (value == null) map.putNull(key) else map.putMap(key, value)
  }

  private fun putValue(map: WritableMap, key: String, value: Any?) {
    when (value) {
      null -> map.putNull(key)
      is Boolean -> map.putBoolean(key, value)
      is Byte, is Short, is Int -> map.putInt(key, (value as Number).toInt())
      is Number -> map.putDouble(key, value.toDouble())
      is String -> map.putString(key, value)
      is Map<*, *> -> map.putMap(key, mapToWritableMap(value))
      is Iterable<*> -> map.putArray(key, iterableToWritableArray(value))
      is Array<*> -> map.putArray(key, iterableToWritableArray(value.asIterable()))
      is BooleanArray -> map.putArray(key, iterableToWritableArray(value.asIterable()))
      is ByteArray -> map.putArray(key, iterableToWritableArray(value.asIterable()))
      is ShortArray -> map.putArray(key, iterableToWritableArray(value.asIterable()))
      is IntArray -> map.putArray(key, iterableToWritableArray(value.asIterable()))
      is LongArray -> map.putArray(key, iterableToWritableArray(value.asIterable()))
      is FloatArray -> map.putArray(key, iterableToWritableArray(value.asIterable()))
      is DoubleArray -> map.putArray(key, iterableToWritableArray(value.asIterable()))
      else -> map.putString(key, value.toString())
    }
  }

  private fun mapToWritableMap(source: Map<*, *>): WritableMap {
    val map = Arguments.createMap()
    source.forEach { (key, value) ->
      if (key != null) putValue(map, key.toString(), value)
    }
    return map
  }

  private fun iterableToWritableArray(source: Iterable<*>): WritableArray {
    val array = Arguments.createArray()
    source.forEach { value -> putArrayValue(array, value) }
    return array
  }

  private fun putArrayValue(array: WritableArray, value: Any?) {
    when (value) {
      null -> array.pushNull()
      is Boolean -> array.pushBoolean(value)
      is Byte, is Short, is Int -> array.pushInt((value as Number).toInt())
      is Number -> array.pushDouble(value.toDouble())
      is String -> array.pushString(value)
      is Map<*, *> -> array.pushMap(mapToWritableMap(value))
      is Iterable<*> -> array.pushArray(iterableToWritableArray(value))
      is Array<*> -> array.pushArray(iterableToWritableArray(value.asIterable()))
      else -> array.pushString(value.toString())
    }
  }
}
