package dev.pawan.instaxlava;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolved Instagram media: a direct CDN stream URL plus metadata. */
public record MediaInfo(String streamUrl, String title, String author, long durationMs, String artworkUrl) {

    private static final Pattern OE = Pattern.compile("[?&]oe=([0-9a-fA-F]+)");

    /** Instagram CDN links carry an "oe" query param (hex unix seconds) = expiry. Returns epoch millis or -1. */
    public static long cdnExpiryMillis(String url) {
        if (url == null) return -1;
        Matcher m = OE.matcher(url);
        if (!m.find()) return -1;
        try {
            return Long.parseLong(m.group(1), 16) * 1000L;
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    public long expiresAtMillis() {
        return cdnExpiryMillis(streamUrl);
    }
}
