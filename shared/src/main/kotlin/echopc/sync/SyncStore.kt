package echopc.sync

import com.music.innertube.models.Album
import com.music.innertube.models.Artist
import com.music.innertube.models.SongItem
import echo.music.iad1tya.db.entities.Event
import echo.music.iad1tya.db.entities.PlaylistEntity
import echopc.db.Feedback
import echopc.db.LibraryRepository
import echopc.db.ListenSignal
import echopc.db.SyncKv
import echopc.db.SyncOp
import echopc.db.TastePick
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneOffset
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/** What can be synced; each has its own switch. */
object SyncCategory {
    /** Liked songs and your playlists. */
    const val LIBRARY = "library"
    /** Listening history, plays and skips, feedback ("not interested", hidden artists) and picked artists. */
    const val TASTE = "taste"
    val ALL = listOf(LIBRARY, TASTE)
}

/** Change types. The key says what a change is about; the newest change of a key wins. */
internal object OpType {
    const val LIKE = "like" // key like:<songId>          {song, liked}
    const val PLAYLIST = "playlist" // key pl:<playlistId>   {id, name, deleted}
    const val PLAYLIST_SONG = "plsong" // key pls:<pid>:<song> {pid, pname, song, present}
    const val FEEDBACK = "feedback" // key fb:<target>|<kind>|<ctx> {target, kind, context, present}
    const val PICKS = "picks" // key picks                {picks:[{key,name,thumb,at}]}
    const val SIGNAL = "signal" // id = key sig:<songId>:<ms> (one play for taste)
    const val PLAY = "play" // id = key play:<songId>:<ms> (one entry of Recently played)

    fun category(type: String) = when (type) {
        LIKE, PLAYLIST, PLAYLIST_SONG -> SyncCategory.LIBRARY
        else -> SyncCategory.TASTE
    }
}

internal val syncJson = Json { ignoreUnknownKeys = true }

/** Same clock as the database's dates (Converters stores local time as if it were UTC). */
internal fun LocalDateTime.millis(): Long = atZone(ZoneOffset.UTC).toInstant().toEpochMilli()
internal fun Long.toLocal(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneOffset.UTC)

internal fun JsonElement?.s(key: String): String? = (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.contentOrNull }
internal fun JsonElement?.l(key: String): Long? = (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.longOrNull }
internal fun JsonElement?.b(key: String): Boolean? = (this as? JsonObject)?.get(key)?.let { (it as? JsonPrimitive)?.booleanOrNull }

internal fun SongItem.toJson(): JsonObject = buildJsonObject {
    put("id", id); put("title", title); put("thumb", thumbnail)
    duration?.let { put("dur", it) }
    if (explicit) put("explicit", true)
    musicVideoType?.let { put("mvt", it) }
    album?.let { put("album", buildJsonObject { put("name", it.name); put("id", it.id) }) }
    put("artists", buildJsonArray { artists.forEach { a -> add(buildJsonObject { put("name", a.name); a.id?.let { put("id", it) } }) } })
}

internal fun JsonElement.toSong(): SongItem? {
    val o = this as? JsonObject ?: return null
    val id = o.s("id") ?: return null
    return SongItem(
        id = id, title = o.s("title").orEmpty(), thumbnail = o.s("thumb").orEmpty(),
        duration = (o["dur"] as? JsonPrimitive)?.intOrNull, explicit = o.b("explicit") == true, musicVideoType = o.s("mvt"),
        album = (o["album"] as? JsonObject)?.let { a -> a.s("id")?.let { Album(a.s("name").orEmpty(), it) } },
        artists = (o["artists"] as? JsonArray).orEmpty().mapNotNull { a -> a.s("name")?.let { Artist(it, a.s("id")) } },
    )
}

/**
 * The sync log of this device. Every change to likes, playlists, plays, feedback and picks becomes a [SyncOp]:
 * [reconcile] compares the library with the log and writes what changed (so changes made anywhere, imports
 * included, are caught), and [apply] puts another device's changes into the library.
 */
class SyncStore(private val library: LibraryRepository) {
    private val dao = library.syncDao
    private val lock = Mutex()
    private val random = SecureRandom()

    suspend fun kv(k: String): String? = dao.syncKv(k)
    suspend fun putKv(k: String, v: String) = dao.putSyncKv(SyncKv(k, v))

