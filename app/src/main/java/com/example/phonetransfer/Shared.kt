package com.example.phonetransfer

import android.net.Uri
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

data class SharedFile(val id: Int, val name: String, val size: Long, val uri: Uri)
data class ReceivedFile(val name: String, val size: Long)

/** State shared between the screen, the foreground service and the HTTP server. */
object Shared {
    const val PORT = 8765

    val files = CopyOnWriteArrayList<SharedFile>()      // phone -> laptop (picked by the user)
    val received = CopyOnWriteArrayList<ReceivedFile>() // laptop -> phone (this session)
    private val nextId = AtomicInteger(1)

    @Volatile var running = false
    @Volatile var error: String? = null

    fun addFile(name: String, size: Long, uri: Uri) {
        if (files.any { it.uri == uri }) return
        files.add(SharedFile(nextId.getAndIncrement(), name, size, uri))
    }
}
