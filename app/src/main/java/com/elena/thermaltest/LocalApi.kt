package com.elena.thermaltest

import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

/**
 * Pelayan HTTP kecil pada 127.0.0.1 (hanya dalam telefon ini) supaya app PV report boleh:
 *  /ping   semak Thermal Snap sedang jalan
 *  /arm    sedia terima gambar SNAP seterusnya (+ buka ThermVue; /arm?open=0 = tanpa buka)
 *  /take   ambil gambar yang dihantar (200 = gambar, 204 = belum ada)
 *  /disarm batal
 * Gambar hanya dihantar jika /arm dipanggil dahulu dan pengguna menekan SNAP, dan hanya sekali.
 */
class LocalApi {
    companion object { const val PORT = 8765 }

    @Volatile private var server: ServerSocket? = null

    fun start() {
        if (server != null) return
        Thread {
            try {
                val s = ServerSocket(PORT, 8, InetAddress.getByName("127.0.0.1"))
                server = s
                while (!s.isClosed) {
                    val c = try { s.accept() } catch (e: Exception) { break }
                    Thread { handle(c) }.start()
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    fun stop() {
        try { server?.close() } catch (_: Exception) {}
        server = null
    }

    private fun send(sock: Socket, code: Int, status: String, ctype: String, body: ByteArray, extra: String = "") {
        val head = "HTTP/1.1 $code $status\r\n" +
            "Access-Control-Allow-Origin: *\r\n" +
            "Access-Control-Allow-Private-Network: true\r\n" +
            "Access-Control-Allow-Headers: *\r\n" +
            "Access-Control-Allow-Methods: GET, OPTIONS\r\n" +
            "Access-Control-Expose-Headers: X-Name, X-Armed\r\n" +
            "Cache-Control: no-store\r\n" +
            "Content-Type: $ctype\r\n" +
            "Content-Length: ${body.size}\r\n" +
            extra +
            "Connection: close\r\n\r\n"
        val o = sock.getOutputStream()
        o.write(head.toByteArray(Charsets.ISO_8859_1))
        o.write(body)
        o.flush()
    }

    private fun handle(sock: Socket) {
        try {
            sock.soTimeout = 5000
            val rd = sock.getInputStream().bufferedReader(Charsets.ISO_8859_1)
            val first = rd.readLine() ?: return
            while (true) {
                val l = rd.readLine() ?: break
                if (l.isEmpty()) break
            }
            val parts = first.split(" ")
            val method = parts[0]
            val target = if (parts.size > 1) parts[1] else "/"
            val path = target.substringBefore("?")
            val query = target.substringAfter("?", "")
            if (method == "OPTIONS") { send(sock, 204, "No Content", "text/plain", ByteArray(0)); return }
            when (path) {
                "/ping" -> send(sock, 200, "OK", "application/json", "{\"app\":\"thermal-snap\",\"version\":\"3.3\"}".toByteArray())
                "/arm" -> {
                    val svc = SnapService.instance
                    if (svc == null) {
                        send(sock, 503, "Service Unavailable", "application/json", "{\"ok\":false}".toByteArray())
                    } else {
                        svc.apiArm(query != "open=0")
                        send(sock, 200, "OK", "application/json", "{\"ok\":true}".toByteArray())
                    }
                }
                "/disarm" -> {
                    SnapService.armedUntil = 0L
                    SnapService.pendingPng = null
                    send(sock, 200, "OK", "application/json", "{\"ok\":true}".toByteArray())
                }
                "/take" -> {
                    val b = SnapService.pendingPng
                    if (b != null) {
                        val name = SnapService.pendingName
                        SnapService.pendingPng = null
                        send(sock, 200, "OK", "image/png", b, "X-Name: $name\r\n")
                    } else {
                        val armed = if (System.currentTimeMillis() < SnapService.armedUntil) "1" else "0"
                        send(sock, 204, "No Content", "text/plain", ByteArray(0), "X-Armed: $armed\r\n")
                    }
                }
                else -> send(sock, 404, "Not Found", "text/plain", ByteArray(0))
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }
}
