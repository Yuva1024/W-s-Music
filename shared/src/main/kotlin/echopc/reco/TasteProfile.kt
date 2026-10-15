package echopc.reco

import echopc.db.EndReason
import echopc.db.Feedback
import kotlin.math.max
import kotlin.math.pow

/**
 * How much each kind of evidence moves taste. Modelled on Spotify's published approach: implicit
 * feedback from listening (a stream counts from 30 s, an early forward skip counts against),
 * explicit saves count more, and "not interested" is a strong veto.
 */
object Weights {
    const val FULL_LISTEN = 1.0 // finished, or 80 %+ heard
    const val STREAM = 0.5 // 30 s or more
    const val EARLY_SKIP = -0.7 // skipped forward before 30 s
    const val LIKE = 3.0
    const val PLAYLIST_ADD = 2.0
    /**
     * One song of a picked artist's top songs. A pick brings up to three of them (about 2.1 together, a little
     * above a playlist add and below a like), a guess that real listening quickly outweighs.
     */
    const val PICK_SONG = 0.7
    const val NOT_INTERESTED = -5.0
    /** Listening to a song marked "exclude from taste" counts this much. */
    const val EXCLUDED_FACTOR = 0.1
    const val STREAM_MS = 30_000L
    const val SHORT_HALF_LIFE_DAYS = 7.0
    const val LONG_HALF_LIFE_DAYS = 180.0
    /** Age given to playlist membership (it has no timestamp). */
    const val PLAYLIST_AGE_DAYS = 30.0
}

/** One play, as the engine sees it (decoupled from the database row). */
data class Play(
    val songId: String,
    val artistKey: String,
    val artistName: String,
    val atMillis: Long,
    val playedMs: Long,
    val durationMs: Long,
    val endReason: String,
)

/** A song the user saved (liked or put in a playlist). */
data class Saved(val songId: String, val artistKey: String, val artistName: String, val atMillis: Long?)

/** Per-song listening statistics. */
class TrackStats(val songId: String, val artistKey: String) {
    var streams = 0 // plays of 30 s or more
    var skips = 0 // early forward skips
    var firstPlayed = Long.MAX_VALUE
    var lastPlayed = Long.MIN_VALUE
    var longScore = 0.0
    var shortScore = 0.0
    val played: Boolean get() = lastPlayed != Long.MIN_VALUE
}

/** Weight of one play before time decay. */
fun playWeight(p: Play): Double {
    val ratio = if (p.durationMs > 0) p.playedMs.toDouble() / p.durationMs else 0.0
    return when {
        p.endReason == EndReason.TRACK_DONE || ratio >= 0.8 -> Weights.FULL_LISTEN
        p.playedMs >= Weights.STREAM_MS -> Weights.STREAM
        p.endReason == EndReason.FWD_BUTTON -> Weights.EARLY_SKIP
        else -> 0.0 // left early for some other reason: no evidence either way
    }
}

/** Exponential time decay: weight halves every [halfLifeDays]. */
fun decay(ageMillis: Long, halfLifeDays: Double): Double = 0.5.pow(max(0L, ageMillis) / (halfLifeDays * 86_400_000.0))

/**
 * The user's taste: artist and track affinities over a short (weeks) and long (months) horizon,
 * plus everything the user asked us to avoid.
 */
