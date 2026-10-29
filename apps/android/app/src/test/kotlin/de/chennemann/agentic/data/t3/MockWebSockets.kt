package de.chennemann.agentic.data.t3

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okhttp3.mockwebserver.MockResponse
import okio.ByteString

/** Own upgraded server sockets so test teardown does not depend on asynchronous client shutdown. */
internal class MockWebSockets : AutoCloseable {
    private val sockets = ConcurrentHashMap<WebSocket, CountDownLatch>()

    fun upgrade(listener: WebSocketListener): MockResponse =
        MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                sockets[webSocket] = CountDownLatch(1)
                listener.onOpen(webSocket, response)
            }

            override fun onMessage(webSocket: WebSocket, text: String) = listener.onMessage(webSocket, text)

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = listener.onMessage(webSocket, bytes)

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(code, reason)
                listener.onClosing(webSocket, code, reason)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                sockets.remove(webSocket)?.countDown()
                listener.onClosed(webSocket, code, reason)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                sockets.remove(webSocket)?.countDown()
                listener.onFailure(webSocket, t, response)
            }
        })

    override fun close() {
        val closing = sockets.entries.map { it.key to it.value }
        closing.forEach { (socket, _) -> socket.close(1000, "Test complete") }
        closing.forEach { (_, closed) ->
            check(closed.await(10, TimeUnit.SECONDS)) { "Test WebSocket did not finish closing" }
        }
    }
}
