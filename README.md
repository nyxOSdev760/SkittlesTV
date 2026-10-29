# Skittles Player

A clean, minimal video player for Amazon Fire TV and Android TV, built on AndroidX Media3 (ExoPlayer).

- **Open with…** from any app (file managers, browsers, Plex, Kodi, …) — plays immediately. Shared links (`ACTION_SEND`) work too.
- **HLS, DASH, SmoothStreaming, RTSP** and all common files (MP4, MKV, WebM, TS, MP3, FLAC, …).
- **Big buffers** to avoid stalls, with three modes: Smooth (default), Balanced, Fast start.
- **Resumes where you stopped.** Only the position per file is remembered — media is never copied.
- **Settings panel** (press ☰ Menu or D-pad Up): speed, audio track, subtitles, screen fit, buffer mode, restart, open file, info.

## Install on Fire TV

1. On the Fire TV: **Settings → My Fire TV → About**, then click the device name 7 times to unlock **Developer Options**.
2. Install **Downloader** (by AFTVnews) from the Amazon Appstore.
3. **Settings → My Fire TV → Developer Options → Install unknown apps → Downloader → On.**
4. Open Downloader and enter:

   ```
   https://github.com/nyxOSdev760/SkittlesTV/releases/latest/download/SkittlesPlayer.apk
   ```

5. Click **Install**. Skittles Player now appears in your apps row.

Every push to this repo builds a new APK and publishes it as the latest release, so repeating step 4 always gets the newest version (it installs as an update; your saved positions and settings are kept).

## Remote

| Button | Action |
| --- | --- |
| OK | Show controls / play-pause (opens the file browser when nothing is loaded) |
| ◀ ▶ | Seek ∓10 s (when controls are hidden) |
| ▲ or ☰ | Settings panel |
| ⏯ ⏪ ⏩ | Play/pause, seek ∓10 s |
| Back | Settings: back one level, then close. Otherwise exits. |

## Build locally

Needs JDK 17+ and the Android SDK (platform 34).

```
./gradlew assembleRelease   # app/build/outputs/apk/release/app-release.apk
./gradlew test              # Robolectric UI tests
```

The release build is signed with `app/debug.keystore` (a throwaway debug key kept in the repo so every build can update the previous one).