    /** This device's random id (made once). */
    suspend fun deviceId(): String = kv("deviceId") ?: ByteArray(8).also { random.nextBytes(it) }.joinToString("") { "%02x".format(it) }.also { putKv("deviceId", it) }

    suspend fun enabled(category: String): Boolean = kv("switch.$category") != "off"
    suspend fun setEnabled(category: String, on: Boolean) = putKv("switch.$category", if (on) "on" else "off")
    suspend fun enabledCategories(): List<String> = SyncCategory.ALL.filter { enabled(it) }

    /** Highest change number stored per device and category: what to ask the other device for. */
    suspend fun have(): Map<String, Map<String, Long>> =
        dao.syncHave().groupBy { it.origin }.mapValues { (_, rows) -> rows.associate { it.category to it.seq } }

    /** Changes the other device lacks, oldest first, at most [limit]. */
    suspend fun opsFor(theirHave: Map<String, Map<String, Long>>, categories: Collection<String>, limit: Int = 2000): List<SyncOp> {
        val out = ArrayList<SyncOp>()
        val origins = dao.syncHave().map { it.origin }.distinct()
        for (origin in origins) for (cat in categories) {
            if (out.size >= limit) return out
            out += dao.syncOpsAfter(origin, cat, theirHave[origin]?.get(cat) ?: 0L, limit - out.size)
        }
        return out
    }

    // ---------------------------------------------------------------- writing this device's changes

    private suspend fun record(type: String, key: String, at: Long, data: JsonObject, fixedId: String? = null) {
        val me = deviceId()
        val seq = (kv("seq")?.toLongOrNull() ?: 0L) + 1
        putKv("seq", seq.toString())
        dao.insertIgnore(SyncOp(fixedId ?: "$me:$seq", me, seq, OpType.category(type), key, type, at, data.toString()))
    }

    private suspend fun latest(type: String): Map<String, JsonObject> =
        dao.syncLatest(type).associate { it.key to syncJson.parseToJsonElement(it.data).jsonObject }

