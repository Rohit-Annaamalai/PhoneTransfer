package com.example.phonetransfer

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.FileInputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.concurrent.Executors

/**
 * Tiny HTTP/1.1 server written on raw sockets so big files stream straight
 * between the network and storage (no buffering whole files in memory).
 *
 *  GET    /            web page for the laptop
 *  GET    /api/list    JSON of files shared by the phone + files received
 *  GET    /dl/<id>     download a shared file (supports Range)
 *  PUT    /up?name=X   upload a file from the laptop -> Downloads/PhoneTransfer
 */
class FileServer(private val ctx: Context, private val port: Int = Shared.PORT) {
    private var server: ServerSocket? = null
    private val pool = Executors.newCachedThreadPool()
    @Volatile private var running = false

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(port)) // throws if the port is taken
        server = ss
        running = true
        Thread {
            while (running) {
                try {
                    val s = ss.accept()
                    pool.execute { handle(s) }
                } catch (e: Exception) {
                    if (!running) break
                }
            }
        }.apply { isDaemon = true; name = "pt-accept" }.start()
    }

    fun stop() {
        running = false
        try { server?.close() } catch (_: Exception) {}
        server = null
        pool.shutdownNow()
    }

    // ---------------------------------------------------------------- connection loop

    private fun handle(s: Socket) {
        try {
            s.tcpNoDelay = true
            s.sendBufferSize = 1 shl 20
            s.receiveBufferSize = 1 shl 20
            s.soTimeout = 60_000
            val input = BufferedInputStream(s.getInputStream(), 1 shl 16)
            val output = BufferedOutputStream(s.getOutputStream(), 1 shl 16)
            while (running) {
                if (!serveOne(input, output)) break
            }
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    /** Returns true if the connection can be reused for another request. */
    private fun serveOne(inp: InputStream, out: OutputStream): Boolean {
        val requestLine = readLine(inp) ?: return false
        val parts = requestLine.split(" ")
        if (parts.size < 2) return false
        val method = parts[0]
        val target = parts[1]

        val headers = HashMap<String, String>()
        while (true) {
            val line = readLine(inp) ?: return false
            if (line.isEmpty()) break
            val i = line.indexOf(':')
            if (i > 0) headers[line.substring(0, i).trim().lowercase()] = line.substring(i + 1).trim()
        }

        val qi = target.indexOf('?')
        val path = if (qi >= 0) target.substring(0, qi) else target
        val query = HashMap<String, String>()
        if (qi >= 0) {
            for (kv in target.substring(qi + 1).split("&")) {
                val e = kv.indexOf('=')
                if (e > 0) query[kv.substring(0, e)] = URLDecoder.decode(kv.substring(e + 1), "UTF-8")
            }
        }

        return when {
            method == "GET" && path == "/" -> {
                val html = ctx.assets.open("index.html").use { it.readBytes() }
                respond(out, 200, "OK", "text/html; charset=utf-8", html)
            }
            method == "GET" && path == "/api/list" ->
                respond(out, 200, "OK", "application/json; charset=utf-8", listJson().toByteArray())
            method == "GET" && path.startsWith("/dl/") -> download(out, path, headers)
            method == "PUT" && path == "/up" -> upload(inp, out, query["name"], headers)
            else -> respond(out, 404, "Not Found", "text/plain", "Not found".toByteArray())
        }
    }

    // ---------------------------------------------------------------- routes

    private fun listJson(): String {
        val sb = StringBuilder("{\"phone\":[")
        Shared.files.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("{\"id\":").append(f.id).append(",\"name\":").append(jstr(f.name))
                .append(",\"size\":").append(f.size).append('}')
        }
        sb.append("],\"received\":[")
        Shared.received.forEachIndexed { i, f ->
            if (i > 0) sb.append(',')
            sb.append("{\"name\":").append(jstr(f.name)).append(",\"size\":").append(f.size).append('}')
        }
        sb.append("]}")
        return sb.toString()
    }

    private fun download(out: OutputStream, path: String, headers: Map<String, String>): Boolean {
        val id = path.removePrefix("/dl/").toIntOrNull()
        val f = Shared.files.firstOrNull { it.id == id }
            ?: return respond(out, 404, "Not Found", "text/plain", "Not found".toByteArray())
        val pfd: ParcelFileDescriptor = try {
            ctx.contentResolver.openFileDescriptor(f.uri, "r")
        } catch (e: Exception) { null }
            ?: return respond(out, 404, "Not Found", "text/plain", "Cannot open file".toByteArray())

        pfd.use {
            val size = if (pfd.statSize >= 0) pfd.statSize else f.size
            if (size < 0) return respond(out, 500, "Error", "text/plain", "Unknown size".toByteArray())

            var start = 0L
            var end = size - 1
            var partial = false
            val m = Regex("bytes=(\\d*)-(\\d*)").find(headers["range"] ?: "")
            if (m != null && (m.groupValues[1].isNotEmpty() || m.groupValues[2].isNotEmpty())) {
                if (m.groupValues[1].isNotEmpty()) {
                    start = m.groupValues[1].toLong()
                    if (m.groupValues[2].isNotEmpty()) end = m.groupValues[2].toLong()
                } else {
                    start = maxOf(0L, size - m.groupValues[2].toLong())
                }
                end = minOf(end, size - 1)
                partial = true
            }
            val length = end - start + 1
            val encName = URLEncoder.encode(f.name, "UTF-8").replace("+", "%20")
            val head = StringBuilder()
                .append(if (partial) "HTTP/1.1 206 Partial Content\r\n" else "HTTP/1.1 200 OK\r\n")
                .append("Content-Type: application/octet-stream\r\n")
                .append("Accept-Ranges: bytes\r\n")
                .append("Content-Length: ").append(length).append("\r\n")
                .append("Content-Disposition: attachment; filename*=UTF-8''").append(encName).append("\r\n")
            if (partial) head.append("Content-Range: bytes ").append(start).append('-').append(end).append('/').append(size).append("\r\n")
            head.append("\r\n")
            out.write(head.toString().toByteArray())

            FileInputStream(pfd.fileDescriptor).use { fis ->
                fis.channel.position(start)
                val buf = ByteArray(256 * 1024)
                var left = length
                while (left > 0) {
                    val n = fis.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    out.write(buf, 0, n)
                    left -= n
                }
            }
            out.flush()
            return true
        }
    }

    private fun upload(inp: InputStream, out: OutputStream, rawName: String?, headers: Map<String, String>): Boolean {
        val total = headers["content-length"]?.toLongOrNull()
            ?: return respond(out, 411, "Length Required", "text/plain", "Length required".toByteArray()).let { false }
        val name = sanitize(rawName ?: "file")

        val resolver = ctx.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/PhoneTransfer")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
            ?: return respond(out, 500, "Error", "text/plain", "Cannot create file".toByteArray()).let { false }

        var ok = false
        try {
            val pfd = resolver.openFileDescriptor(uri, "w") ?: throw IllegalStateException("no fd")
            ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { os ->
                val buf = ByteArray(256 * 1024)
                var left = total
                while (left > 0) {
                    val n = inp.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) throw java.io.EOFException("interrupted")
                    os.write(buf, 0, n)
                    left -= n
                }
            }
            ok = true
        } catch (_: Exception) {
        }

        if (!ok) {
            try { resolver.delete(uri, null, null) } catch (_: Exception) {}
            return false // drop the connection
        }
        val done = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        resolver.update(uri, done, null, null)
        Shared.received.add(ReceivedFile(name, total))
        return respond(out, 200, "OK", "text/plain", "ok".toByteArray())
    }

    // ---------------------------------------------------------------- helpers

    private fun respond(out: OutputStream, code: Int, status: String, type: String, body: ByteArray): Boolean {
        val head = "HTTP/1.1 $code $status\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\n\r\n"
        out.write(head.toByteArray())
        out.write(body)
        out.flush()
        return true
    }

    private fun readLine(i: InputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = i.read()
            if (c < 0) return if (sb.isEmpty()) null else sb.toString()
            if (c == '\n'.code) return sb.toString()
            if (c != '\r'.code) sb.append(c.toChar())
            if (sb.length > 16_384) return null
        }
    }

    private fun sanitize(n: String): String {
        val base = n.substringAfterLast('/').substringAfterLast('\\').trim()
        return base.replace(Regex("[<>:\"|?*\\u0000-\\u001f]"), "_").ifEmpty { "file" }
    }

    private fun jstr(s: String): String {
        val sb = StringBuilder("\"")
        for (c in s) {
            when {
                c == '"' -> sb.append("\\\"")
                c == '\\' -> sb.append("\\\\")
                c < ' ' -> sb.append(String.format("\\u%04x", c.code))
                else -> sb.append(c)
            }
        }
        return sb.append('"').toString()
    }
}
