package echopc

import com.music.innertube.YouTube
import com.music.innertube.models.SongItem
import com.music.innertube.models.WatchEndpoint

enum class RepeatMode { OFF, ALL, ONE }

/** Per playlist/page, like Spotify: off -> shuffle -> Smart Shuffle (shuffle plus recommendations). */
enum class ShuffleMode { OFF, ON, SMART }

/** Immutable queue snapshot so Compose can observe it. */
data class QueueState(
    val items: List<SongItem> = emptyList(),
    val index: Int = -1,
    val repeat: RepeatMode = RepeatMode.OFF,
    /** Ids of Smart Shuffle recommendations mixed into [items]. */
    val recommended: Set<String> = emptySet(),
) {
    val current: SongItem? get() = items.getOrNull(index)

    fun isRecommended(position: Int): Boolean = items.getOrNull(position)?.id in recommended

    /** What will play after [current] (used for prefetching). */
    val upNext: SongItem?
        get() = when {
            current == null -> null
            repeat == RepeatMode.ONE -> current
            index + 1 < items.size -> items[index + 1]
            repeat == RepeatMode.ALL && items.isNotEmpty() -> items.first()
            else -> null
        }

    fun withItems(newItems: List<SongItem>, startIndex: Int) = copy(items = newItems, index = startIndex)

    /** Appends [song] unless it's already queued. */
    fun add(song: SongItem): QueueState = if (items.any { it.id == song.id }) this else copy(items = items + song)

    /** Inserts [song] right after the current track ("play next"). */
    fun playNext(song: SongItem): QueueState {
        val without = items.filterIndexed { i, s -> s.id != song.id || i == index }
        val cur = without.indexOfFirst { it.id == current?.id }.takeIf { it >= 0 } ?: -1
        return copy(items = without.toMutableList().apply { add(cur + 1, song) }, index = cur)
    }

    fun remove(position: Int): QueueState {
        if (position !in items.indices) return this
        val newItems = items.toMutableList().apply { removeAt(position) }
        val newIndex = when {
            position < index -> index - 1
            else -> index
        }
        return copy(items = newItems, index = newIndex.coerceAtMost(newItems.lastIndex))
    }

    fun jump(position: Int): QueueState = if (position in items.indices) copy(index = position) else this

    /** Index after finishing/skipping forward, or null when the queue is exhausted. */
    fun nextIndex(auto: Boolean): Int? = when {
        items.isEmpty() -> null
        auto && repeat == RepeatMode.ONE -> index
        index + 1 < items.size -> index + 1
        repeat == RepeatMode.ALL -> 0
        else -> null
    }

    fun previousIndex(): Int? = when {
        items.isEmpty() -> null
        index > 0 -> index - 1
        repeat == RepeatMode.ALL -> items.lastIndex
        else -> 0
    }

    /** Shuffles everything after the current track. */
    fun shuffledUpcoming(random: kotlin.random.Random = kotlin.random.Random.Default): QueueState {
        if (index < 0) return copy(items = items.shuffled(random))
        val head = items.take(index + 1)
        return copy(items = head + items.drop(index + 1).shuffled(random))
    }

    /**
     * Smart Shuffle: shuffles what's next and puts one recommendation after every [every] songs.
     * Earlier recommendations are replaced; the current song stays where it is.
     */
    fun withSmartShuffle(recs: List<SongItem>, every: Int = 3, random: kotlin.random.Random = kotlin.random.Random.Default): QueueState {
        val base = withoutRecommendations()
        val head = base.items.take(base.index + 1)
        val upcoming = base.items.drop(base.index + 1).shuffled(random)
        val fresh = recs.filter { r -> base.items.none { it.id == r.id } }.distinctBy { it.id }
        val merged = ArrayList<SongItem>()
        var r = 0
        // A short list still gets a recommendation: never wait for more songs than there are.
        val step = every.coerceIn(1, maxOf(1, upcoming.size))
        upcoming.forEachIndexed { i, song ->
            merged += song
            if ((i + 1) % step == 0 && r < fresh.size) merged += fresh[r++]
        }
        if (upcoming.isEmpty() && r < fresh.size) merged += fresh[r++] // a one-song list still gets something
        val used = fresh.take(r).map { it.id }.toSet()
        return base.copy(items = head + merged, recommended = used)
    }

    /** Drops Smart Shuffle recommendations (except the one playing right now). */
    fun withoutRecommendations(): QueueState {
        if (recommended.isEmpty()) return this
        val cur = current
        val kept = items.filterIndexed { i, s -> s.id !in recommended || i == index }
        // Everything removed before the current song shifts it left by one.
        val newIndex = if (index < 0) index else items.take(index).count { it.id !in recommended }
        return copy(items = kept, index = newIndex, recommended = if (cur != null && cur.id in recommended) setOf(cur.id) else emptySet())
    }

    /** Back to the list's own order after the current song ([original] = the list as it was started). */
    fun linear(original: List<SongItem>): QueueState {
        val base = withoutRecommendations()
        val cur = base.current ?: return base.copy(items = original, index = if (original.isEmpty()) -1 else 0)
        val head = base.items.take(base.index + 1)
        val pos = original.indexOfFirst { it.id == cur.id }
        val rest = if (pos >= 0) original.drop(pos + 1) else original
        val extras = base.items.drop(base.index + 1).filter { s -> original.none { it.id == s.id } } // queued by hand
        val upcoming = (rest + extras).filter { s -> head.none { it.id == s.id } }
        return base.copy(items = head + upcoming)
    }

    /** Removes upcoming songs matching [drop] (the current song is left alone). */
    fun withoutUpcoming(drop: (SongItem) -> Boolean): QueueState =
        copy(items = items.filterIndexed { i, s -> i <= index || !drop(s) })
}

/** YouTube Music "radio" for [song]: related songs to keep playing after the queue ends. */
suspend fun fetchRadio(song: SongItem): List<SongItem> =
    YouTube.next(WatchEndpoint(videoId = song.id, playlistId = "RDAMVM${song.id}"))
        .getOrNull()
        ?.items
        .orEmpty()
        .filter { it.id != song.id }
