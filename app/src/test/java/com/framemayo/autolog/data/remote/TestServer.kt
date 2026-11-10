package com.framemayo.autolog.data.remote

import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class TestServer(private val handler: (method: String, path: String, auth: String?, body: String) -> Pair<Int, String>) {
    private val socket = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val url: String get() = "http://127.0.0.1:${socket.localPort}"

    init {
        thread(isDaemon = true) {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                client.use { c ->
                    runCatching {
                        val input = c.getInputStream().buffered()
                        fun line(): String {
                            val sb = StringBuilder()
                            while (true) {
                                val b = input.read()
                                if (b < 0 || b == '\n'.code) break
                                if (b != '\r'.code) sb.append(b.toChar())
                            }
                            return sb.toString()
                        }
                        val (method, path) = line().split(" ").let { it[0] to it[1] }
                        var auth: String? = null
                        var length = 0
                        while (true) {
                            val h = line()
                            if (h.isEmpty()) break
                            val (k, v) = h.split(":", limit = 2).let { it[0].lowercase() to it.getOrElse(1) { "" }.trim() }
                            if (k == "authorization") auth = v
                            if (k == "content-length") length = v.toInt()
                        }
                        val body = String(input.readNBytes(length))
                        val (code, text) = handler(method, path, auth, body)
                        val bytes = text.toByteArray()
                        c.getOutputStream().apply {
                            write("HTTP/1.1 $code X\r\nContent-Type: application/json\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            write(bytes)
                            flush()
                        }
                    }
                }
            }
        }
    }

    fun stop() = socket.close()
}
