# Calmaria — sleep sounds for Android

A Sleepa-style sleep sound mixer. Pick sounds from five categories, mix up to 10 at once with
individual volume sliders, set a sleep timer, save favorites, play evolving "journeys" and wake up
to a gentle nature alarm. UI in Portuguese (pt) and English, light and dark themes.

**It keeps playing while other apps play audio.** Spotify, YouTube, podcasts or games can run on
top of it and Calmaria will not pause, duck or fade out.

## Install

1. Download `app-release.apk` (from this repo's **Actions** tab → latest *Calmaria Android* run →
   *calmaria-apk* artifact, or the file shared with you).
2. Open it on the phone and allow "Install unknown apps" for your browser/files app when asked.
3. Samsung/Xiaomi phones: open Calmaria → **Ajustes → Otimização de bateria → Permitir**, or set
   the app's battery usage to *Unrestricted*. Otherwise the OS may kill background audio overnight.

## Why it can play alongside Spotify

Android pauses or fades an app when another app takes **audio focus**, but only if the first app
requested focus itself. Calmaria never requests audio focus (unless you switch off *Tocar junto com
outros apps* in settings), so the system has nothing to take away from it. It also:

- runs playback in a `mediaPlayback` foreground service with a partial wake lock, started from a
  user action, so audio survives the screen turning off and meets Android 14–17's background audio
  rules;
- has **no MediaSession**, so headset buttons and the lock-screen media controls stay with your
  music app instead of being hijacked;
- pauses only for things you would want: phone/VoIP calls (resumes after), headphones unplugged,
  and the sleep timer. Each can be switched off in settings.

The wake-up alarm is the one exception: it uses the alarm stream and briefly takes focus so your
music pauses while you wake up.

## Features

| Tab | What it does |
| --- | --- |
| **Sons** | 60 sounds in 5 swipeable categories (rain & water, nature, city & home, noise, meditation & music). Tap to add/remove; the mixer panel has play/pause, per-sound sliders, timer and ♥. |
| **Viagens** | 10 journeys: curated mixes that slowly crossfade between stages over the night (e.g. fireplace → storm → light rain). |
| **Favoritos** | Saved mixes; play, rename, delete. |
| **Alarme** | Daily/one-off alarm with repeat days, wake scene or any favorite, gradual 1-minute fade-in, snooze, full-screen over the lock screen. |
| **Ajustes** | Mix-with-other-apps, pause on calls, pause on headphone unplug, timer fade-out, battery optimization shortcut. |

Sleep timer: 5 min to 8 h, fades out over the last minute. Notification shows a live countdown and
play/pause/close.

## The sounds

Every sound is **synthesized live on the phone** (`app/src/main/java/.../audio`): filtered noise
beds, thousands of tiny resonant "drops", bubble models, chirp synthesis for birds and insects,
additive synthesis for bowls/chimes/piano, and a Freeverb reverb. That means no audio files, no
downloads, a ~1.6 MB APK, and no audible loop points, but they are synthesized approximations rather
than field recordings. Levels are calibrated so every sound is similarly loud at the same slider
position (`SoundLevelsTest` prints the table), and a limiter keeps any mix clean.

## Build

Requires JDK 17+ and the Android SDK (platform 36).

```sh
./gradlew :app:assembleRelease       # app/build/outputs/apk/release/app-release.apk
./gradlew :app:testDebugUnitTest      # DSP levels/CPU, mixer, alarm scheduling tests
./gradlew :app:testDebugUnitTest -Pscreenshots   # renders UI screenshots to app/build/screenshots
```

Both build types are signed with `app/debug.keystore`, an intentionally public development key
(password `android`) so APKs from CI and local builds install over each other. Use your own key
before distributing it anywhere.

## Layout

```
app/src/main/java/com/redelar/calmaria/
  audio/dsp/      noise, filters, drift modulators, voice pools, reverb
  audio/sounds/   the generators (water, nature, home, noise, music)
  audio/          SoundCatalog, Mixer (fades + limiter), AudioEngine (AudioTrack thread)
  playback/       Player (mix, timer, journeys, alarm, focus/calls/headphones), service, notifications
  alarm/          scheduling, alarm and boot receivers
  data/           settings/favorites store, journeys and wake scenes
  ui/             Compose UI
```

The Lato font is by Łukasz Dziedzic, licensed under the SIL Open Font License 1.1.
Calmaria is an independent project and is not affiliated with Sleepa.
