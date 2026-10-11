# InstaXlava v1.1.0

Instagram audio source plugin for Lavalink v4. Author: Pawan (ALLAY_XD_20).

## What is new
- Tracks show full info: title, author, artwork and length
- More reliable playback: automatic fallback to Lavaplayer's own container detection
- Open Graph fallback for posts and reels
- Clear one-line log per load showing which method worked or why it failed
- More robust Gradle build

## Install
1. Download `instaxlava-1.1.0.jar` from the assets below.
2. Put it in Lavalink's `plugins/` folder.
3. Add to `application.yml`:

```yaml
plugins:
  instaxlava:
    engine: enable
```

Requires Lavalink 4.x and Java 17 or newer.

## Notes
Uses unofficial Instagram endpoints, which may change without notice. Mirror playback needs a search-capable source such as the YouTube plugin.
