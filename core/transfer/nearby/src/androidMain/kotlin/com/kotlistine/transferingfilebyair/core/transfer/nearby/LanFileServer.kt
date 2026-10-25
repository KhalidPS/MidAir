package com.kotlistine.transferingfilebyair.core.transfer.nearby

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import kotlin.concurrent.thread

private const val Tag = "AirLan"

/** A request that hasn't finished its headers by then is dropped. */
private const val RequestTimeoutMillis = 15_000

/** How long the other phone gets to answer before it counts as not on this Wi-Fi. */
private const val ReachTimeoutMillis = 600

private const val CopyBufferBytes = 64 * 1024

/**
 * Serves shared files to the paired phone over the Wi-Fi network both are on, as plain HTTP with byte ranges: what a
 * video player reads natively, a range at a time, each on a connection of its own. It doesn't share the Nearby
 * connection with the card's moves, and isn't subject to its limits (one connection, no priorities).
 *
 * Only files shared right now are served, and only with the random token handed out with each share, so another
 * device on the network can't read anything. Started on the first share; the port is whatever is free.
 */
internal class LanFileServer(context: Context) {

    private val appContext = context.applicationContext
    private val random = SecureRandom()

    private class SharedFile(val uri: String, val token: String)

    /** By file id. */
    private val files = ConcurrentHashMap<String, SharedFile>()

    @Volatile
    private var server: ServerSocket? = null

    /**
     * Starts serving [uri] as [fileId] and returns where the other phone can read it, or null when this phone isn't
     * on a Wi-Fi network (or the server couldn't start).
     */
    fun share(fileId: String, uri: String): String? {
        val address = wifiAddress() ?: return null
        val port = start() ?: return null
        val token = ByteArray(16).also(random::nextBytes).joinToString("") { "%02x".format(it) }
        files[fileId] = SharedFile(uri, token)
        return "http://$address:$port/v/${URLEncoder.encode(fileId, "UTF-8")}?t=$token"
    }

    fun unshare(fileId: String) {
        files.remove(fileId)
    }

    fun unshareAll() {
        files.clear()
    }

    @Synchronized
    private fun start(): Int? {
        server?.takeIf { !it.isClosed }?.let { return it.localPort }
        return try {
            val socket = ServerSocket(0)
            server = socket
            thread(name = "AirLanServer", isDaemon = true) { accept(socket) }
            Log.i(Tag, "Serving on port ${socket.localPort}")
            socket.localPort
        } catch (e: IOException) {
            Log.w(Tag, "Couldn't start serving", e)
            null
        }
    }

    private fun accept(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: IOException) {
                return
            }
            thread(name = "AirLanRequest", isDaemon = true) {
                try {
                    client.use(::serve)
                } catch (_: IOException) {
                    // The reader went away mid-answer (the player jumped elsewhere): nothing to do.
                }
            }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = RequestTimeoutMillis
        val reader = BufferedReader(InputStreamReader(client.getInputStream(), Charsets.ISO_8859_1))
        val requestLine = reader.readLine() ?: return
        var rangeHeader: String? = null
        while (true) {
            val line = reader.readLine() ?: return
            if (line.isEmpty()) break
            val name = line.substringBefore(':').trim()
            if (name.equals("Range", ignoreCase = true)) rangeHeader = line.substringAfter(':').trim()
        }
        val out = client.getOutputStream().buffered(CopyBufferBytes)
        val parts = requestLine.split(' ')
        val method = parts.getOrNull(0)
        val target = parts.getOrNull(1)
        if ((method != "GET" && method != "HEAD") || target == null) return out.respond(405, "Method Not Allowed")
        val fileId = URLDecoder.decode(target.substringBefore('?').removePrefix("/v/"), "UTF-8")
        val token = target.substringAfter("?t=", missingDelimiterValue = "").substringBefore('&')
        val shared = files[fileId]
        if (shared == null || !MessageDigest.isEqual(shared.token.toByteArray(), token.toByteArray())) {
            return out.respond(404, "Not Found")
        }
        val descriptor = try {
            appContext.contentResolver.openFileDescriptor(Uri.parse(shared.uri), "r")
        } catch (_: Exception) {
            null
        } ?: return out.respond(404, "Not Found")
        ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
            val size = descriptor.statSize
            val (status, first, length) = when (val range = parseByteRange(rangeHeader, size)) {
                ByteRangeRequest.Whole -> Triple(200, 0L, size)
                is ByteRangeRequest.Part -> Triple(206, range.first, range.length)
                ByteRangeRequest.Unsatisfiable -> return out.respond(416, "Range Not Satisfiable", "Content-Range: bytes */$size")
            }
            val headers = buildList {
                add("Content-Type: application/octet-stream")
                add("Accept-Ranges: bytes")
                add("Content-Length: $length")
                if (status == 206) add("Content-Range: bytes $first-${first + length - 1}/$size")
            }
            out.respond(status, if (status == 206) "Partial Content" else "OK", *headers.toTypedArray(), flush = false)
            if (method == "HEAD") return out.flush()
            input.channel.position(first)
            val buffer = ByteArray(CopyBufferBytes)
            var remaining = length
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
                if (read < 0) break
                out.write(buffer, 0, read)
                remaining -= read
            }
            out.flush()
        }
    }

    private fun OutputStream.respond(status: Int, reason: String, vararg headers: String, flush: Boolean = true) {
        val head = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            headers.forEach { append(it).append("\r\n") }
            if (headers.none { it.startsWith("Content-Length") }) append("Content-Length: 0\r\n")
            append("Connection: close\r\n\r\n")
        }
        write(head.toByteArray(Charsets.ISO_8859_1))
        if (flush) flush()
    }
}

/** Whether [url], a file shared by the other phone, answers from here: both phones are on the same Wi-Fi. */
internal fun answersFromHere(url: String): Boolean {
    val parsed = try {
        URL(url)
    } catch (_: Exception) {
        return false
    }
    // Only a device on the local network, never somewhere the peer could point this phone at.
    if (parsed.protocol != "http" || !isLocalAddress(parsed.host)) return false
    return try {
        val connection = parsed.openConnection() as HttpURLConnection
        connection.requestMethod = "HEAD"
        connection.connectTimeout = ReachTimeoutMillis
        connection.readTimeout = ReachTimeoutMillis
        val code = connection.responseCode
        connection.disconnect()
        code == HttpURLConnection.HTTP_OK
    } catch (e: IOException) {
        Log.i(Tag, "The other phone's Wi-Fi address doesn't answer from here: ${e.message}")
        false
    }
}

private fun isLocalAddress(host: String): Boolean {
    // A literal address: no lookup happens.
    if (!host.all { it.isDigit() || it == '.' }) return false
    val address = try {
        InetAddress.getByName(host)
    } catch (_: Exception) {
        return false
    }
    return address is Inet4Address && address.isSiteLocalAddress
}

/** This phone's address on the Wi-Fi network it is joined to (not a Wi-Fi Direct group), or null. */
private fun wifiAddress(): String? = try {
    NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
        .filter { it.isUp && !it.isLoopback && it.name.startsWith("wlan") }
        // wlan0 is the network the phone joined; another wlan can be a hotspot Nearby opened.
        .sortedBy { it.name != "wlan0" }
        .flatMap { it.inetAddresses.toList() }
        .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        ?.hostAddress
} catch (_: Exception) {
    null
}
