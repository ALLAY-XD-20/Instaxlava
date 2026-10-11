# Changelog

All notable changes to InstaXlava are listed here.

## [2.0.3] - 2026-10-11

### Fixed
- Audio not streaming while title, author and artwork loaded fine: the plugin now looks inside every stream Instagram offers (tracks, codecs, fragmentation, length), removes streams that contain no audio, and plays audio-only streams first
- The reel's own sound file (when Instagram lists one) is added as an extra stream to try

### Added
- `inspectStreams` option (default on)
- The log line of each load now lists what is inside every stream, so a silent track can be diagnosed from the log

## [1.1.2] - 2026-10-11

### Fixed
- Tracks that loaded with full info but played no sound: every stream URL Instagram gives (smallest progressive MP4, audio-only DASH, other qualities) is now tried in turn, each with two readers, instead of only the first URL
- A stream that ends instantly without producing audio (for example a video-only file) is treated as a failure and the next stream is tried, instead of the track silently finishing

### Added
- Playback log lines showing which stream and reader is opened and why one failed
- The smallest video quality is tried first, which saves bandwidth because the audio is interleaved in the file

## [1.1.1] - 2026-10-11

### Added
- Logged-in media endpoint, used first when `cookies` is set: works from hosting-provider IPs that Instagram blocks, and returns title, author, artwork and length (also for videos inside carousels)
- Clear warning in the log, with the fix, when Instagram answers 401, 403 or 429 and no cookie is configured
- Log note when a page has preview tags but no video

## [1.1.0] - 2026-10-07

### Fixed
- Tracks now always carry metadata: title, author, artwork and length. Missing fields are filled from the page's Open Graph tags, and a missing length is read from the MP4 header
- Playback no longer depends on a single reader: if the MP4 reader fails, Lavaplayer's own container detection is tried on the same stream
- Build no longer depends on what the Lavalink Gradle plugin puts on the classpath: plugin API, Lavaplayer, Spring Boot, Jackson and SLF4J are declared explicitly (compileOnly)

### Added
- Link parser rewritten: accepts share tokens (`?xtok=...==`, `?igsh=`, `?utm_source=`), embed pages, `l.instagram.com` and login-redirect links, links without a scheme, upper case hosts, quotes or text around the link, `instagram://media?id=` deep links, and the extra hosts `fxinstagram.com` and `instagramez.com`
- Open Graph fallback for posts and reels (also used to fill thin metadata)
- One log line per load showing the method that worked, or why each method failed
- `probeDuration` option (default on)

## [1.0.0] - 2026-10-07

First release.

### Added
- Instagram source for Lavalink v4 (`instagram`), enabled with `plugins.instaxlava.engine: enable`
- Posts, reels, IGTV, username links, share links, mirror hosts (`instagr.am`, `ddinstagram.com`, `kkinstagram.com`, `vxinstagram.com`) and raw numeric media IDs
- Instagram audio pages through the clips music API, with paging-cursor retry
- Mirror playback for audio pages with no direct stream (searches another source and matches title and artist)
- Post and reel resolution through the logged-out clips query, session GraphQL and the public embed page
- Stream URL cache that expires before the CDN link does
- Retry with a fresh link on failed starts, resume from the last position on mid-track failures
- Request limit, failure cool-down, optional proxy and cookies
- Gradle build following the official Lavalink plugin template layout
