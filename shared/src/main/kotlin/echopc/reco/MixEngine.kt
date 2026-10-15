package echopc.reco

import com.music.innertube.models.SongItem
import echopc.db.artistKey

/** A generated mix before it is stored. */
data class MixDraft(val id: String, val kind: String, val title: String, val subtitle: String, val songs: List<SongItem>)

object MixKind {
    const val DAILY = "daily"
    const val DISCOVER = "discover"
    const val RADAR = "radar"
    const val ON_REPEAT = "on_repeat"
    const val REWIND = "rewind"
}

/**
 * Builds the personal mixes from a [TasteProfile]:
 *
 * - **Daily Mix 1–6**: the user's artists clustered by how often they turn up in each other's
 *   radios; each mix blends known favourites from one cluster with new songs from it.
 * - **Discover Weekly**: 30 songs the user has never played, ranked by how many of their recent
 *   favourites "point to" them (co-occurrence across seeds), with one or two per artist.
 * - **Release Radar**: newest releases from the artists the user listens to most.
 * - **On Repeat / Repeat Rewind**: most played lately / heavy rotation that went quiet.
 *
 * [songMeta] gives metadata for songs the user knows (from the library database).
 */
class MixEngine(private val source: CandidateSource, private val songMeta: Map<String, SongItem>) {

    // ---------------- Daily Mixes ----------------

    suspend fun dailyMixes(profile: TasteProfile, maxMixes: Int = 6, size: Int = 50): List<MixDraft> {
        val artists = profile.topArtists(24).filter { a -> profile.tracksOf(a).any { it.songId in songMeta } }
        if (artists.isEmpty()) return emptyList()
        val artistSet = artists.toSet()

        // Seeds: each artist's two best known songs. Their radios are the candidate pool.
        val seedsByArtist = artists.associateWith { a -> profile.tracksOf(a).mapNotNull { songMeta[it.songId] }.take(2) }
        val related = HashMap<String, List<SongItem>>()
        for (seed in seedsByArtist.values.flatten().take(36)) related[seed.id] = source.related(seed)

        // Artist graph: an edge when one artist's radio contains another of the user's artists.
        val edges = HashMap<String, HashMap<String, Double>>()
        for ((artist, seeds) in seedsByArtist) for (seed in seeds) for (cand in related[seed.id].orEmpty()) {
            val other = cand.artistKey()
            if (other != artist && other in artistSet) {
                edges.getOrPut(artist) { HashMap() }.merge(other, 1.0, Double::plus)
                edges.getOrPut(other) { HashMap() }.merge(artist, 1.0, Double::plus)
            }
        }
        // A small library (a few likes, little listening) is too thin to split into taste groups:
        // blend it into one mix instead, and accept a smaller familiar core.
        val knownCount = artists.sumOf { a -> profile.tracksOf(a).count { it.songId in songMeta } }
        val small = knownCount < SMALL_LIBRARY
        val clusters = if (small) listOf(artists) else clusterArtists(artists, edges, profile)
        val minFamiliar = if (small) MIN_FAMILIAR_SMALL_LIBRARY else MIN_FAMILIAR_PER_MIX

        val used = HashSet<String>()
        return clusters.mapNotNull { cluster ->
            val members = cluster.toSet()
            val familiar = cluster.flatMap { a -> profile.tracksOf(a).mapNotNull { songMeta[it.songId] } }
                .sortedByDescending { profile.tracks[it.id]?.longScore ?: 0.0 }
                .filter { it.id !in used }
            // A mix needs a real taste behind it, not an artist heard once in passing.
            if (familiar.size < minFamiliar) return@mapNotNull null
            // New songs: from the cluster's radios, by artists in (or next to) the cluster, never played.
            val counts = HashMap<String, Int>()
            val byId = HashMap<String, SongItem>()
            for (a in cluster) for (seed in seedsByArtist[a].orEmpty()) for (cand in related[seed.id].orEmpty()) {
                if (profile.known(cand.id) || profile.blocked(cand.id, cand.artistKey()) || cand.id in used) continue
                val inCluster = cand.artistKey() in members
                counts.merge(cand.id, if (inCluster) 2 else 1, Int::plus)
                byId[cand.id] = cand
            }
            val fresh = counts.entries.sortedByDescending { it.value }.mapNotNull { byId[it.key] }
            val blended = interleave(familiar.take(size / 2 + 5), fresh.take(size / 2 + 5), familiarEvery = 2)
            val songs = diversify(blended, size)
            if (songs.size < 10) return@mapNotNull null
            used += songs.map { it.id }
            songs
        }.take(maxMixes).mapIndexed { i, songs ->
            val names = songs.map { it.artistKey() }.distinct().take(3).mapNotNull { profile.artistNames[it] ?: songs.firstOrNull { s -> s.artistKey() == it }?.artists?.firstOrNull()?.name }
            MixDraft("daily-${i + 1}", MixKind.DAILY, "Daily Mix ${i + 1}", artistLine(names), songs)
        }
    }

