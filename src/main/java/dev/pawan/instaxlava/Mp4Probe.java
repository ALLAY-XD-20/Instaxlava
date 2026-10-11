package dev.pawan.instaxlava;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Set;

/**
 * Looks inside an MP4 with one or two small range requests (start of the file, then the end if the
 * "moov" box is at the end): which tracks it has (audio / video), the codecs, whether it is fragmented
 * and how long it is. Used to pick a stream that really contains audio and to explain failures in the log.
 */
final class Mp4Probe {

    private static final int WINDOW = 512 * 1024;
    private static final int INSPECT_WINDOW = 256 * 1024;
    private static final Set<String> AUDIO_CODECS = Set.of("mp4a", "ac-3", "ec-3", "opus", "alac", "fLaC", "samr", "sawb");
    private static final Set<String> VIDEO_CODECS = Set.of("avc1", "avc3", "hvc1", "hev1", "vp08", "vp09", "av01", "mp4v");

    /** What an MP4 file contains. */
    record Format(boolean recognized, boolean audio, boolean video, boolean fragmented, String brand,
                  String audioCodec, String videoCodec, long durationMs) {

        static final Format UNKNOWN = new Format(false, false, false, false, null, null, null, 0);

        boolean tracksSeen() { return audio || video; }

        /** Confirmed to have tracks but none of them audio. */
        boolean knownNoAudio() { return recognized && tracksSeen() && !audio; }

        boolean audioOnly() { return audio && !video; }

        String describe() {
            if (!recognized) return "unreadable";
            String kind = audioOnly() ? "audio-only" : audio ? "video+audio" : video ? "VIDEO-ONLY (no audio)" : "no tracks found";
            StringBuilder sb = new StringBuilder(kind);
            if (audioCodec != null) sb.append(' ').append(audioCodec);
            if (videoCodec != null) sb.append(' ').append(videoCodec);
            sb.append(fragmented ? ", fragmented" : ", progressive");
            if (brand != null) sb.append(", brand ").append(brand.trim());
            if (MediaInfo.knownDuration(durationMs)) sb.append(", ").append(durationMs / 1000.0).append('s');
            return sb.toString();
        }
    }

    private Mp4Probe() {}

    // ── duration only ───────────────────────────────────────────────────────

    static long durationMs(HttpClient http, String url, String userAgent) {
        try {
            long d = scan(fetch(http, url, userAgent, "bytes=0-" + (WINDOW - 1), WINDOW));
            if (d > 0) return d;
            return scan(fetch(http, url, userAgent, "bytes=-" + WINDOW, WINDOW));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    // ── full inspection ─────────────────────────────────────────────────────

    static Format inspect(HttpClient http, String url, String userAgent) {
        try {
            Format f = analyze(fetch(http, url, userAgent, "bytes=0-" + (INSPECT_WINDOW - 1), INSPECT_WINDOW));
            if (!f.tracksSeen()) {
                Format t = analyze(fetch(http, url, userAgent, "bytes=-" + INSPECT_WINDOW, INSPECT_WINDOW));
                if (t.tracksSeen() || t.durationMs() > 0) {
                    f = new Format(f.recognized() || t.recognized(), t.audio(), t.video(), f.fragmented() || t.fragmented(),
                            f.brand(), t.audioCodec(), t.videoCodec(), f.durationMs() > 0 ? f.durationMs() : t.durationMs());
                }
            }
            return f;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Format.UNKNOWN;
        } catch (Exception e) {
            return Format.UNKNOWN;
        }
    }

    static Format analyze(byte[] b) {
        String brand = null;
        if (b.length >= 12 && b[4] == 'f' && b[5] == 't' && b[6] == 'y' && b[7] == 'p') brand = fourcc(b, 8);

        boolean audio = false, video = false;
        String audioCodec = null, videoCodec = null;

        for (int i = indexOf(b, "hdlr", 0); i >= 0; i = indexOf(b, "hdlr", i + 4)) {
            if (i + 16 > b.length) break;
            String handler = fourcc(b, i + 12);
            if (handler.equals("soun")) audio = true;
            else if (handler.equals("vide")) video = true;
        }
        for (int i = indexOf(b, "stsd", 0); i >= 0; i = indexOf(b, "stsd", i + 4)) {
            if (i + 20 > b.length) break;
            String format = fourcc(b, i + 16);
            if (AUDIO_CODECS.contains(format)) {
                audio = true;
                if (audioCodec == null) audioCodec = format;
            } else if (VIDEO_CODECS.contains(format)) {
                video = true;
                if (videoCodec == null) videoCodec = format;
            }
        }
        boolean fragmented = indexOf(b, "mvex", 0) >= 0 || indexOf(b, "moof", 0) >= 0;
        long duration = scan(b);
        boolean recognized = brand != null || audio || video || duration > 0;
        return new Format(recognized, audio, video, fragmented, brand, audioCodec, videoCodec, duration);
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static byte[] fetch(HttpClient http, String url, String ua, String range, int limit) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", ua)
                .header("Range", range)
                .timeout(Duration.ofSeconds(12))
                .GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = resp.body()) {
            if (resp.statusCode() != 200 && resp.statusCode() != 206) return new byte[0];
            return in.readNBytes(limit); // a server that ignores Range is cut off after the limit
        }
    }

    private static int indexOf(byte[] b, String tag, int from) {
        byte t0 = (byte) tag.charAt(0), t1 = (byte) tag.charAt(1), t2 = (byte) tag.charAt(2), t3 = (byte) tag.charAt(3);
        for (int i = Math.max(4, from); i + 4 <= b.length; i++) {
            if (b[i] == t0 && b[i + 1] == t1 && b[i + 2] == t2 && b[i + 3] == t3) return i;
        }
        return -1;
    }

    private static String fourcc(byte[] b, int o) {
        char[] c = new char[4];
        for (int i = 0; i < 4; i++) {
            int v = b[o + i] & 0xFF;
            c[i] = v >= 32 && v < 127 ? (char) v : '?';
        }
        return new String(c);
    }

    private static long scan(byte[] b) {
        for (int i = 4; i + 40 < b.length; i++) {
            if (b[i] != 'm' || b[i + 1] != 'v' || b[i + 2] != 'h' || b[i + 3] != 'd') continue;
            int version = b[i + 4] & 0xFF;
            int base = i + 8; // after type, version and 3 flag bytes
            long timescale;
            long duration;
            if (version == 0) {
                timescale = u32(b, base + 8);
                duration = u32(b, base + 12);
            } else if (version == 1) {
                timescale = u32(b, base + 16);
                duration = u64(b, base + 20);
            } else {
                continue;
            }
            if (timescale <= 0 || duration <= 0) continue;
            long ms = duration * 1000L / timescale;
            if (ms >= 500 && ms <= 6L * 3600_000L) return ms;
        }
        return 0;
    }

    private static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF) << 24) | ((b[o + 1] & 0xFF) << 16) | ((b[o + 2] & 0xFF) << 8) | (b[o + 3] & 0xFF);
    }

    private static long u64(byte[] b, int o) {
        return (u32(b, o) << 32) | u32(b, o + 4);
    }
}
