package dev.pawan.instaxlava;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolved Instagram media: a direct CDN stream URL plus metadata. */
public record MediaInfo(String streamUrl, String title, String author, long durationMs, String artworkUrl) {

    public static final String DEFAULT_TITLE = "Instagram Video";
    private static final Pattern OE = Pattern.compile("[?&]oe=([0-9a-fA-F]+)");

    /** True when the duration is a real value (not 0 and not Lavaplayer's "unknown" marker). */
    public static boolean knownDuration(long ms) {
        return ms > 0 && ms != Long.MAX_VALUE;
    }

    public static boolean unknownAuthor(String a) {
        return a == null || a.isBlank() || a.equalsIgnoreCase("Unknown") || a.equalsIgnoreCase("User Unknown");
    }

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

    public MediaInfo withDuration(long ms) {
        return new MediaInfo(streamUrl, title, author, ms, artworkUrl);
    }

    public boolean lacksMetadata() {
        return title == null || title.isBlank() || title.equals(DEFAULT_TITLE)
                || unknownAuthor(author) || artworkUrl == null || artworkUrl.isBlank();
    }
}
