package echopc.reco

import kotlin.math.ln
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Orders Home shelves like a contextual bandit (Spotify's "BaRT"): each shelf's click-through rate
 * is uncertain, so every load draws a plausible rate from its Beta posterior (Thompson sampling) and
 * shelves are ranked by the draw. Shelves that earn clicks rise; new or rarely seen shelves still get
 * shown now and then (exploration). The feed's own order acts as a gentle prior.
 */
class ShelfBandit(private val random: Random = Random.Default) {

    /** [stats] maps shelf key to (impressions, clicks). Returns [keys] in display order. */
    fun order(keys: List<String>, stats: Map<String, Pair<Long, Long>>): List<String> {
        val n = keys.size.coerceAtLeast(1)
        return keys.mapIndexed { i, key ->
            val (impressions, clicks) = stats[key] ?: (0L to 0L)
            val c = clicks.coerceAtMost(impressions).toDouble()
            // Prior Beta(1, 3): a 25 % click rate before any evidence.
            val draw = sampleBeta(1.0 + c, 3.0 + (impressions - c))
            val positional = 0.25 * (1.0 - i.toDouble() / n)
            key to draw + positional
        }.sortedByDescending { it.second }.map { it.first }
    }

    fun sampleBeta(a: Double, b: Double): Double {
        val x = sampleGamma(a); val y = sampleGamma(b)
        return x / (x + y)
    }

    /** Marsaglia–Tsang gamma sampler (shape >= 1; smaller shapes use the boost trick). */
    private fun sampleGamma(shape: Double): Double {
        if (shape < 1.0) return sampleGamma(shape + 1.0) * random.nextDouble().pow(1.0 / shape)
        val d = shape - 1.0 / 3.0
        val c = 1.0 / sqrt(9.0 * d)
        while (true) {
            var x: Double; var v: Double
            do { x = gaussian(); v = 1.0 + c * x } while (v <= 0.0)
            v = v * v * v
            val u = random.nextDouble()
            if (u < 1.0 - 0.0331 * x * x * x * x) return d * v
            if (ln(u) < 0.5 * x * x + d * (1.0 - v + ln(v))) return d * v
        }
    }

    private fun gaussian(): Double {
        // Box–Muller
        val u1 = random.nextDouble().coerceAtLeast(1e-12); val u2 = random.nextDouble()
        return sqrt(-2.0 * ln(u1)) * kotlin.math.cos(2.0 * Math.PI * u2)
    }
}