    /**
     * Label propagation over the artist graph: every artist starts in its own group and repeatedly
     * joins the group its neighbours weigh most toward (weighted by their affinity). Groups are then
     * ordered by total affinity.
     */
    internal fun clusterArtists(artists: List<String>, edges: Map<String, Map<String, Double>>, profile: TasteProfile): List<List<String>> {
        val label = artists.associateWith { it }.toMutableMap()
        repeat(10) {
            var changed = false
            for (a in artists) {
                val votes = HashMap<String, Double>()
                votes[label.getValue(a)] = 0.5 * profile.artistAffinity(a) // a little inertia
                edges[a]?.forEach { (b, w) -> label[b]?.let { lb -> votes.merge(lb, w * (0.5 + profile.artistAffinity(b)), Double::plus) } }
                val best = votes.entries.maxWithOrNull(compareBy<Map.Entry<String, Double>> { it.value }.thenBy { -artists.indexOf(it.key) })!!.key
                if (best != label[a]) { label[a] = best; changed = true }
            }
            if (!changed) return@repeat
        }
        return artists.groupBy { label.getValue(it) }.values
            .sortedByDescending { group -> group.sumOf { profile.artistAffinity(it) } }
    }

    // ---------------- Discover Weekly ----------------

    suspend fun discoverWeekly(profile: TasteProfile, recentSeeds: List<SongItem>, size: Int = 30): MixDraft? {
        if (recentSeeds.isEmpty()) return null
        val score = HashMap<String, Double>()
        val byId = HashMap<String, SongItem>()
        recentSeeds.forEachIndexed { i, seed ->
            val seedWeight = 1.0 - i * 0.03 // the user's very top seeds count a bit more
            val list = source.related(seed)
            list.forEachIndexed { rank, cand ->
                val key = cand.artistKey()
                if (cand.id == seed.id || profile.known(cand.id) || profile.blocked(cand.id, key)) return@forEachIndexed
                score.merge(cand.id, seedWeight * (1.0 - rank.toDouble() / list.size), Double::plus)
                byId[cand.id] = cand
            }
        }
        val ranked = score.entries.map { (id, s) ->
            val cand = byId.getValue(id)
            val aff = profile.artistAffinity(cand.artistKey())
            // Prefer songs several favourites agree on; a small bonus for new artists keeps it a discovery list.
            id to s + 0.3 * aff.coerceAtLeast(0.0) + if (aff == 0.0) 0.2 else 0.0
        }.sortedByDescending { it.second }.map { byId.getValue(it.first) }
        val songs = capPerArtist(ranked, 2).take(size)
        if (songs.size < 10) return null
        return MixDraft("discover-weekly", MixKind.DISCOVER, "Discover Weekly", "New-to-you songs picked from your taste. Updated every Monday.", songs)
    }

    // ---------------- Release Radar ----------------

