package echopc

import com.music.innertube.models.SongItem
import com.music.innertube.pages.SearchSummary
import java.text.Normalizer

/**
 * Puts the song the person typed first. YouTube Music's own order often leads with a video upload,
 * a cover, a remix or a "sped up" version of the title; here an exact title match by the original
 * artist wins, and versions the query did not ask for (karaoke, slowed, live, cover...) sink.
 * The sort is stable, so YouTube's order still breaks ties.
 */
object SearchRank {
    /** Words that mark a different version of a song; penalised unless the query contains them too. */
    private val versionWords = setOf(
        "cover", "karaoke", "instrumental", "sped", "slowed", "reverb", "remix", "live", "bass", "boosted", "8d", "nightcore",
        "acoustic", "tribute", "parody", "reaction", "mashup", "lofi", "violin", "piano", "flip", "rework", "edit", "version",
    )

    private fun fold(s: String): String =
        Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")

    private fun tokens(s: String): List<String> =
        fold(s).replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim().split(' ').filter { it.isNotEmpty() }

    /** The title without "(From ...)", "[HD]" and similar notes. */
    private fun core(title: String): List<String> = tokens(title.replace(Regex("\\([^)]*\\)|\\[[^]]*]"), " "))

    fun score(query: String, song: SongItem): Int {
        val q = tokens(query)
        if (q.isEmpty()) return 0
        val qSet = q.toSet()
        val titleCore = core(song.title)
        val allTitle = tokens(song.title).toSet()
        val have = (allTitle + song.artists.flatMap { tokens(it.name) }).toSet()
        var score = 0
        if (titleCore == q) score += 100
        val matched = q.count { it in have }
        score += if (matched == q.size) 60 else 40 * matched / q.size
        if (titleCore.isNotEmpty() && titleCore.all { it in qSet }) score += 30 // the whole title is what was typed
        score -= 25 * allTitle.count { it in versionWords && it !in qSet }
        if (song.isVideoSong && "video" !in qSet) score -= 10 // prefer the album version over a video upload
        return score
    }

    /** [songs] best match first (stable). */
    fun rank(query: String, songs: List<SongItem>): List<SongItem> =
        songs.sortedByDescending { score(query, it) }

    /** Result of [apply]: the page's sections reordered, and every song best-first for the queue. */
    class Ranked(val summaries: List<SearchSummary>, val songs: List<SongItem>)

    fun apply(query: String, summaries: List<SearchSummary>): Ranked {
        val all = rank(query, summaries.flatMap { it.items }.filterIsInstance<SongItem>().distinctBy { it.id })
        val fixed = summaries.map { section ->
            val isTop = section.title.equals("Top result", ignoreCase = true)
            when {
                isTop && section.items.firstOrNull() is SongItem -> {
                    // YouTube thinks a song is the top result: show the best-matching version of it.
                    val first = section.items.first() as SongItem
                    val best = all.firstOrNull()
                    if (best != null && score(query, best) > score(query, first)) section.copy(items = listOf(best) + section.items.filter { it.id != best.id })
                    else section
                }
                !isTop && section.items.isNotEmpty() && section.items.all { it is SongItem } ->
                    section.copy(items = rank(query, section.items.filterIsInstance<SongItem>()))
                else -> section
            }
        }
        return Ranked(fixed, all)
    }
}
