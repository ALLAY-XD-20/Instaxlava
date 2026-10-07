# InstaXlava

Lavalink v4 plugin that plays Instagram posts, reels, IGTV and reel-audio pages.
Author: **Pawan**

Built on the official [lavalink-plugin-template](https://github.com/lavalink-devs/lavalink-plugin-template)
layout (Java, Gradle, `lavalinkPlugin {}` block, `runLavalink`).

## Enable
```yaml
plugins:
  instaxlava:
    engine: enable
```
`engine: disable` turns it off. Other options are listed in `application.yml`.

## Setup (first time)
`gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar` come from the official template.
Either copy those three from a clone of the template into this folder, or run once with Gradle installed:
```
gradle wrapper --gradle-version 8.10.2
```

## Test
```
./gradlew runLavalink      # uses ./application.yml, loads the plugin
```

## Build
```
./gradlew build
```
Jar: `build/libs/instaxlava-1.0.0.jar` → put it in Lavalink's `plugins/` folder.

## Publish (JitPack, like the template)
Push to GitHub, then in your Lavalink `application.yml`:
```yaml
lavalink:
  plugins:
    - dependency: "com.github.ALLAY-XD-20:instaxlava:1.1.1"
      repository: "https://jitpack.io"
```

## Supported inputs
| Format | Example |
|---|---|
| Post | `instagram.com/p/CODE/` |
| Reel | `instagram.com/reel/CODE/`, `/reels/CODE/` |
| IGTV | `instagram.com/tv/CODE/` |
| With username | `instagram.com/USER/p/CODE/`, `/USER/reel/CODE/` |
| Share links | `instagram.com/share/CODE`, `/share/reel/CODE`, `/share/p/CODE` |
| Reel audio | `instagram.com/reels/audio/ID/` |
| Mirror / short hosts | `instagr.am`, `m.instagram.com`, `ddinstagram.com`, `kkinstagram.com`, `vxinstagram.com` |
| Numeric media ID | `3123456789012345678` (15+ digits, optional `_userid`) |

Query strings (`?igsh=...`) are ignored. Source name: `instagram`.

## How it resolves
- Posts/reels: logged-out clips query, then session GraphQL, then public embed page
- Audio: clips music API (paging-cursor retry), then OG metadata + mirror search on another source
- URL cache that expires before the CDN link; retry / re-resolve / resume on playback failure
- Concurrency limit and failure cool-down; optional proxy and cookies

## Notes
Uses unofficial Instagram endpoints: they can change without notice and may conflict with Instagram's terms.
Mirror playback needs a search-capable source (e.g. the YouTube plugin).
# Instaxlava
