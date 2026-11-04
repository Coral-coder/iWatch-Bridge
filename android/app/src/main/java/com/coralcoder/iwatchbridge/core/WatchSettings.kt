package com.coralcoder.iwatchbridge.core

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Settings the user changes in the app for each watch (wrist, 24-hour time, wake on wrist
 * raise...). They're saved per watch and pushed to the watch through preferences sync
 * (com.apple.private.alloy.preferencessync) once the IDS session is up; [pending] holds what
 * hasn't been delivered yet.
 */
class WatchSettings(context: Context) {
    private val prefs = context.getSharedPreferences("watch_settings", Context.MODE_PRIVATE)
    private val _values = MutableStateFlow(load())
    val values: StateFlow<Map<String, Boolean>> = _values.asStateFlow()
    private val _pending = MutableStateFlow(prefs.getStringSet("pending", emptySet())!!.toSet())
    val pending: StateFlow<Set<String>> = _pending.asStateFlow()

    private fun load(): Map<String, Boolean> = prefs.all.filterValues { it is Boolean }.mapValues { it.value as Boolean }

    fun get(watchId: String, key: String, default: Boolean) = _values.value["$watchId/$key"] ?: default

    fun set(watchId: String, key: String, value: Boolean) {
        val k = "$watchId/$key"
        prefs.edit().putBoolean(k, value).putStringSet("pending", _pending.value + k).apply()
        _values.value = _values.value + (k to value)
        _pending.value = _pending.value + k
    }

    fun delivered(keys: Set<String>) {
        _pending.value = _pending.value - keys
        prefs.edit().putStringSet("pending", _pending.value).apply()
    }
}
