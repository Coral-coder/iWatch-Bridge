package com.coralcoder.iwatchbridge.core

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Notifications waiting for (or already sent to) the active watch. */
object NotificationQueue {
    data class Item(val key: String, val app: String, val appName: String, val title: String, val body: String, val postedAt: Long)

    /** Apps the user turned off under Notifications, like the iPhone's per-app mirror switches. */
    val mutedApps = MutableStateFlow<Set<String>>(emptySet())

    private val _items = MutableStateFlow<List<Item>>(emptyList())
    val items: StateFlow<List<Item>> = _items.asStateFlow()

    fun post(item: Item) {
        if (item.app in mutedApps.value) return
        _items.value = (_items.value.filterNot { it.key == item.key } + item).takeLast(100)
    }

    fun remove(key: String) { _items.value = _items.value.filterNot { it.key == key } }
}
