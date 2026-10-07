# Android

Native Android implementation using Kotlin, Jetpack Compose and Media3/ExoPlayer.

Silence skipping is Media3's native `ExoPlayer.setSkipSilenceEnabled(true)` path; the video itself is not rewritten.

Build:

```bash
./gradlew :app:assembleDebug
```
