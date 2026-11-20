package com.kotlistine.midair.core.transfer.domain

/**
 * Keeps the app alive while files move, even if the user leaves it or locks the phone (a foreground service with a
 * visible notice on Android). Holds can overlap; the app is let go when the last one is released.
 */
fun interface TransferKeepAlive {
    /** Starts keeping the app alive, telling the user [description] ("Sending “Q4 Report.pdf”"). Returns the release. */
    fun hold(description: String): () -> Unit

    companion object {
        /** For platforms (and tests) where nothing needs to be kept alive. */
        val None = TransferKeepAlive { {} }
    }
}

/** Runs [block] with the app kept alive, released however [block] ends. */
suspend fun <T> TransferKeepAlive.keeping(description: String, block: suspend () -> T): T {
    val release = hold(description)
    try {
        return block()
    } finally {
        release()
    }
}
