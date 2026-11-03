# Metrolist Scrobble

> **This is an unofficial fork of [Metrolist](https://github.com/MetrolistGroup/Metrolist)**, the YouTube Music client for Android by the Metrolist team.
> All credit for the app itself goes to the upstream project. This fork only adds one feature on top.
> Please report issues with this feature here, not upstream.

## What this fork adds

**YouTube Music recorder:** records what you play in the official YouTube Music app and adds it to a local Metrolist playlist automatically.

- Only counts tracks you actually listened to (30 seconds by default, adjustable in the settings); skipped tracks are ignored
- No duplicates: a song that is already in the playlist (same video ID, or same title and artist) is not added again
- No Google login and no API key needed
- Playlist: *Played in YouTube Music* / *Gehört in YouTube Music*; after a backup restore or playlist import, an existing playlist with that name is picked up again
- Works when YouTube Music runs in a work profile
- The playlist can be shown as its own entry in the Android Auto menu (*Settings → Android Auto*)

## Install

1. Download the APK from [Releases](https://github.com/fuzzyfly0/metrolist-scrobble/releases).
2. Install it. It runs as a separate app (*Metrolist Scrobble*, `com.metrolist.music.scrobble`) next to the official Metrolist.
3. Open *Settings → Integrations → YouTube Music recorder*, turn it on and grant notification access.

## Build

```bash
./gradlew :app:assembleIzzyDebug
```

Releases are built by GitHub Actions when a `v*` tag is pushed. Signing needs the repository secrets `SIGNING_KEYSTORE_BASE64` and `SIGNING_PASSWORD`.

## License

GPL-3.0, same as upstream. See [LICENSE](LICENSE).
