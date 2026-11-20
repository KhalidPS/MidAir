package com.kotlistine.midair.core.transfer.lan

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.URI

/** A file to send, open for reading. */
class OpenedFile(val stream: InputStream, val sizeBytes: Long)

/** Where an incoming file is written: [location] is what [com.kotlistine.midair.core.transfer.domain.PeerLink.receiveFile] hands back. */
class IncomingFile(val location: String, val stream: OutputStream, val discard: () -> Unit)

/** The platform's files, as the LAN link reads and writes them (a content resolver on Android, the disk on desktop). */
interface LanFiles {
    /** The file at [uri] (a platform file reference), or null if it can't be read. */
    fun open(uri: String): OpenedFile?

    /** A new, empty file for incoming transfer [transferId]. */
    fun createIncoming(transferId: Long): IncomingFile

    /**
     * A file named [name] in the same scratch folder, for this device to fill as it likes: what has come over of a
     * file read a piece at a time.
     */
    fun scratch(name: String): File
}

/**
 * Files on the disk: `file:` URIs or plain paths to read, and incoming files in [incomingFolder] (a scratch folder:
 * the shelf copies a finished file to where received files are kept).
 *
 * Made once, when the app starts: what the folder still holds then is left over from before (nothing can be on its
 * way yet), and goes, or every received file would stay there twice for good.
 */
class DiskLanFiles(private val incomingFolder: File) : LanFiles {

    init {
        incomingFolder.listFiles()?.forEach { runCatching { it.delete() } }
    }

    override fun open(uri: String): OpenedFile? = runCatching {
        val file = if (uri.startsWith("file:")) File(URI(uri)) else File(uri)
        OpenedFile(file.inputStream().buffered(), file.length())
    }.getOrNull()

    override fun scratch(name: String): File {
        incomingFolder.mkdirs()
        return File(incomingFolder, name)
    }

    override fun createIncoming(transferId: Long): IncomingFile {
        incomingFolder.mkdirs()
        val file = File(incomingFolder, "incoming-$transferId")
        val stream = file.outputStream().buffered()
        return IncomingFile(
            location = file.toURI().toString(),
            stream = stream,
            discard = {
                runCatching { stream.close() }
                file.delete()
            },
        )
    }
}
