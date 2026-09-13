package com.tymewear.karoo

data class ZoneTimes(
    val z1: Long = 0,
    val z2: Long = 0,
    val z3: Long = 0,
    val z4: Long = 0,
    val z5: Long = 0,
    val total: Long = 0,
) {
    operator fun get(zone: Int): Long = when (zone) {
        1 -> z1
        2 -> z2
        3 -> z3
        4 -> z4
        5 -> z5
        else -> 0
    }

    fun add(zone: Int, seconds: Long): ZoneTimes {
        if (seconds <= 0L) return this
        return when (zone) {
            1 -> copy(z1 = z1 + seconds, total = total + seconds)
            2 -> copy(z2 = z2 + seconds, total = total + seconds)
            3 -> copy(z3 = z3 + seconds, total = total + seconds)
            4 -> copy(z4 = z4 + seconds, total = total + seconds)
            5 -> copy(z5 = z5 + seconds, total = total + seconds)
            else -> this
        }
    }

    val max: Long get() = maxOf(z1, z2, z3, z4, z5)
}
