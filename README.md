# my-song

my-song is an Android player for audio files you select. Open **Add music** and choose **Add files** or **Add folder**. Folder import scans subfolders and skips duplicates already in the library. Imported copies are stored inside the app, so playback works offline even if the source file moves. Search by track, artist, or album. Use the menu beside a song to add it to a playlist.

Use **Add synced lyrics** in the song menu to pick a local `.lrc` file. The player’s **Lyrics** button opens the timed lines. Tap a line to seek to that point in the song. **Queue** shows what plays next and lets you jump to a track or clear the upcoming entries.

The player supports background playback, Android media controls, seeking, shuffle, and playlist repeat. It requests no Internet permission. There is no in-app catalogue, sign-in, or ad service. Removing a track from the library removes only my-song’s copy.

## Build

Use JDK 17 or newer and Android SDK Platform 37.0. Run `./gradlew :app:assembleRelease` on macOS or Linux, or `gradlew.bat :app:assembleRelease` on Windows. The unsigned release APK is written to `app/build/outputs/apk/release/`. Sign it with your own key using Android SDK Build Tools. Keep the same key for future updates to an installed copy.

The app uses [AndroidX Media3](https://developer.android.com/media/media3) for playback and media controls.
