# my-song

An Android music player for audio files on your phone and live internet radio. The app itself is ad-free.

**[Download my-song.apk](https://github.com/vaibhavsant18/my-song/releases/latest/download/my-song.apk)** · [Latest release](https://github.com/vaibhavsant18/my-song/releases/latest)

## Install on Android

1. On a phone running Android 8.0 or newer, tap **Download my-song.apk**.
2. Open the downloaded APK from your notifications or **Downloads** and follow the Android install prompt. If prompted, allow installation from the app you used to open the file.
3. In my-song, tap **Add music** and choose **Add files** or **Add folder**.

## Use my-song

- **Library and playlists.** Search by song, artist, or album. Use a song’s menu to add it to a playlist. Folder imports scan subfolders and skip duplicates.
- **Offline playback.** Imported copies are stored inside the app and play even if the original file moves. Removing a track from my-song removes its imported copy.
- **Synced lyrics.** Choose **Add synced lyrics** in a song’s menu and pick a local `.lrc` file. Tap **Lyrics** in the player to follow or seek to a line.
- **Now Playing.** Tap the track name in the mini-player to open the full player. Touch or drag the orbital progress ring or use the slider to seek. Open **Queue** to jump to a track or clear upcoming songs. The player also supports background playback, Android media controls, shuffle, and repeat.
- **Live radio.** Open **Live → Add station**, give it a name, and paste a direct HTTPS MP3 or AAC stream URL, or an HLS `.m3u8` link. Tap the saved station to listen and use **Stop radio** to disconnect. Live radio uses an internet connection. Your imported music stays playable offline.

Dot & Line uses [Bitcount Prop Single](https://fonts.google.com/specimen/Bitcount+Prop+Single) for headings, distributed under the [SIL Open Font License](app/src/main/assets/licenses/bitcount-ofl.txt).

## Build

Use JDK 17 or newer and Android SDK Platform 37.0. Run `./gradlew :app:assembleRelease` on macOS or Linux, or `gradlew.bat :app:assembleRelease` on Windows. The unsigned release APK is written to `app/build/outputs/apk/release/`. Sign it with your own key using Android SDK Build Tools. Keep the same key for future updates to an installed copy.

The app uses [AndroidX Media3](https://developer.android.com/media/media3) for playback and media controls. The download link above uses the `my-song.apk` asset in the latest GitHub release.
