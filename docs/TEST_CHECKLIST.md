# W's MUSIC test checklist

Run this before publishing a release. An update must never break something that worked before.

- **[auto]** means an automated test covers it (`gradlew :app:test`). Still glance at it by hand when that area changed.
- Every other item is checked by hand, on the **PC** and the **phone** as marked.
- Test the **update itself** with the previous version installed, not only a fresh install.

## 0. Before building

- [ ] All automated tests pass: `gradlew :app:test` (99 tests as of 1.2.1).
- [ ] Version raised in both places: `appVersion` in `app/build.gradle.kts`, and `versionName` + `versionCode` in `android/build.gradle.kts`. The version code is major×10000 + minor×100 + patch.
- [ ] Release files are freshly built (`--rerun-tasks`, so the .msi isn't left over from an older build): `WsMusic-x.y.z.msi`, `WsMusic-Setup.exe`, `WsMusic-portable.zip`, `WsMusic-x.y.z.apk`.
- [ ] The APK is signed with the permanent key (`android-signing\wsmusic-release.jks`). An APK signed with another key can't update the app that's already installed.

## 1. Updating (the most important part)

**PC**
- [ ] On the previous version: the update dialog appears (or **Check for updates**), shows notes without `**` marks, then **Update now**.
- [ ] It downloads with a progress bar, says "Installing…", closes, and reopens by itself with "Updated to x.y.z". No installer window, no question about where to install.
- [ ] After the update: Liked Songs, playlists, history, downloads, settings (volume, Discord mode) and pairing are all still there.
- [ ] **[auto]** The update download picks the .msi, verifies the checksum, and continues after a dropped connection.
- [ ] **By hand:** the update downloads on your home network. That's how the 1.2.0 "HTTP connect timed out" bug showed up: one GitHub address can't be reached there.

**Phone**
- [ ] On the previous version: **Library › Check for updates** finds it, downloads it, and Android asks to update.
- [ ] After the update, the app reopens (Android 9 and older) and says "Updated to x.y.z".
- [ ] **Songs play on the phone right after updating:** tap a song in Search, Home and Liked Songs. (In 1.2.0 they went to the PC instead.)
- [ ] Library, likes, playlists, downloads and pairing are kept.

## 2. Playing music (both, every release)

- [ ] Search a song by name: the song you meant is first, and tapping it plays **that** song, not the next one.
- [ ] Play / pause, next, previous (restarts if more than 3 s in), seek, volume.
- [ ] A song that only exists as a video still plays.
- [ ] When the queue ends, similar songs keep playing (autoplay on); with autoplay off it stops.
- [ ] Shuffle (off, on, Smart) and repeat (off, all, one).
- [ ] **Phone:** notification and lock-screen controls work, music keeps playing with the screen off, and unplugging headphones pauses it.
- [ ] **PC:** keyboard shortcuts (Space, arrows, Ctrl+L…), and mouse buttons 4/5 for back and forward.
- [ ] A song that fails shows an error in words and the next one plays.

## 3. Library and pages

- [ ] Like and unlike from the heart and from the song menu; Liked Songs updates.
- [ ] Create a playlist, add a song, remove it, rename the playlist, delete it.
- [ ] Recently played lists plays of 10 s or more; Clear history works.
- [ ] Home loads: shelves, Made for you mixes, Recently played, mood chips.
- [ ] Artist, album and online playlist pages open and play.
- [ ] Lyrics show and follow the song (synced lyrics highlight the current line).

## 4. Queue

- [ ] **[auto]** Moving a song keeps the playing song playing.
- [ ] **PC:** drag a song in the queue panel by its grip (⋮⋮) and the other songs make room. Remove from queue, shuffle what's next, and clear the queue also work.
- [ ] **Phone:** in Now playing › Up next, drag a song by its handle (☰). Swipe left removes it.
- [ ] **Phone:** on any song list, swipe right adds the song to the queue, swipe left plays it next, and the phone ticks at the line. A plain tap still plays the song.

## 5. Downloads and local files

- [ ] Download a single song (song menu) and a whole playlist (download button): progress shows, and they appear in **Downloads**.
- [ ] **[auto]** A downloaded song plays from its file without streaming.
- [ ] **By hand:** with internet off (airplane mode / Wi-Fi off), downloaded songs still play.
- [ ] Remove a download, and cancel downloads in progress.
- [ ] **PC › Local files:** the Music folder is listed. **Add folder** and **Rescan** work, MP3/M4A/WAV files play, and titles and covers come from the files.
- [ ] **Phone › On this phone:** Android asks for permission once, then the songs list and play.

## 6. Spotify import

- [ ] A playlist link (Share › Copy link) creates a playlist (up to 100 songs).
- [ ] Copied song links (Spotify desktop: Ctrl+A, Ctrl+C) go into **Liked Songs**, or into a **new playlist** with the name you type.
- [ ] A Spotify data .zip: history-only shows the "Account data" note; Account data creates the playlists and likes.
- [ ] **[auto]** Reading links, files and the matching on YouTube Music.

## 7. Sync, remote control and Devices (PC and phone, same Wi-Fi)

- [ ] **[auto]** Pairing with codes, sync both ways, removals, the switches, nothing duplicated, upgrade of an old library.
- [ ] Pair by hand: the PC's Sync button › Pair a phone, then Phone › Sync with computer. Both screens show the same code.
- [ ] Like a song on the phone and it appears on the PC within a few minutes (or after **Sync now**), and the other way round.
- [ ] Turn off **Sync music taste** on one device: likes still sync, plays don't.
- [ ] **[auto]** Remote control, handoff, and "a paused PC doesn't take the phone's taps".
- [ ] While the PC plays: the phone shows "Playing on <PC>", and its buttons and song taps control the PC. A tapped song shows a toast with **Play here**.
- [ ] With the PC paused and the phone idle, a tapped song plays **on the phone**.
- [ ] Devices button: move playback to the other device and back. The song and position carry over, and the other device pauses.

## 8. Discord mode (PC)

- [ ] **[auto]** Lower and pause react to voice, come back after a quiet moment, and respect a pause made by hand.
- [ ] **By hand, in a Discord call:** someone talks and the music drops (Lower) or pauses (Pause); they stop and it comes back. Your own voice doesn't trigger it. Off does nothing.

## 9. Start-up and odd cases

- [ ] First start on a new PC or phone: the "pick artists you like" prompt, then Home works.
- [ ] Without internet: the app opens, says it can't load Home, and Downloads and local files still work.
- [ ] Low-end PC: the app starts (it switches to CPU rendering after a failed start), and the portable zip runs without installing.
- [ ] Closing the PC app while music plays saves the play to history.

## Bugs this list was made from

| Version | What broke | Now covered by |
| --- | --- | --- |
| 1.2.0 | PC update failed with "HTTP connect timed out" (one GitHub address unreachable on the user's network) | §1 (by hand) |
| 1.2.0 | Phone songs didn't play after updating: taps went to the paired PC because it had played recently | §1, §7, remote control test |
| 1.0.7 | PC update "download is incomplete" | §1, updater resume test |
| 1.0.3 | Phone app showed no update popup | §1 |
| 1.0.x | Tapping a search result played the next song | §2 |
