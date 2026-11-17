package com.kotlistine.transferingfilebyair.feature.shelf.data.gesture

import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/** Spike probe: runs only with HAND_PROBE set; reads the webcam for HAND_PROBE seconds and prints every frame. */
class HandModelsProbe {

    @Test
    fun probe() {
        val seconds = System.getenv("HAND_PROBE")?.toLongOrNull() ?: return
        val opened = System.currentTimeMillis()
        var start = 0L
        var count = 0
        runBlocking {
            WebcamHandTracker().frames()
                .takeWhile { if (start == 0L) { start = it.atMillis; println("PROBE first frame after ${start - opened} ms") }; it.atMillis - start < seconds * 1000 }
                .collect { frame ->
                    count++
                    println("PROBE t=${frame.atMillis - start} ${frame.pose} x=%.2f y=%.2f size=%.2f".format(frame.x, frame.y, frame.size))
                }
        }
        println("PROBE $count frames in $seconds s")
    }
}
