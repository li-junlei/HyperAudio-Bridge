package io.hyperaudio.bridge

object SessionRecovery {
    fun delayMillis(attempt: Int): Long? = if (attempt in 0..4) (attempt + 1) * 1500L else null
    fun canRecover(id: String?, expires: Long, now: Long) = !id.isNullOrBlank() && expires >= now && expires - now <= 90000
}
