package com.neurodoc

import com.facebook.react.bridge.ReadableArray
import com.facebook.react.bridge.ReadableMap

/**
 * Extension functions for ReadableMap to provide safe, concise parameter extraction
 * in React Native bridge methods.
 *
 * All functions check both hasKey and isNull to correctly handle:
 *   - missing keys (hasKey returns false)
 *   - explicit null values passed from JS
 */

internal fun ReadableMap.getStringOrNull(key: String): String? =
    if (hasKey(key) && !isNull(key)) getString(key) else null

internal fun ReadableMap.getBooleanOrDefault(key: String, default: Boolean = false): Boolean =
    if (hasKey(key) && !isNull(key)) getBoolean(key) else default

internal fun ReadableMap.getIntOrDefault(key: String, default: Int = 0): Int =
    if (hasKey(key) && !isNull(key)) getInt(key) else default

internal fun ReadableMap.getDoubleOrNull(key: String): Double? =
    if (hasKey(key) && !isNull(key)) getDouble(key) else null

internal fun ReadableMap.getFloatOrDefault(key: String, default: Float): Float =
    if (hasKey(key) && !isNull(key)) getDouble(key).toFloat() else default

internal fun ReadableMap.getArrayOrNull(key: String): ReadableArray? =
    if (hasKey(key) && !isNull(key)) getArray(key) else null

/** Returns a List<Int> from a ReadableArray field, or null if the key is absent/null. */
internal fun ReadableMap.getIntListOrNull(key: String): List<Int>? =
    getArrayOrNull(key)?.let { arr -> (0 until arr.size()).map { arr.getInt(it) } }
