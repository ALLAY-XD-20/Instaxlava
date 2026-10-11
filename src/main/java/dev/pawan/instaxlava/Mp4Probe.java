package dev.pawan.instaxlava;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Reads the duration of an MP4 from its "mvhd" box using one or two small range requests
 * (start of file, then end of file if the moov box is at the end). Returns 0 when it cannot tell.
 */
final class Mp4Probe {

    private static final int WINDOW = 512 * 1024;

    private Mp4Probe() {}

    static long durationMs(HttpClient http, String url, String userAgent) {
        try {
            long d = scan(fetch(http, url, userAgent, "bytes=0-" + (WINDOW - 1)));
            if (d > 0) return d;
            return scan(fetch(http, url, userAgent, "bytes=-" + WINDOW));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0;
        } catch (Exception e) {
            return 0;
        }
    }

    private static byte[] fetch(HttpClient http, String url, String ua, String range) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", ua)
                .header("Range", range)
                .timeout(Duration.ofSeconds(12))
                .GET().build();
        HttpResponse<InputStream> resp = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = resp.body()) {
            if (resp.statusCode() != 200 && resp.statusCode() != 206) return new byte[0];
            return in.readNBytes(WINDOW); // a server that ignores Range is cut off after WINDOW bytes
        }
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
