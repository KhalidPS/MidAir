package com.kotlistine.transferingfilebyair.feature.shelf.presentation

import com.kotlistine.transferingfilebyair.feature.shelf.domain.gesture.HandFrame
import com.kotlistine.transferingfilebyair.feature.shelf.domain.gesture.HandTracker
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/** A camera that sees whatever [frames] emits, and counts who is watching. */
class FakeHandTracker : HandTracker {
    val frames = MutableSharedFlow<HandFrame>()
    var watchers = 0

    /** Thrown as soon as watching starts, like a camera that can't be used. */
    var failure: Throwable? = null

    override fun frames(): Flow<HandFrame> = flow {
        failure?.let { throw it }
        watchers++
        try {
            emitAll(frames)
        } finally {
            watchers--
        }
    }
}
