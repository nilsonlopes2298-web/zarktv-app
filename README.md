<div align="center">

<img src="docs/header.png" alt="Channelbox" width="760">

**A free, open-source live TV player for Android TV – built to feel like a cable box.**

Turn on the TV, and you're watching TV. Channel up/down, a real programme guide, recordings.
Bring your own IPTV subscription (M3U, Xtream Codes or a Stalker portal with a MAC address).

[![Build](https://github.com/Axon07/Channelbox/actions/workflows/build.yml/badge.svg)](https://github.com/Axon07/Channelbox/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/Axon07/Channelbox?include_prereleases&label=download)](https://github.com/Axon07/Channelbox/releases)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue.svg)](LICENSE)
![Android 5.0+](https://img.shields.io/badge/Android-5.0%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-Compose%20for%20TV-7F52FF?logo=kotlin&logoColor=white)

**Downloader code: `5502923`** · [Download](#download) · [Features](#features) · [Remote control](#remote-control) · [Cable-box setup](#making-it-act-like-a-cable-box) · [Building](#building)

</div>

> [!NOTE]
> Channelbox is **only a player**. It doesn't include, sell or link to any channels. You need your own IPTV service.
> Not affiliated with any IPTV provider.

## Why

Paid IPTV players work well, but the good ones are closed source and subscription-based. Channelbox aims to be a
free alternative that's simple enough for anyone – especially people who just want their TV to behave like the cable
box they're used to.

## Download

Grab the latest `channelbox.apk` from **[Releases](https://github.com/Axon07/Channelbox/releases)**, or the
newest test build from the **[Actions](https://github.com/Axon07/Channelbox/actions)** tab (*channelbox-apks* artifact).

**Fire TV / Android TV:** open the **Downloader** app and enter code **`5502923`** – it always gets the latest version.
You can also use a USB stick, or `adb install channelbox.apk`.
The latest version is always at `https://github.com/Axon07/Channelbox/releases/latest/download/channelbox.apk`.

**Updates:** Channelbox checks for a new release when it starts and offers to install it (turn that off in Settings).
You can also use **Settings → Check for updates**. The first time, Android asks you to allow Channelbox to install apps.

## Features

| | |
|---|---|
| **Sources** | M3U / M3U8 playlist URL (incl. `.gz`), Xtream Codes login, Stalker / Ministra portal (portal URL + MAC address – see [Stalker portals](#stalker-portals)). **Several playlists at once**, merged into one lineup, each with its own name, guide and on/off switch. Huge provider lists (100 MB+) are read as they download |
| **Add from your phone** | The add-playlist screen shows a QR code: scan it, type the link, login or portal + MAC on your phone, press Send. Works only on your home network and only while that screen is open |
| **TV guide** | XMLTV (incl. `.xml.gz`) from every guide the playlist lists (`url-tvg`), from Xtream, plus your own URLs. Channels are matched by `tvg-id`, then a loose id match, then by name (ignoring "US:", "HD", "(1080p)" and similar) |
| **Guide screen** | Cable-style grid (channels × time) with a live video preview in the corner, programme details and a "now" line. Hold Back to go straight back to the channel full screen. Marks programmes set to record and locked channels; adapts to small screens |
| **Groups menu** | Back from the guide: channel groups (one section per playlist when you have several). Back again (or ◀) opens the side menu: Search, Live TV, Movies, TV Shows, Recordings and Settings |
| **Movies & TV Shows** | From the playlist or the provider's Xtream API: poster grid by category, search, "Continue watching", seasons and episodes, resume where you stopped. Hide categories you don't want (Settings → Playlist & guide → Movie / TV show categories, or hold OK on a category) Xtream-style M3U links get real posters and details from the API automatically |
| **Pause live TV** | Play/Pause while watching: the channel is buffered on the box from that moment (up to 2 hours); resume behind live, ⏩ to jump back to live |
| **Guide search** | Search programme titles across the whole guide; watch what's on, catch up on what aired, record what's coming |
| **Your lineup** | Rename, renumber, hide or lock channels; rename, reorder, hide or lock groups (hold OK). Survives playlist reloads; undo from Settings |
| **Parental PIN** | Lock channels, groups and movie/show categories, optionally Settings too. Zapping skips locked channels |
| **Playback** | Media3/ExoPlayer: HLS, MPEG-TS, DASH (`.mpd`, also `.mpd/…` links), Smooth Streaming, RTSP, RTMP and UDP multicast (`udp://@239.…`). Unknown links are tried as TS → HLS → DASH. A broken channel shows a message and retries – it never crashes the app |
| **Stream options** | Kodi-style `url|User-Agent=…&Referer=…`, `#EXTVLCOPT:http-user-agent/http-referrer`, `#EXTHTTP:{…}`, `#KODIPROP:inputstream.adaptive.stream_headers` |
| **DRM** | `#KODIPROP` `license_type`/`license_key`, `drm_legacy` and `drm` (JSON). ClearKey with keys in the playlist (`kid:key`, JSON, or key only) – also on streams whose manifest only lists PlayReady/Widevine. Widevine and PlayReady license servers with headers (`url|Header=Value|…`); PlayReady falls back to Widevine on boxes without it, and Widevine retries at security level L3 when L1 fails |
| **Catch-up / archive** | Watch programmes that already aired, straight from the guide (↺). M3U `catchup` = `default` / `append` / `shift` / `flussonic` / `xc` with `catchup-source` templates (`${start}`, `{utc}`, `{duration:60}`, `{Y}-{m}-{d}` …), and Xtream Codes archives (server time zone aware). Also "watch from the start" for shows that are on now. Channels with catch-up show a ↺ clock icon in the guide and channel list, and can be **rewound while watching live** (⏪ on the remote or the control bar, 30 s per press; ⏩ skips ahead, ■ / LIVE returns to live) |
| **Audio, quality & subtitles** | CC turns captions on and off. The ⋯ menu (or hold OK / Menu) picks subtitles, audio track, quality and picture size (fit, zoom, stretch). Optional maximum quality for slow connections |
| **Backup & restore** | Settings → Playlist & guide → Back up data / Restore data: playlists (with logins), settings, favourites and channel changes in one file. Uses the box's file picker, or the Download folder on boxes without one |
| **Organise** | Settings → Playlist & guide → Organise (or hold OK on a group or channel → Select…): tick many groups to hide or show at once, put groups in your order, tick many channels to add to Favourites or hide, and sort Favourites. Hold OK on a row to move it (▲ ▼, OK to drop) |
| **Jellyfin** | Add your own Jellyfin server's movies & shows to Movies and TV Shows – next to your provider's (in their own categories) or instead of them. Settings → Playlist & guide → Jellyfin. Plays straight from the server |
| **Player engine** | Buffer size (none → very large), hardware or software video and audio decoding, audio passthrough for Dolby / DTS receivers, auto frame rate (AFR) to match the TV's refresh rate to the video, and HDR10 / HLG / Dolby Vision on boxes and TVs that support them |
| **Audio formats** | Bundled FFmpeg decoder plays Dolby Digital (AC3), Dolby Digital+ (E-AC3), DTS and TrueHD on boxes that can't – no more silent sports channels. Optional "decode all audio in software" setting |
| **Network & VPN** | Built-in **WireGuard VPN** (paste or load a config from any VPN provider; only Channelbox or the whole box), **HTTP / SOCKS5 proxy**, **encrypted DNS** (DNS-over-HTTPS: Cloudflare, Google, Quad9, AdGuard or custom) and a **user agent** setting – for privacy and for networks that interfere with streaming |
| **Multi-view** | Watch up to 4 channels at once (green button, ⋯ menu → Multi-view, or the on-screen button). The layout adapts to 2, 3 or 4 tiles; add more with Menu. Hold OK / long-press a tile for full screen, change or remove. Sound follows the highlighted tile |
| **Mini player** | Picture-in-picture when you press Home (optional) or from the on-screen buttons |
| **Stream info** | The info banner shows resolution, frame rate, codec, audio format, bitrate, available caption languages and VPN status |
| **Recording** | Record what's on now (● / red button) or schedule a programme from the guide. TS, HLS (incl. AES-128) and DASH. Save to this box, a USB drive / SD card, a network share (SMB) or WebDAV. DRM channels are saved exactly as delivered – still encrypted – and play back through the same licence as live TV |
| **Player controls** | Movies, episodes, recordings and catch-up: seek bar, elapsed/remaining time, ⏪ 10 s / ⏯ / ⏩ 30 s, CC and the ⋯ menu; tap the bar to jump |
| **Zapping** | Channel ▲/▼, number entry, previous channel, now/next info banner with a progress bar |
| **Channel list** | Press ◀ while watching: channels by group with what's on now |
| **Favourites** | Hold OK on a channel in the list or guide |
| **Set-top box mode** | Optional start on boot and on wake from sleep, optional "use as home screen", resumes the last channel. Back goes TV → Guide → Groups → side menu → "Press Back again to exit"; the last step can also be set to never exit, or exit straight away |
| **Touch & mouse** | Tap the picture for the controls (record, pause, live, favourite, CC, multi-view, mini player, ⋯); swipe up/down to change channel; tap anything in the list or guide |
| **Background refresh** | Playlist and guide reload every 6/12/24/48 hours, or only when you choose |

## Stalker portals

Some providers give you a **portal URL and a MAC address** instead of a playlist link or a login – the setup a MAG set-top box uses
(Stalker / Ministra middleware). Add it under **Settings → Playlist & guide → Add another playlist → Stalker portal (MAC)**
(or from your phone, where typing the MAC is easier).

| Field | What to enter |
|---|---|
| Portal URL | Exactly what the provider sent, e.g. `http://example.com:8080/c/` or `http://example.com/stalker_portal/c/`. Channelbox finds the portal's API behind it (`portal.php`, `server/load.php`, `stalker_portal/server/load.php`) |
| MAC address | The one registered with the provider, e.g. `00:1A:79:12:34:56` (dashes or no separators work too) |
| Username / password | Only if the provider gave you one – some portals ask for a login on top of the MAC |
| More options | Serial number and device IDs – only for providers that tie the account to a particular box |

What works: live TV with the portal's channel groups and logos, the portal's own TV guide (plus any XMLTV guide you add,
which wins where both cover a channel), movies and TV shows with seasons and episodes, pause live TV, recording, multi-view
and favourites. Channelbox presents itself as a MAG250 box, signs in again by itself if the portal drops the session, and
fetches a fresh link from the portal each time you play something (portal links only last a few seconds).

Not yet: catch-up / rewind on Stalker channels, and radio.

> [!TIP]
> Most portals allow one box per MAC address. Watching on another device with the same MAC can sign Channelbox out –
> it signs back in by itself, but the other device may then be signed out instead.

## Playback settings

**Settings → Playback.** Channelbox uses Google's Media3 ExoPlayer (the engine behind most Android TV apps); these
options tune it. Buffer size, decoders and passthrough take effect the next time Channelbox starts; auto frame rate
applies straight away.

| Setting | Options | Default | When to change it |
|---|---|---|---|
| Buffer size | None · Small · Medium · Large · Very large | Medium | Bigger for a shaky connection, smaller for faster channel changes |
| Video decoder | Hardware · Software | Hardware | Software if a channel shows green blocks or won't play (works the box harder; copy-protected channels always use hardware) |
| Audio decoder | Hardware · Software | Hardware | Software if some channels play without sound |
| Audio passthrough | On · Off | On | Off if Dolby / DTS channels are silent through the TV's speakers; on to send them untouched to a receiver or soundbar |
| Auto frame rate (AFR) | On · Off | Off | On for smoother motion: the TV switches refresh rate to match the video (e.g. 50 Hz for European channels, 23.976 Hz for films). Waits ~1.5 s after a channel change; the screen may blank for a moment |
| Dolby Vision | Auto · On · Off (HDR10) | Auto | Auto plays Dolby Vision only when the TV supports it and otherwise falls back to HDR10. Off if the picture looks purple or green. *HDR on this TV* (just below) shows what your TV and box support. HDR10 and HLG play automatically with the hardware decoder |
| Captions on by default | On · Off | Off | On to start every channel and video with subtitles / CC |
| Maximum quality | Best available · up to 1080p · 720p · 480p · Automatic | Best available | Lower on a slow connection, or Automatic to follow the connection speed |

Buffer sizes in detail:

| Size | Keeps loaded ahead | Starts playing after | Restarts after a stall |
|---|---|---|---|
| None | 1.5–4 s | 0.5 s | 1 s |
| Small | 5–15 s | 1 s | 2 s |
| Medium | 15–30 s | 2 s | 4 s |
| Large | 30–60 s | 4 s | 8 s |
| Very large | 60–120 s | 6 s | 12 s |

On live channels the buffer can only fill up to the live point, so a bigger buffer mostly helps with dropouts and
movies; it doesn't put you further behind live.

## Languages

**Settings → Language** switches the whole app straight away. English is the default.

| | | | |
|---|---|---|---|
| English | Français (French) | Español (Spanish) | Português (Brasil / Portugal) |
| Deutsch (German) | Italiano (Italian) | Nederlands (Dutch) | Polski (Polish) |
| Українська (Ukrainian) | Русский (Russian) | Română (Romanian) | Ελληνικά (Greek) |
| Magyar (Hungarian) | Türkçe (Turkish) | العربية (Arabic, right-to-left) | हिन्दी (Hindi) |
| ਪੰਜਾਬੀ (Punjabi) | 中文 (Chinese, Simplified) | 日本語 (Japanese) | Tiếng Việt (Vietnamese) |
| Filipino (Tagalog) | বাংলা (Bengali) | اردو (Urdu, right-to-left) | Bahasa Indonesia (Indonesian) |
| فارسی (Persian, right-to-left) | 한국어 (Korean) | Shqip (Albanian) | Srpski (Serbian) |
| Hrvatski (Croatian) | Soomaali (Somali) | ไทย (Thai) | Čeština (Czech) |
| Slovenčina (Slovak) | | | |

Translations live in `app/src/main/assets/i18n/<code>.json` (English text → translation). Anything not
translated shows in English. To add or fix a language, run `python3 tools/i18n_keys.py` for the full list
of texts, edit the file and open a pull request – corrections from native speakers are very welcome.

## Remote control

| Button | Watching TV | Channel list | Guide |
|---|---|---|---|
| ▲ / ▼, CH+ / CH− | Change channel | Move (wraps top ↔ bottom) | Move, wraps top ↔ bottom (CH± = page) |
| ◀ | Open channel list | Previous group | Earlier |
| ▶ | Back to the channel you watched before (again to swap back), like TiviMate | Next group | Later |
| Info | Show/hide info banner | – | – |
| OK | Control bar (OK again = pause; ◀ ▶ record, live, CC, multi-view, ⋯; ▲ hide) | Watch | On now: watch · finished: catch-up · future: record menu |
| Hold OK | ⋯ menu: audio, subtitles, quality, picture size | ★ favourite | Programme menu (watch / from start / record / favourite) |
| ● Record / red | Record what's on (again = stop) | – | Record / cancel the selected show |
| 0–9 | Go to channel number | Go to channel number | Jump to channel |
| Guide | Open guide | Open guide | Back to TV |
| Menu | ⋯ menu | Open guide | Groups menu |
| Back | Open guide | Close | Groups → side menu → press Back twice to exit |
| Last / Recall | Previous channel | Previous channel | – |
| CC / Subtitles | Captions on/off | – | – |
| Green | Multi-view | – | – |
| ⏯ Play/Pause | Pause live TV / resume | – | – |
| ⏪ Rewind | Rewind live TV, 30 s per press (channels with catch-up ↺) | – | – |
| ⏩ / ■ Stop | ⏩ 30 s ahead after rewinding · back to live (after a pause) | – | – |
| Search | Search the guide | – | Search the guide |

**Groups menu:** ▲▼ choose, OK select, ▶ to the guide, ◀ or Back to the side menu, hold OK on a group to rename / move / hide / lock it.
Search, Live TV, Movies, TV Shows, Recordings and Settings are on the side menu.
**Guide:** hold OK on a programme → *Edit channel…* to rename, renumber, hide or lock the channel.
**Playing a movie, episode, recording or catch-up:** OK / ⏯ pause, ◀ ▶ (or ⏪ ⏩) skip 10 s back / 30 s ahead, ▼ to move onto the buttons (◀ ▶ choose, OK press, ▲ back),
CC for captions, hold OK for the ⋯ menu, Back hides the controls and then returns to the list.

### Where recordings are saved

By default `Android/data/io.github.axon07.channelbox/files/recordings/` on the box (`.ts`, `.mp4` for fMP4 HLS, or a folder with `index.m3u8` / `manifest.mpd` for HLS channels with separate audio and DASH). Change it in **Settings → Recordings**. Recordings on the box are removed if the app is uninstalled. Folder recordings stay on the box or USB / SD card. Scheduled recordings only happen if the box is on; a box in deep sleep may start late.

### Touch screen / mouse

Tap the picture to show the controls. Swipe up for the next channel, down for the previous one. In the channel list and guide, tap to watch and press-and-hold for more. In the guide, swipe sideways to move through time, tap the clock to jump back to now, and tap the small video to go back to full screen.

## Building

Requires Android Studio (Ladybug or newer) or JDK 17 plus the Android SDK.

```bash
git clone https://github.com/Axon07/Channelbox.git
cd Channelbox
./gradlew assembleDebug        # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest    # parser tests
```

Every push also builds APKs on GitHub Actions (see the *Actions* tab → *channelbox-apks* artifact).

### Installing on a TV box

```bash
adb connect <box-ip>:5555
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or upload the APK somewhere and use the *Downloader* app on the box.

## Releasing

Releases are built by GitHub Actions and signed with a permanent key, so the in-app updater can install each new
version over the old one. One-time setup (Windows PowerShell, JDK 17 installed):

```powershell
$env:JAVA_HOME = (Get-ChildItem "C:\Program Files\Eclipse Adoptium" -Directory -Filter "jdk-17*" | Select-Object -First 1).FullName
& "$env:JAVA_HOME\bin\keytool.exe" -genkeypair -v -keystore "$env:USERPROFILE\channelbox-release.jks" -alias channelbox -keyalg RSA -keysize 4096 -validity 36500 -dname "CN=Channelbox"
[Convert]::ToBase64String([IO.File]::ReadAllBytes("$env:USERPROFILE\channelbox-release.jks")) | Set-Clipboard
```

Then in the repo: **Settings → Secrets and variables → Actions → New repository secret**:

| Secret | Value |
|---|---|
| `SIGNING_KEYSTORE_BASE64` | paste (the keystore, copied to the clipboard by the last line) |
| `SIGNING_STORE_PASSWORD` | the password you typed for the keystore |
| `SIGNING_KEY_ALIAS` | `channelbox` |
| `SIGNING_KEY_PASSWORD` | the same password |

Keep a backup of `channelbox-release.jks` and its password somewhere safe – if it's lost, existing installs can't be
updated and everyone has to uninstall and reinstall.

To release: **Releases → Draft a new release → new tag (e.g. `v1.0.0`) → Publish**. The workflow sets the app version from the
tag, builds the signed APK and attaches it as `channelbox.apk`; installed apps will offer the update.

The release notes get a **What's changed** list automatically: every change since the previous release, one line
each, taken from the commit titles (housekeeping commits are left out), plus the Downloader code. Anything you type
in the release description is kept above it. Don't use GitHub's *Generate release notes* button – it adds a second list.

Version numbers: `v0.x.y` while in testing – bump the last number for fixes (`v0.6.1`) and the middle one for new
features (`v0.7.0`). `v1.0.0` once it's been stable in daily use.

## Making it act like a cable box

1. **Settings → Start-up & exit → Start when the box turns on** and/or **Start when the box wakes up** (both off by default).
   Also consider **Settings → Start-up & exit → Back button → Never exit (set-top box)** so nobody exits by accident.
2. On Android 10 and newer, Android only lets an app open itself if it has the *Display over other apps* permission. Use **Settings → Start-up & exit → Permission to start by itself**. Some TV firmware hides that screen; grant it over adb instead:
   ```bash
   adb shell appops set io.github.axon07.channelbox SYSTEM_ALERT_WINDOW allow
   ```
3. Optional: **Settings → Use as home screen**, then press Home and choose Channelbox → *Always*. The box then boots, and Home returns, straight into live TV.
   Google TV (Chromecast with Google TV, newer Onn/Walmart boxes) ignores third-party home screens unless the stock launcher is disabled over adb. That step is at your own risk:
   ```bash
   adb shell pm disable-user --user 0 com.google.android.apps.tv.launcherx
   ```
   Undo with `pm enable`.

## Project layout

```
app/src/main/java/io/github/axon07/channelbox/
├── App.kt                 dependency container
├── MainActivity.kt
├── data/                  M3U, Xtream, XMLTV parsers, movies & shows, recorder, Room DB, settings
├── player/                ExoPlayer wrapper (auto-reconnect, DRM), pause-live-TV buffer
├── system/                boot receiver, background refresh, recording worker, phone link, home-screen helpers
└── ui/                    Compose for TV screens: player overlays, guide, settings, setup
```

Stack: Kotlin, Jetpack Compose for TV, Media3 ExoPlayer, Room, DataStore, WorkManager, OkHttp, Coil, ZXing. Min SDK 21 (Android 5.0).

## Roadmap

- [ ] Series recording (every episode of a show)
- [ ] Play the next episode automatically
- [ ] Optional TMDB artwork for providers that send poor pictures
- [ ] Translations

## License

[GPL-3.0](LICENSE). You can use, change and share Channelbox freely, but anything you distribute that's built from it must stay open source under the same license.
