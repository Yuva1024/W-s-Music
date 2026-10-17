package echopc.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

// Tables for syncing the library between the user's own devices over Wi-Fi (schema v4).

/**
 * One change, made on device [origin] as its [seq]-th change. Devices swap the changes the other hasn't
 * seen; the newest change ([at], then [id]) for a [key] wins. [category] is "library" (likes, playlists)
 * or "taste" (plays, feedback, picked artists), so each can be switched on or off.
 */
@Entity(tableName = "sync_op", indices = [Index(value = ["origin", "category", "seq"]), Index(value = ["key"])])
data class SyncOp(
    @PrimaryKey val id: String,
    val origin: String,
    val seq: Long,
    val category: String,
    val key: String,
    val type: String,
    /** When the change happened (epoch ms of local time, like the other tables' dates). */
    val at: Long,
    /** The change as JSON. */
    val data: String,
)

/** Small sync settings: this device's id, paired devices, switches. */
@Entity(tableName = "sync_kv")
data class SyncKv(@PrimaryKey val k: String, val v: String)

/** Highest change number stored from [origin] in [category]. */
data class SyncHave(val origin: String, val category: String, val seq: Long)
