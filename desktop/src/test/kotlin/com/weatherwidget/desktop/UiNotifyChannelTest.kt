package com.weatherwidget.desktop

import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Round-trips the daemon → UI push channel over a real Unix domain socket in a temp dir. Proves the
 * non-lossy notification the popup relies on actually reaches the client: once on connect (so a change
 * that landed while disconnected is picked up) and again on each pushed byte.
 */
class UiNotifyChannelTest {

    @Test
    fun `client is notified on connect and on push`() {
        val dir = Files.createTempDirectory("ui-notify-test")
        val server = UiNotifyServer(dir)
        val client = UiNotifyClient(dir) { }
        try {
            server.start()

            val reasons = CopyOnWriteArrayList<String>()
            val latch = CountDownLatch(2) // "connect" + at least one "push"
            val listening = UiNotifyClient(dir) { reason ->
                reasons.add(reason)
                latch.countDown()
            }
            listening.start()

            // Keep pushing until both the connect and a push notification have arrived, so the test
            // never races the server's accept() (a push before the client is registered is a no-op).
            var attempts = 0
            while (!latch.await(200, TimeUnit.MILLISECONDS) && attempts < 25) {
                server.pushDataUpdated()
                attempts++
            }
            listening.close()

            assertTrue("expected a connect notification, got $reasons", reasons.contains("connect"))
            assertTrue("expected a push notification, got $reasons", reasons.contains("push"))
        } finally {
            client.close()
            server.close()
        }
    }

    @Test
    fun `push with no clients and after client close does not throw`() {
        val dir = Files.createTempDirectory("ui-notify-test")
        val server = UiNotifyServer(dir)
        try {
            server.start()
            // No client connected yet — must be a safe no-op.
            server.pushDataUpdated()

            val latch = CountDownLatch(1)
            val client = UiNotifyClient(dir) { latch.countDown() }
            client.start()
            var attempts = 0
            while (!latch.await(200, TimeUnit.MILLISECONDS) && attempts < 25) {
                server.pushDataUpdated()
                attempts++
            }
            assertTrue("client never connected", latch.count == 0L)

            client.close()
            Thread.sleep(200)
            // Client gone: the dead channel is dropped without surfacing an error.
            server.pushDataUpdated()
        } finally {
            server.close()
        }
    }
}