    suspend fun releaseRadar(profile: TasteProfile, artistIds: List<String>, size: Int = 30): MixDraft? {
        val perArtist = artistIds.map { id ->
            source.recentReleases(id).filter { !profile.known(it.id) && !profile.blocked(it.id, it.artistKey()) }
        }.filter { it.isNotEmpty() }
        // Round robin so one prolific artist doesn't fill the list.
        val limit = size
        val songs = ArrayList<SongItem>()
        var i = 0
        while (songs.size < limit && perArtist.any { i < it.size }) {
            perArtist.forEach { l -> l.getOrNull(i)?.let { s -> if (songs.none { it.id == s.id }) songs += s } }
            i++
        }
        if (songs.size > limit) songs.subList(limit, songs.size).clear()
        if (songs.size < 5) return null
        return MixDraft("release-radar", MixKind.RADAR, "Release Radar", "New releases from artists you like. Updated every Friday.", songs)
    }

    // ---------------- On Repeat / Repeat Rewind ----------------

    fun onRepeat(plays: List<Play>, profile: TasteProfile, now: Long, size: Int = 30): MixDraft? {
        val since = now - 30L * 86_400_000
        val counts = plays.filter { it.atMillis >= since && it.playedMs >= Weights.STREAM_MS && it.songId !in profile.notInterested }
            .groupingBy { it.songId }.eachCount().filter { it.value >= 2 }
        val songs = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenByDescending { profile.tracks[it.key]?.lastPlayed ?: 0 })
            .mapNotNull { songMeta[it.key] }.take(size)
        if (songs.size < 5) return null
        return MixDraft("on-repeat", MixKind.ON_REPEAT, "On Repeat", "The songs you can't stop playing right now.", songs)
    }

    fun repeatRewind(profile: TasteProfile, now: Long, size: Int = 30): MixDraft? {
        val quietSince = now - 60L * 86_400_000
        val songs = profile.tracks.values.filter { it.streams >= 3 && it.played && it.lastPlayed < quietSince && it.songId !in profile.notInterested }
            .sortedByDescending { it.streams }.mapNotNull { songMeta[it.songId] }.take(size)
        if (songs.size < 5) return null
        return MixDraft("repeat-rewind", MixKind.REWIND, "Repeat Rewind", "Songs you had on repeat a while back.", songs)
    }

    companion object {
        /** Known songs a taste cluster needs before it becomes a Daily Mix. */
        const val MIN_FAMILIAR_PER_MIX = 3

        /** Below this many known songs the whole library is treated as one taste (a single Daily Mix). */
        const val SMALL_LIBRARY = 6
        const val MIN_FAMILIAR_SMALL_LIBRARY = 2

        /** Familiar and new songs mixed: [familiarEvery] familiar ones, then one new one, and so on. */
        fun interleave(familiar: List<SongItem>, fresh: List<SongItem>, familiarEvery: Int): List<SongItem> = buildList {
            var f = 0; var n = 0
            while (f < familiar.size || n < fresh.size) {
                repeat(familiarEvery) { if (f < familiar.size) add(familiar[f++]) }
                if (n < fresh.size) add(fresh[n++])
            }
        }.distinctBy { it.id }

        /**
         * Keeps at most [maxPerWindow] songs by the same artist in any [window] consecutive songs,
         * taking the best-ranked song that fits each slot (relaxing only when nothing fits).
         */
        fun diversify(ranked: List<SongItem>, limit: Int, maxPerWindow: Int = 2, window: Int = 10): List<SongItem> {
            val pending = ranked.distinctBy { it.id }.toMutableList()
            val out = ArrayList<SongItem>()
            while (out.size < limit && pending.isNotEmpty()) {
                val recent = out.takeLast(window - 1).groupingBy { it.artistKey() }.eachCount()
                val i = pending.indexOfFirst { (recent[it.artistKey()] ?: 0) < maxPerWindow }
                out += pending.removeAt(if (i >= 0) i else 0)
            }
            return out
        }

        /** At most [max] songs per artist over the whole list. */
        fun capPerArtist(ranked: List<SongItem>, max: Int): List<SongItem> {
            val seen = HashMap<String, Int>()
            return ranked.filter { s -> (seen.merge(s.artistKey(), 1, Int::plus) ?: 1) <= max }
        }

        fun artistLine(names: List<String>): String = when (names.size) {
            0 -> "Songs you love and new ones like them"
            1 -> "${names[0]} and more"
            else -> names.dropLast(1).joinToString(", ") + " and ${names.last()}"
        }
    }
}
