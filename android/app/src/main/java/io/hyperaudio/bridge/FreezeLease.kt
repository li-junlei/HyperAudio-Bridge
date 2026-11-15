package io.hyperaudio.bridge

/** A crashed or stopped sender cannot leave a permanent system exemption. */
class FreezeLease {
    @Volatile private var uid = -1
    @Volatile private var until = 0L
    @Synchronized fun update(owner: Int, enabled: Boolean, now: Long) {
        uid = owner
        until = if (enabled) now + 45000 else 0
    }
    fun blocksBinder(target: Int, freezing: Boolean, now: Long) = freezing && protects(target, now)
    fun protects(target: Int, now: Long) = target >= 10000 && target == uid && now < until
}