    /** Writes a change for everything in the library that differs from the log. Safe to run any time. */
    suspend fun reconcile() = lock.withLock {
        val now = LocalDateTime.now().millis()

        // Likes
        val likeLog = latest(OpType.LIKE)
        val liked = dao.likedIdsNow().toSet()
        val songs = library.songsById(liked + likeLog.keys.map { it.removePrefix("like:") })
        for (id in liked) {
            if (likeLog["like:$id"]?.b("liked") == true) continue
            val song = songs[id] ?: continue
            val at = dao.getSong(id)?.likedDate?.millis() ?: now
            record(OpType.LIKE, "like:$id", maxOf(at, (dao.syncLatestFor("like:$id")?.at ?: 0) + 1), buildJsonObject { put("song", song.toJson()); put("liked", true) })
        }
        for ((key, data) in likeLog) {
            val id = key.removePrefix("like:")
            if (data.b("liked") == true && id !in liked) record(OpType.LIKE, key, now, buildJsonObject { put("song", data["song"] ?: JsonObject(emptyMap())); put("liked", false) })
        }

        // Playlists (made on this app; YouTube playlists saved to the library are not synced)
        val plLog = latest(OpType.PLAYLIST)
        val playlists = dao.localPlaylistsNow().associateBy { it.id }
        for (p in playlists.values) {
            val logged = plLog["pl:${p.id}"]
            if (logged != null && logged.b("deleted") != true && logged.s("name") == p.name) continue
            record(OpType.PLAYLIST, "pl:${p.id}", if (logged == null) (p.createdAt?.millis() ?: now) else now, buildJsonObject { put("id", p.id); put("name", p.name); put("deleted", false) })
        }
        for ((key, data) in plLog) {
            val id = key.removePrefix("pl:")
            if (data.b("deleted") != true && id !in playlists) record(OpType.PLAYLIST, key, now, buildJsonObject { put("id", id); put("name", data.s("name").orEmpty()); put("deleted", true) })
        }

        // Songs in those playlists
        val plsLog = latest(OpType.PLAYLIST_SONG)
        val maps = dao.localPlaylistSongMaps()
        val present = maps.map { "pls:${it.playlistId}:${it.songId}" }.toSet()
        val mapSongs = library.songsById(maps.map { it.songId })
        for (m in maps) {
            val key = "pls:${m.playlistId}:${m.songId}"
            if (plsLog[key]?.b("present") == true) continue
            val song = mapSongs[m.songId] ?: continue
            record(OpType.PLAYLIST_SONG, key, now, buildJsonObject {
                put("pid", m.playlistId); put("pname", playlists[m.playlistId]?.name.orEmpty()); put("song", song.toJson()); put("present", true)
            })
        }
        for ((key, data) in plsLog) {
            if (data.b("present") == true && key !in present) {
                val pid = data.s("pid").orEmpty()
                // A deleted playlist takes its songs with it; the playlist change says so already.
                if (pid !in playlists) continue
                record(OpType.PLAYLIST_SONG, key, now, buildJsonObject { put("pid", pid); put("pname", data.s("pname").orEmpty()); put("song", data["song"] ?: JsonObject(emptyMap())); put("present", false) })
            }
        }

        // Feedback
        val fbLog = latest(OpType.FEEDBACK)
        val feedback = library.feedbackNow()
        val fbKeys = feedback.map { "fb:${it.targetId}|${it.kind}|${it.context}" }.toSet()
        for (f in feedback) {
            val key = "fb:${f.targetId}|${f.kind}|${f.context}"
            if (fbLog[key]?.b("present") == true) continue
            record(OpType.FEEDBACK, key, f.timestamp.millis(), buildJsonObject { put("target", f.targetId); put("kind", f.kind); put("context", f.context); put("present", true) })
        }
        for ((key, data) in fbLog) {
            if (data.b("present") == true && key !in fbKeys) record(OpType.FEEDBACK, key, now, buildJsonObject {
                put("target", data.s("target").orEmpty()); put("kind", data.s("kind").orEmpty()); put("context", data.s("context").orEmpty()); put("present", false)
            })
        }

        // Picked artists (one value: the whole list)
        val picks = library.tastePicksNow()
        val pickKeys = picks.map { it.artistKey }.sorted()
        val loggedPicks = latest(OpType.PICKS)["picks"]
        val loggedKeys = (loggedPicks?.get("picks") as? JsonArray).orEmpty().mapNotNull { it.s("key") }.sorted()
        if (pickKeys != loggedKeys && (loggedPicks != null || picks.isNotEmpty())) record(OpType.PICKS, "picks", now, buildJsonObject {
            put("picks", buildJsonArray { picks.forEach { p -> add(buildJsonObject { put("key", p.artistKey); put("name", p.name); put("thumb", p.thumbnail); put("at", p.pickedAt.millis()) }) } })
        })

        // Plays: append only, so just the rows added since last time. Ids are made from the play itself,
        // so a play that came from the other device is not sent back as new.
        catchUpSignals()
        catchUpEvents()
    }

    private suspend fun catchUpSignals() {
        var mark = kv("mark.signal")?.toLongOrNull() ?: 0L
        while (true) {
            val rows = dao.signalsAfterId(mark, 1000)
            if (rows.isEmpty()) break
            val songs = library.songsById(rows.map { it.songId })
            for (r in rows) {
                val song = songs[r.songId] ?: continue
                val id = "sig:${r.songId}:${r.timestamp.millis()}"
                record(OpType.SIGNAL, id, r.timestamp.millis(), buildJsonObject {
                    put("song", song.toJson()); put("artistKey", r.artistKey); put("artistName", r.artistName)
                    put("playedMs", r.playedMs); put("durationMs", r.durationMs); put("start", r.startReason); put("end", r.endReason); put("context", r.context)
                }, fixedId = id)
            }
            mark = rows.last().id
            putKv("mark.signal", mark.toString())
        }
    }

    private suspend fun catchUpEvents() {
        var mark = kv("mark.event")?.toLongOrNull() ?: 0L
        while (true) {
            val rows = dao.eventsAfterId(mark, 1000)
            if (rows.isEmpty()) break
            val songs = library.songsById(rows.map { it.songId })
            for (r in rows) {
                val song = songs[r.songId] ?: continue
                val id = "play:${r.songId}:${r.timestamp.millis()}"
                record(OpType.PLAY, id, r.timestamp.millis(), buildJsonObject { put("song", song.toJson()); put("playTime", r.playTime) }, fixedId = id)
            }
            mark = rows.last().id
            putKv("mark.event", mark.toString())
        }
    }

    // ---------------------------------------------------------------- applying the other device's changes

