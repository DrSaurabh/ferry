package app.ferry.core.util

import java.security.SecureRandom

/** Random tokens (cast session paths, design §6). */
object Tokens {
    private val random = SecureRandom()

    /** 128 random bits as 32 lower-case hex characters. */
    fun random128(): String = ByteArray(16).also { synchronized(random) { random.nextBytes(it) } }.let(Hex::encode)
}
