package com.cellnet.collector

object Rules {
    fun rateRsrp(v: Int?): Int = when {
        v == null -> -1
        v >= -85 -> 0
        v >= -100 -> 1
        else -> 2
    }

    fun rateRsrq(v: Int?): Int = when {
        v == null -> -1
        v >= -10 -> 0
        v >= -15 -> 1
        else -> 2
    }

    fun rateSinr(v: Double?): Int = when {
        v == null -> -1
        v >= 13.0 -> 0
        v >= 0.0 -> 1
        else -> 2
    }

    // 0 = GOOD, 1 = MODERATE, 2 = POOR, -1 = no values. Worse of coverage and quality.
    fun overall(rsrp: Int?, rsrq: Int?, sinr: Double?): Int {
        val cov = rateRsrp(rsrp)
        val qual = if (sinr != null) rateSinr(sinr) else rateRsrq(rsrq)
        return maxOf(cov, qual)
    }

    fun label(rank: Int): String = when (rank) {
        0 -> "GOOD"
        1 -> "MODERATE"
        2 -> "POOR"
        else -> "UNAVAILABLE"
    }
}