    /** Stores and applies changes from another device. Returns the categories that changed something. */
    suspend fun apply(ops: List<SyncOp>): Set<String> = lock.withLock {
        val changed = HashSet<String>()
        for (op in ops.sortedWith(compareBy({ it.origin }, { it.seq }))) {
            if (dao.insertIgnore(op) == -1L) continue // already had it
            val winner = dao.syncLatestFor(op.key)
            if (winner != null && winner.id != op.id) continue // a newer change of the same thing is already here
            val data = runCatching { syncJson.parseToJsonElement(op.data).jsonObject }.getOrNull() ?: continue
            if (runCatching { applyOne(op, data) }.isSuccess) changed += op.category
        }
        changed
    }

    private suspend fun applyOne(op: SyncOp, d: JsonObject) {
        when (op.type) {
            OpType.LIKE -> {
                val song = d["song"]?.toSong() ?: return
                val entity = library.save(song)
                val liked = d.b("liked") == true
                if (entity.liked != liked) {
                    val at = op.at.toLocal()
                    dao.update(entity.copy(liked = liked, likedDate = if (liked) at else null, inLibrary = if (liked) entity.inLibrary ?: at else entity.inLibrary))
                }
            }
            OpType.PLAYLIST -> {
                val id = d.s("id") ?: return
                val existing = dao.playlistNow(id)
                when {
                    d.b("deleted") == true -> if (existing != null) dao.deletePlaylist(id)
                    existing == null -> dao.insert(newPlaylist(id, d.s("name").orEmpty(), op.at))
                    existing.name != d.s("name") -> dao.renamePlaylist(id, d.s("name").orEmpty(), op.at.toLocal())
                }
            }
            OpType.PLAYLIST_SONG -> {
                val pid = d.s("pid") ?: return
                val song = d["song"]?.toSong() ?: return
                if (d.b("present") == true) {
                    if (dao.playlistNow(pid) == null) {
                        // Its playlist change hasn't arrived (or was deleted here): only make it if nothing says it's gone.
                        if (dao.syncLatestFor("pl:$pid")?.let { syncJson.parseToJsonElement(it.data).b("deleted") } == true) return
                        dao.insert(newPlaylist(pid, d.s("pname").orEmpty().ifEmpty { "Playlist" }, op.at))
                    }
                    library.addToPlaylist(pid, song)
                } else {
                    dao.removeSongFromPlaylist(pid, song.id)
                }
            }
            OpType.FEEDBACK -> {
                val target = d.s("target") ?: return
                val kind = d.s("kind") ?: return
                val context = d.s("context").orEmpty()
                if (d.b("present") == true) dao.insert(Feedback(target, kind, context, op.at.toLocal())) else dao.deleteFeedback(target, kind, context)
            }
            OpType.PICKS -> {
                val picks = (d["picks"] as? JsonArray).orEmpty().mapNotNull { p ->
                    TastePick(p.s("key") ?: return@mapNotNull null, p.s("name").orEmpty(), p.s("thumb").orEmpty(), (p.l("at") ?: op.at).toLocal())
                }
                dao.replaceTastePicks(picks)
            }
            OpType.SIGNAL -> {
                val song = d["song"]?.toSong() ?: return
                val at = op.at.toLocal()
                if (dao.countSignal(song.id, at) > 0) return
                library.save(song)
                dao.insert(ListenSignal(
                    songId = song.id, artistKey = d.s("artistKey").orEmpty(), artistName = d.s("artistName").orEmpty(), timestamp = at,
                    playedMs = d.l("playedMs") ?: 0, durationMs = d.l("durationMs") ?: 0, startReason = d.s("start").orEmpty(),
                    endReason = d.s("end").orEmpty(), context = d.s("context").orEmpty(),
                ))
            }
            OpType.PLAY -> {
                val song = d["song"]?.toSong() ?: return
                val at = op.at.toLocal()
                if (dao.countEvent(song.id, at) > 0) return
                library.save(song)
                val ms = d.l("playTime") ?: 0
                dao.insert(Event(songId = song.id, timestamp = at, playTime = ms))
                dao.addPlayTime(song.id, ms)
            }
        }
    }

    private fun newPlaylist(id: String, name: String, at: Long): PlaylistEntity {
        val time = at.toLocal()
        return PlaylistEntity(id = id, name = name.ifBlank { "Playlist" }, createdAt = time, lastUpdateTime = time, bookmarkedAt = time, isLocal = true)
    }
}
