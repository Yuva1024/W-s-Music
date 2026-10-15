package echopc

/**
 * Keyboard shortcuts. One table drives the key handling, the hints in button tooltips and the
 * "Keyboard shortcuts" dialog, so they cannot disagree. [keys] is the display text.
 * [repeatable]: holding the key keeps firing (volume, seeking); everything else fires once per press.
 */
enum class Shortcut(val label: String, val keys: String, val group: String, val repeatable: Boolean = false) {
    PLAY_PAUSE("Play or pause", "Space", "Playback"),
    NEXT("Next song", "Ctrl+→", "Playback"),
    PREVIOUS("Previous song", "Ctrl+←", "Playback"),
    SEEK_FORWARD("Skip forward 5 seconds", "Shift+→", "Playback", repeatable = true),
    SEEK_BACK("Skip back 5 seconds", "Shift+←", "Playback", repeatable = true),
    SHUFFLE("Shuffle, Smart Shuffle, off", "Ctrl+S", "Playback"),
    REPEAT("Repeat: off, all, one", "Ctrl+R", "Playback"),
    LIKE("Like or unlike the current song", "Alt+Shift+B", "Playback"),
    VOLUME_UP("Volume up", "Ctrl+↑", "Sound", repeatable = true),
    VOLUME_DOWN("Volume down", "Ctrl+↓", "Sound", repeatable = true),
    MUTE("Mute or unmute", "M", "Sound"),
    SEARCH("Search", "Ctrl+L", "Navigation"),
    BACK("Go back", "Alt+←", "Navigation"),
    FORWARD("Go forward", "Alt+→", "Navigation"),
    HELP("Keyboard shortcuts", "Ctrl+/", "Navigation"),
}
