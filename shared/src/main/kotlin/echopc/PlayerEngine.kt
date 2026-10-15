package echopc

data class PlayerState(
    val loading: Boolean = false,
    val playing: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val error: String? = null,
)

/** What the UI needs from a player; lets screenshot/UI tests run without audio. */
interface PlayerEngine {
    fun play(videoId: String, nextVideoId: String?)
    fun prefetch(videoId: String)
    fun togglePause()
    fun seek(ms: Long)
    fun setVolume(v: Double)
    fun stop()
}
