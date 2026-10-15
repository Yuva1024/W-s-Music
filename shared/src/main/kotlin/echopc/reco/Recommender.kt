package echopc.reco

import com.music.innertube.models.SongItem
import echopc.db.Feedback
import echopc.db.GeneratedMix
import echopc.db.LibraryRepository
import echopc.db.TrackCodec
import echopc.db.artistKey
import echopc.db.toSongItem
import java.time.DayOfWeek
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.temporal.TemporalAdjusters
import kotlin.random.Random
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Stored timestamps are local wall-clock values saved as UTC epoch millis (see Converters). */
internal fun LocalDateTime.millis(): Long = atZone(ZoneOffset.UTC).toInstant().toEpochMilli()

/**
 * Keeps the generated mixes fresh, on Spotify's schedule: Daily Mixes, On Repeat and Repeat Rewind
 * every day (or sooner after 20 new plays), Discover Weekly on Mondays, Release Radar on Fridays.
 */
class Recommender(
    private val library: LibraryRepository,
    private val source: CandidateSource,
    private val clock: () -> LocalDateTime = LocalDateTime::now,
) {
    private val mutex = Mutex()
    /** A build that produced nothing (too little history yet), and what the library looked like then. */
    private class Attempt(val at: LocalDateTime, val likedCount: Int)

    /** Kinds that came up empty: retried when listening or likes change, else tomorrow. */
    private val emptyAttempts = HashMap<String, Attempt>()

    /** Forget empty results (after an import or other big change), so everything is tried again. */
    fun invalidateEmpty() = emptyAttempts.clear()

    companion object {
        /** New plays since an empty build before it is tried again the same day. */
        const val RETRY_AFTER_SIGNALS = 5
    }

    val kinds = listOf(MixKind.DISCOVER, MixKind.RADAR, MixKind.DAILY, MixKind.ON_REPEAT, MixKind.REWIND)

    /** Rebuilds stale mixes (all of them with [force]). Returns the kinds that were rebuilt. */
    suspend fun refresh(force: Boolean = false): List<String> = mutex.withLock {
        val now = clock()
        val existing = library.mixesNow().groupBy { it.kind }
        val stale = kinds.filter { force || isStale(it, existing[it].orEmpty(), now) }
        if (stale.isEmpty()) return emptyList()

        val inputs = loadInputs(now)
        val engine = MixEngine(source, inputs.meta)
        val profile = inputs.profile
        for (kind in stale) {
            val drafts = when (kind) {
                MixKind.DAILY -> engine.dailyMixes(profile)
                MixKind.DISCOVER -> listOfNotNull(engine.discoverWeekly(profile, inputs.recentSeeds))
                MixKind.RADAR -> listOfNotNull(engine.releaseRadar(profile, profile.topArtists(30).filter { it.startsWith("UC") }.take(12)))
                MixKind.ON_REPEAT -> listOfNotNull(engine.onRepeat(inputs.plays, profile, now.millis()))
                MixKind.REWIND -> listOfNotNull(engine.repeatRewind(profile, now.millis()))
                else -> emptyList()
            }
            if (drafts.isEmpty()) emptyAttempts[kind] = Attempt(now, inputs.likedCount) else emptyAttempts.remove(kind)
            library.replaceMixes(kind, drafts.mapIndexed { i, d -> d.toEntity(positionOf(kind, i), now) })
        }
        library.pruneRelated(olderThanDays = 30)
        stale
    }

    internal suspend fun isStale(kind: String, mixes: List<GeneratedMix>, now: LocalDateTime): Boolean {
        if (mixes.isEmpty()) {
            val attempt = emptyAttempts[kind] ?: return true
            if (attempt.at.toLocalDate() != now.toLocalDate()) return true
            // Same day: try again as soon as there is new listening or a like to learn from.
            return library.countSignalsAfter(attempt.at) >= RETRY_AFTER_SIGNALS || library.likedSongs.first().size != attempt.likedCount
        }
        val at = mixes.minOf { it.generatedAt }
        return when (kind) {
            MixKind.DISCOVER -> at < now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).atStartOfDay()
            MixKind.RADAR -> at < now.toLocalDate().with(TemporalAdjusters.previousOrSame(DayOfWeek.FRIDAY)).atStartOfDay()
            MixKind.REWIND -> at.toLocalDate() != now.toLocalDate()
            else -> at.toLocalDate() != now.toLocalDate() || library.countSignalsAfter(at) >= 20
        }
    }

    /** Home order: Discover Weekly, Release Radar, Daily Mix 1–6, On Repeat, Repeat Rewind. */
    private fun positionOf(kind: String, i: Int) = when (kind) {
        MixKind.DISCOVER -> 0
        MixKind.RADAR -> 1
        MixKind.DAILY -> 2 + i
        MixKind.ON_REPEAT -> 20
        else -> 21
    }

    private fun MixDraft.toEntity(position: Int, now: LocalDateTime) =
        GeneratedMix(id, kind, title, subtitle, TrackCodec.encode(songs), position, now)

    // ---------------- Smart Shuffle ----------------

    /**
     * Recommendations to mix into a playlist/page for Smart Shuffle: songs that the page's own songs
     * point to (co-occurrence across a sample of them), not already on the page, nothing the user
     * blocked or removed here. One set per page per day, like Spotify ("refreshed daily").
     */
    suspend fun smartShuffle(context: String, songs: List<SongItem>, count: Int): List<SongItem> {
        val now = clock()
        val feedback = library.feedbackNow()
        val notInterested = feedback.filter { it.kind == Feedback.Kind.NOT_INTERESTED }.map { it.targetId }.toSet()
        val banned = feedback.filter { it.kind == Feedback.Kind.BAN_ARTIST }.map { it.targetId }.toSet()
        val hiddenHere = feedback.filter { it.kind == Feedback.Kind.HIDE_IN_CONTEXT && it.context == context }.map { it.targetId }.toSet()
        val inList = songs.map { it.id }.toSet()
        fun allowed(s: SongItem) = s.id !in inList && s.id !in notInterested && s.id !in hiddenHere && s.artistKey() !in banned

        val key = "smart:$context:${now.toLocalDate()}"
        library.cachedRelated(key, maxAgeDays = 1)?.let { cached -> return cached.filter(::allowed).take(count) }

        val seeds = songs.shuffled(Random(now.toLocalDate().toEpochDay() * 31 + context.hashCode())).take(8)
        val score = HashMap<String, Double>()
        val byId = HashMap<String, SongItem>()
        for (seed in seeds) {
            val related = source.related(seed)
            related.forEachIndexed { rank, cand ->
                if (!allowed(cand)) return@forEachIndexed
                score.merge(cand.id, 1.0 - rank.toDouble() / related.size, Double::plus)
                byId[cand.id] = cand
            }
        }
        val ranked = MixEngine.capPerArtist(score.entries.sortedByDescending { it.value }.map { byId.getValue(it.key) }, 2).take(maxOf(count, 30))
        if (ranked.isNotEmpty()) library.cacheRelated(key, ranked) // a failed fetch must not stick for the day
        return ranked.take(count)
    }

    // ---------------- inputs ----------------

    class Inputs(val plays: List<Play>, val profile: TasteProfile, val meta: Map<String, SongItem>, val recentSeeds: List<SongItem>, val likedCount: Int)

    /** Songs standing in for the artists the user picked: up to three top songs each (or just the artist if none load). */
    private suspend fun pickSeeds(now: LocalDateTime): Pair<List<Saved>, Map<String, SongItem>> {
        val saved = ArrayList<Saved>(); val meta = HashMap<String, SongItem>()
        for (pick in library.tastePicksNow()) {
            val songs = runCatching { source.topSongs(pick.artistKey) }.getOrDefault(emptyList()).take(3)
            val at = pick.pickedAt.millis()
            if (songs.isEmpty()) {
                // Offline or nothing listed: the artist alone still steers Release Radar and discovery.
                saved += Saved("pick:${pick.artistKey}", pick.artistKey, pick.name, at)
            } else songs.forEach { s ->
                saved += Saved(s.id, pick.artistKey, pick.name, at) // credited to the picked artist, not a featured one
                meta[s.id] = s
            }
        }
        return saved to meta
    }

    suspend fun loadInputs(now: LocalDateTime): Inputs {
        // Plays from before the signal log (history imported from the Android app, or older app versions) count too.
        library.backfillSignalsFromHistory()
        val signals = library.signalsSince(now.minusDays(365))
        val plays = signals.map { Play(it.songId, it.artistKey, it.artistName, it.timestamp.millis(), it.playedMs, it.durationMs, it.endReason) }
        val likedRows = library.likedSongs.first()
        val liked = likedRows.map { s ->
            val item = s.toSongItem()
            Saved(item.id, item.artistKey(), item.artists.firstOrNull()?.name.orEmpty(), s.song.likedDate?.millis())
        }
        val playlistIds = library.songIdsInPlaylists() - liked.map { it.songId }.toSet()
        val meta = library.songsById(plays.map { it.songId } + liked.map { it.songId } + playlistIds) + likedRows.associate { it.song.id to it.toSongItem() }
        val inPlaylists = playlistIds.mapNotNull { id -> meta[id]?.let { Saved(id, it.artistKey(), it.artists.firstOrNull()?.name.orEmpty(), null) } }
        val (pickSaved, pickMeta) = pickSeeds(now)
        val profile = TasteProfile.build(plays, liked, inPlaylists, library.feedbackNow(), now.millis(), pickSongs = pickSaved)

        // Discover Weekly seeds: what the user has loved over the last four weeks, else their likes.
        val since = now.minusDays(28).millis()
        val recent = profile.tracks.values.filter { it.lastPlayed >= since && it.shortScore > 0 && it.songId !in profile.excluded }
            .sortedByDescending { it.shortScore }.mapNotNull { meta[it.songId] }
        val seeds = (recent + liked.mapNotNull { meta[it.songId] } + pickMeta.values).distinctBy { it.id }.take(15)
        return Inputs(plays, profile, meta + pickMeta, seeds, liked.size)
    }
}
