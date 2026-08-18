package com.lukecao.suggest.rank

import java.util.Calendar
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/** Minutes since local midnight. The [cal] is reused to keep this allocation-free
 *  in the hot loop — callers must not share one across threads. */
fun todMinutes(ts: Long, cal: Calendar): Int {
    cal.timeInMillis = ts
    return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
}

fun isWeekend(ts: Long, cal: Calendar): Boolean {
    cal.timeInMillis = ts
    val d = cal.get(Calendar.DAY_OF_WEEK)
    return d == Calendar.SATURDAY || d == Calendar.SUNDAY
}

/** [Calendar.DAY_OF_WEEK], only ever compared for equality. */
fun dayOfWeek(ts: Long, cal: Calendar): Int {
    cal.timeInMillis = ts
    return cal.get(Calendar.DAY_OF_WEEK)
}

/** Shortest gap in minutes between two times of day, wrapping at midnight, so
 *  23:50 and 00:10 are 20 minutes apart rather than 1420. */
fun circularDelta(a: Int, b: Int): Int {
    val d = abs(a - b)
    return min(d, 1440 - d)
}

/** Unit-height gaussian: 1.0 at [d] == 0, falling to ~0.61 at one sigma. */
fun gaussian(d: Double, sigma: Double): Double = exp(-(d * d) / (2.0 * sigma * sigma))

/** Exponential decay expressed as a half-life. */
fun halfLifeDecay(ageMs: Double, halfLifeMs: Double): Double = 2.0.pow(-ageMs / halfLifeMs)

/** Great-circle distance in metres. */
fun distanceMeters(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val r = 6_371_000.0
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = p2 - p1
    val dl = Math.toRadians(lon2 - lon1)
    val a = sin(dp / 2).pow(2) + cos(p1) * cos(p2) * sin(dl / 2).pow(2)
    return 2 * r * asin(min(1.0, sqrt(a)))
}