class TasteProfile(
    val now: Long,
    private val artistShort: Map<String, Double>,
    private val artistLong: Map<String, Double>,
    val artistNames: Map<String, String>,
    val tracks: Map<String, TrackStats>,
    val liked: Set<String>,
    val notInterested: Set<String>,
    val bannedArtists: Set<String>,
    val excluded: Set<String>,
) {
    private val maxShort = artistShort.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0
    private val maxLong = artistLong.values.maxOrNull()?.takeIf { it > 0 } ?: 1.0

    /** 0..1-ish blend of recent and long-term artist taste; negative when the artist keeps being skipped. */
    fun artistAffinity(key: String): Double =
        0.6 * (artistShort[key] ?: 0.0) / maxShort + 0.4 * (artistLong[key] ?: 0.0) / maxLong

    /** Artists with positive affinity, best first. */
    fun topArtists(limit: Int = Int.MAX_VALUE): List<String> =
        (artistShort.keys + artistLong.keys).distinct()
            .map { it to artistAffinity(it) }.filter { it.second > 0 && it.first !in bannedArtists }
            .sortedByDescending { it.second }.take(limit).map { it.first }

    fun known(songId: String): Boolean = songId in liked || tracks[songId]?.played == true

    /** True when a song must never be recommended. */
    fun blocked(songId: String, artistKey: String): Boolean = songId in notInterested || artistKey in bannedArtists

    /** Known tracks of [artistKey], best first (positive score only). */
    fun tracksOf(artistKey: String): List<TrackStats> =
        tracks.values.filter { it.artistKey == artistKey && it.longScore > 0 && it.songId !in notInterested }.sortedByDescending { it.longScore }

    companion object {
        fun build(
            plays: List<Play>, saved: List<Saved>, playlistSongs: List<Saved>, feedback: List<Feedback>, now: Long,
            /** Top songs of the artists the user picked when asked what they like. */
            pickSongs: List<Saved> = emptyList(),
        ): TasteProfile {
            val notInterested = feedback.filter { it.kind == Feedback.Kind.NOT_INTERESTED }.map { it.targetId }.toSet()
            val banned = feedback.filter { it.kind == Feedback.Kind.BAN_ARTIST }.map { it.targetId }.toSet()
            val excluded = feedback.filter { it.kind == Feedback.Kind.EXCLUDE_FROM_TASTE }.map { it.targetId }.toSet()
            val short = HashMap<String, Double>()
            val long = HashMap<String, Double>()
            val names = HashMap<String, String>()
            val tracks = HashMap<String, TrackStats>()

            fun add(songId: String, artistKey: String, name: String, weight: Double, ageMs: Long) {
                if (name.isNotBlank()) names.putIfAbsent(artistKey, name)
                val w = if (songId in excluded) weight * Weights.EXCLUDED_FACTOR else weight
                val s = w * decay(ageMs, Weights.SHORT_HALF_LIFE_DAYS)
                val l = w * decay(ageMs, Weights.LONG_HALF_LIFE_DAYS)
                short.merge(artistKey, s, Double::plus); long.merge(artistKey, l, Double::plus)
                val t = tracks.getOrPut(songId) { TrackStats(songId, artistKey) }
                t.shortScore += s; t.longScore += l
            }

            for (p in plays) {
                val w = playWeight(p)
                add(p.songId, p.artistKey, p.artistName, w, now - p.atMillis)
                val t = tracks.getValue(p.songId)
                if (p.playedMs >= Weights.STREAM_MS || p.endReason == EndReason.TRACK_DONE) t.streams++
                if (w < 0) t.skips++
                if (p.playedMs > 0) { t.firstPlayed = minOf(t.firstPlayed, p.atMillis); t.lastPlayed = maxOf(t.lastPlayed, p.atMillis) }
            }
            for (s in saved) add(s.songId, s.artistKey, s.artistName, Weights.LIKE, s.atMillis?.let { now - it } ?: 0L)
            for (s in playlistSongs) add(s.songId, s.artistKey, s.artistName, Weights.PLAYLIST_ADD, (Weights.PLAYLIST_AGE_DAYS * 86_400_000).toLong())
            for (s in pickSongs) add(s.songId, s.artistKey, s.artistName, Weights.PICK_SONG, s.atMillis?.let { now - it } ?: 0L)
            for (id in notInterested) tracks[id]?.let { t ->
                add(id, t.artistKey, "", Weights.NOT_INTERESTED, 0L)
            }
            return TasteProfile(now, short, long, names, tracks, saved.map { it.songId }.toSet(), notInterested, banned, excluded)
        }
    }
}
