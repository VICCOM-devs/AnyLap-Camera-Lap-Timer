package app.anylap.util

import kotlin.math.abs
import kotlin.math.round

/** Fixed-decimal formatting that works the same on every platform (no String.format in common code). */
fun Double.fixed(decimals: Int): String {
    if (!isFinite()) return "—"
    var m = 1L
    repeat(decimals) { m *= 10 }
    val scaled = round(abs(this) * m).toLong()
    val ip = scaled / m
    val fp = scaled % m
    val s = if (decimals == 0) "$ip" else "$ip." + fp.toString().padStart(decimals, '0')
    return if (this < 0 && scaled != 0L) "-$s" else s
}

fun Float.fixed(decimals: Int): String = toDouble().fixed(decimals)

fun Long.pad(width: Int): String = toString().padStart(width, '0')
fun Int.pad(width: Int): String = toString().padStart(width, '0')
