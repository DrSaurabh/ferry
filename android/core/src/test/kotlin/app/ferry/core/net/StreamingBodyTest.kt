package app.ferry.core.net

import app.ferry.core.Opened
import okio.Buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import kotlin.random.Random

class StreamingBodyTest {
    private val chunk = StreamingBody.CHUNK

    private fun data(n: Int) = Random(n).nextBytes(n)

    /** A stream that hands out at most 1000 bytes per read (like a slow pipe). */
    private class Trickle(private val inner: InputStream) : InputStream() {
        override fun read(): Int = inner.read()
        override fun read(b: ByteArray, off: Int, len: Int): Int = inner.read(b, off, minOf(len, 1000))
    }

    @Test
    fun sendsAllBytesIn256KbChunksWithMonotonicProgress() {
        for (size in listOf(0, 1, chunk, 3 * chunk + 1)) {
            val bytes = data(size)
            val progress = mutableListOf<Long>()
            val fullyAtProgress = mutableListOf<Boolean>()
            lateinit var body: StreamingBody
            body = StreamingBody(Opened(Trickle(ByteArrayInputStream(bytes)), size.toLong()), size.toLong()) {
                progress += it
                fullyAtProgress += body.bodyFullySent
            }
            assertTrue(body.isOneShot())
            assertEquals(size.toLong(), body.contentLength())
            assertFalse(body.bodyFullySent)
            val sink = Buffer()
            body.writeTo(sink)
            assertArrayEquals(bytes, sink.readByteArray())
            assertTrue("size $size: bodyFullySent", body.bodyFullySent)
            assertEquals(size.toLong(), body.bytesSent)
            assertEquals(size.toLong(), progress.last())
            for (i in 1 until progress.size) assertTrue(progress[i] >= progress[i - 1])
            if (size > 0) {
                // Every step is one full 256 KB chunk except possibly the last.
                val steps = progress.mapIndexed { i, p -> p - (if (i == 0) 0 else progress[i - 1]) }
                for (s in steps.dropLast(1)) assertEquals(chunk.toLong(), s)
                assertTrue(steps.last() in 1..chunk.toLong())
            }
            // bodyFullySent only becomes true after the last byte [B18].
            assertTrue(fullyAtProgress.none { it })
        }
    }

    @Test
    fun shortStreamIsSourceChanged() {
        for (size in listOf(1, chunk, 3 * chunk + 1)) {
            val bytes = data(size - 1)
            val body = StreamingBody(Opened(ByteArrayInputStream(bytes), size.toLong()), size.toLong()) {}
            val sink = Buffer()
            try {
                body.writeTo(sink)
                fail("size $size: expected SourceChanged")
            } catch (e: FerryException.SourceChanged) {
                // expected
            }
            assertFalse(body.bodyFullySent)
            assertTrue(sink.size < size)
        }
    }

    /** [PB1]: a growing file must be detected BEFORE the last byte of `size` reaches the sink. */
    @Test
    fun extraBytesAreSourceChangedBeforeTheLastByteIsSent() {
        for (size in listOf(0, 1, chunk, 3 * chunk + 1)) {
            val bytes = data(size + 1)
            val body = StreamingBody(Opened(ByteArrayInputStream(bytes), size.toLong()), size.toLong()) {}
            val sink = Buffer()
            try {
                body.writeTo(sink)
                fail("size $size: expected SourceChanged")
            } catch (e: FerryException.SourceChanged) {
                // expected
            }
            assertFalse(body.bodyFullySent)
            assertTrue("size $size: sink got ${sink.size}", sink.size < size || size == 0)
            assertEquals(0L, if (size == 0) sink.size else 0L)
        }
    }

    @Test
    fun oneShot() {
        val body = StreamingBody(Opened(ByteArrayInputStream(ByteArray(3)), 3), 3) {}
        body.writeTo(Buffer())
        try {
            body.writeTo(Buffer())
            fail("second writeTo must fail")
        } catch (e: IllegalStateException) {
            // expected
        }
    }
}
