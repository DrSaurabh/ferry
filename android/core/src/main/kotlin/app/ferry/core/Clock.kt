package app.ferry.core

fun interface Clock {
    fun nowMs(): Long
}

object SystemClock : Clock {
    override fun nowMs(): Long = System.currentTimeMillis()
}
