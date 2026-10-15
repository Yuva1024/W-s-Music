package echopc

import echopc.lyrics.LyricsEntry

sealed interface LyricsUiState {
    data object Idle : LyricsUiState
    data object Loading : LyricsUiState
    data object NotFound : LyricsUiState
    data class Loaded(
        val provider: String,
        val lines: List<LyricsEntry>,
        /** True when lines carry timestamps; false for plain text lyrics. */
        val synced: Boolean,
    ) : LyricsUiState
}
