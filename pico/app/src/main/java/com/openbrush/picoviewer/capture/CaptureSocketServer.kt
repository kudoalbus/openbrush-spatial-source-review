package com.openbrush.picoviewer.capture

import org.java_websocket.WebSocket
import org.java_websocket.drafts.Draft_6455
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress
import java.nio.ByteBuffer

/** Explicitly enabled LAN service. No external relay and no remote editing commands. */
class CaptureSocketServer(port: Int, private val events: Events) : WebSocketServer(
    InetSocketAddress(port), 1, listOf(Draft_6455(emptyList(), 128 * 1024))
) {
    interface Events {
        fun opened(server: CaptureSocketServer, socket: WebSocket)
        fun message(server: CaptureSocketServer, socket: WebSocket, text: String)
        fun closed(server: CaptureSocketServer, socket: WebSocket)
        fun ready(server: CaptureSocketServer)
        fun failed(server: CaptureSocketServer, socket: WebSocket?, error: Exception)
    }
    init { setReuseAddr(true); setConnectionLostTimeout(15) }
    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        if(handshake.resourceDescriptor != "/capture") { conn.close(1008, "Unknown endpoint"); return }
        events.opened(this, conn)
    }
    override fun onMessage(conn: WebSocket, message: String) {
        if(message.length > 64 * 1024) { conn.close(1009, "Message too large"); return }
        events.message(this, conn, message)
    }
    override fun onMessage(conn: WebSocket, message: ByteBuffer) { conn.close(1003, "Control messages must be JSON") }
    override fun onClose(conn: WebSocket, code: Int, reason: String, remote: Boolean) { events.closed(this, conn) }
    override fun onError(conn: WebSocket?, ex: Exception) { events.failed(this, conn, ex) }
    override fun onStart() { events.ready(this) }
}
