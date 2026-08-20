package com.nosmai.camerasdk.reactnative

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.WritableArray
import com.facebook.react.bridge.WritableMap
import com.nosmai.effect.NosmaiEffects
import com.nosmai.effect.internal.NosmaiFilter

/** Converts authored .nosmai parameter metadata into bridge-safe values. */
internal object NosmaiEffectParameterAdapter {
  fun currentParameters(): WritableMap {
    val items = Arguments.createArray()
    NosmaiEffects.getEffectParameters()
      .orEmpty()
      .asSequence()
      .take(MAX_PARAMETER_COUNT)
      .mapNotNull(::parameterMap)
      .forEach(items::pushMap)
    return Arguments.createMap().apply { putArray("items", items) }
  }

  private fun parameterMap(parameter: NosmaiFilter.ParameterInfo): WritableMap? {
    val name = safeParameterName(parameter.name) ?: return null
    val type = normalizedParameterType(parameter.type)
    val minimum = parameter.minValue.toDouble().takeIf { it.isFinite() }
    val maximum = parameter.maxValue.toDouble().takeIf { it.isFinite() }
    val hasRange =
      parameter.hasRange && minimum != null && maximum != null && minimum <= maximum

    return Arguments.createMap().apply {
      putString("name", name)
      putString("type", type)
      putString(
        "displayName",
        sanitizeText(parameter.displayName, MAX_DISPLAY_NAME_LENGTH)
          ?.takeIf { it.isNotBlank() }
          ?: name,
      )
      putString(
        "description",
        sanitizeText(parameter.description, MAX_DESCRIPTION_LENGTH).orEmpty(),
      )
      putParameterValue(
        "currentValue",
        type,
        parameter.floatValue,
        parameter.intValue,
        parameter.stringValue,
        parameter.vectorValue,
      )
      putParameterValue(
        "defaultValue",
        type,
        parameter.defaultFloatValue,
        parameter.defaultIntValue,
        parameter.defaultStringValue,
        parameter.defaultVectorValue,
      )
      putBoolean("hasRange", hasRange)
      if (hasRange) {
        putDouble("minValue", minimum!!)
        putDouble("maxValue", maximum!!)
      } else {
        putNull("minValue")
        putNull("maxValue")
      }
      putArray("options", stringArray(parameter.options))
      // The Android SDK snapshot does not expose a stable render-pass ID.
      putNull("passId")
    }
  }

  fun hasNumericParameter(name: String): Boolean =
    NosmaiEffects.getEffectParameters().orEmpty().take(MAX_PARAMETER_COUNT).any { parameter ->
      safeParameterName(parameter.name) == name &&
        normalizedParameterType(parameter.type) in setOf("float", "int", "bool")
    }

  private fun WritableMap.putParameterValue(
    key: String,
    type: String,
    floatValue: Float,
    intValue: Int,
    stringValue: String?,
    vectorValue: FloatArray?,
  ) {
    when (type) {
      "float" -> {
        val value = floatValue.toDouble()
        if (value.isFinite()) putDouble(key, value) else putNull(key)
      }
      "int" -> putInt(key, intValue)
      "bool" -> putBoolean(key, intValue != 0)
      "string" -> {
        val value = sanitizeText(stringValue, MAX_STRING_VALUE_LENGTH)
        if (value == null) putNull(key) else putString(key, value)
      }
      "vector" -> {
        val value = numberArray(vectorValue)
        if (value == null) putNull(key) else putArray(key, value)
      }
      "enum" -> {
        val value = sanitizeText(stringValue, MAX_OPTION_LENGTH)
        if (value != null) putString(key, value) else putInt(key, intValue)
      }
      else -> putNull(key)
    }
  }

  private fun normalizedParameterType(rawType: String?): String =
    when (rawType?.takeIf { it.length <= MAX_PARAMETER_TYPE_LENGTH }?.trim()?.lowercase()) {
      "float", "double", "number" -> "float"
      "int", "integer" -> "int"
      "bool", "boolean" -> "bool"
      "string", "text" -> "string"
      "vector", "vec2", "vec3", "vec4", "color", "color3", "color4" -> "vector"
      "enum", "select", "option" -> "enum"
      else -> "unknown"
    }

  private fun stringArray(values: Array<String>?): WritableArray =
    Arguments.createArray().apply {
      values.orEmpty()
        .asSequence()
        .take(MAX_OPTION_COUNT)
        .mapNotNull { sanitizeText(it, MAX_OPTION_LENGTH) }
        .forEach(::pushString)
    }

  private fun numberArray(values: FloatArray?): WritableArray? {
    if (
      values == null ||
      values.size > MAX_VECTOR_COMPONENT_COUNT ||
      values.any { !it.isFinite() }
    ) {
      return null
    }
    return Arguments.createArray().apply {
      values.forEach { pushDouble(it.toDouble()) }
    }
  }

  private fun safeParameterName(value: String?): String? {
    if (value == null || value.length > MAX_PARAMETER_NAME_LENGTH) return null
    val name = value.trim()
    return name.takeIf { it.isNotEmpty() && !containsControlCharacter(it) }
  }

  private fun sanitizeText(value: String?, maximumLength: Int): String? {
    if (value == null) return null
    val sourceLength = minOf(value.length, maximumLength)
    return buildString(sourceLength) {
      for (index in 0 until sourceLength) {
        val character = value[index]
        if (character.code >= 0x20 && character.code != 0x7f) append(character)
      }
    }
  }

  private fun containsControlCharacter(value: String): Boolean =
    value.any { it.code < 0x20 || it.code == 0x7f }

  private const val MAX_PARAMETER_COUNT = 256
  private const val MAX_PARAMETER_NAME_LENGTH = 128
  private const val MAX_PARAMETER_TYPE_LENGTH = 64
  private const val MAX_DISPLAY_NAME_LENGTH = 256
  private const val MAX_DESCRIPTION_LENGTH = 2_048
  private const val MAX_STRING_VALUE_LENGTH = 16_384
  private const val MAX_VECTOR_COMPONENT_COUNT = 64
  private const val MAX_OPTION_COUNT = 128
  private const val MAX_OPTION_LENGTH = 256
}
