package com.kotlistine.midair.feature.shelf.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * This device's items that were sent, hidden from the shelf until they are removed for good or brought back.
 *
 * App-wide, not kept by one screen: a file goes on moving after its screen is closed, so a screen opened meanwhile
 * must still hide its item, and the send that ends later must still find it here.
 */
class SentItems {
    private val _ids = MutableStateFlow<Set<String>>(emptySet())
    val ids: StateFlow<Set<String>> = _ids.asStateFlow()

    operator fun contains(id: String): Boolean = id in _ids.value

    /** Marks [id] as sent; false when it was already. */
    fun add(id: String): Boolean = id !in _ids.getAndUpdate { it + id }

    /** Brings [id] back; false when it wasn't marked. */
    fun remove(id: String): Boolean = id in _ids.getAndUpdate { it - id }

    fun clear() {
        _ids.value = emptySet()
    }
}
