# InstaXlava v2.0.3

Instagram audio source plugin for Lavalink v4. Author: Pawan (ALLAY_XD_20).

## What is new
- Fixes audio not streaming while the track info showed: every stream is inspected, streams without audio are skipped, audio-only streams are played first
- The reel's own sound file is used as an extra backup stream
- Each load logs what is inside every stream, so problems can be diagnosed from the log
- Includes all fixes from 1.1.x (all link formats, logged-in cookie support, retries and fallbacks)

## Install
1. Download `instaxlava-2.0.3.jar` from the assets below.
2. Put it in Lavalink's `plugins/` folder.
3. Add to `application.yml`:

```yaml
plugins:
  instaxlava:
    engine: enable
    # cookies: "sessionid=...; csrftoken=...; ds_user_id=..."   # needed if Instagram blocks your server IP
```

Requires Lavalink 4.x and Java 17 or newer.

## Notes
Uses unofficial Instagram endpoints, which may change without notice. Use a spare account for the cookie, never your main one.
