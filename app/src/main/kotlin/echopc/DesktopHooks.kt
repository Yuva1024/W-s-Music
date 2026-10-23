package echopc

/** Plugs the desktop's log file, album-art colours, lyrics and installer updates into the shared Controller. */
fun installDesktopHooks() {
    AppHooks.log = Device::log
    AppHooks.backgroundDelayMs = Device.backgroundDelayMs
    AppHooks.dominantColor = { url -> ThumbCache.load(url, 128)?.dominant }
    AppHooks.loadLyrics = { song -> LyricsRepository.load(song) }
    AppHooks.updates = Updater
    AppHooks.deviceName = ::desktopName
    AppHooks.downloadsDir = java.io.File(System.getenv("APPDATA") ?: System.getProperty("user.home"), "WsMusic/downloads")
    AppHooks.localMusic = DesktopLocalMusic
    AppHooks.syncServer = { host -> DesktopSyncServer(host) }
}
