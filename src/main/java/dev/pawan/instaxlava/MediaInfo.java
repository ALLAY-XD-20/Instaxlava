package dev.pawan.instaxlava;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolved Instagram media: a primary stream URL, backup stream URLs (other qualities / audio-only DASH),
 * and metadata.
 */
public record MediaInfo(String streamUrl, String title, String author, long durationMs, String artworkUrl,
                        List<String> fallbackUrls) {

    public static final String DEFAULT_TITLE = "Instagram Video";
    private static final Pattern OE = Pattern.compile("[?&]oe=([0-9a-fA-F]+)");

    public MediaInfo {
        fallbackUrls = fallbackUrls == null ? List.of() : List.copyOf(fallbackUrls);
    }

    public MediaInfo(String streamUrl, String title, String author, long durationMs, String artworkUrl) {
        this(streamUrl, title, author, durationMs, artworkUrl, List.of());
    }

    /** Primary URL first, then the backups. */
    public List<String> allUrls() {
        List<String> all = new ArrayList<>();
        if (streamUrl != null && !streamUrl.isBlank()) all.add(streamUrl);
        all.addAll(fallbackUrls);
        return all;
    }

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
        return new MediaInfo(streamUrl, title, author, ms, artworkUrl, fallbackUrls);
    }

    public MediaInfo withMeta(String newTitle, String newAuthor, String newArtwork) {
        return new MediaInfo(streamUrl, newTitle, newAuthor, durationMs, newArtwork, fallbackUrls);
    }

    public boolean lacksMetadata() {
        return title == null || title.isBlank() || title.equals(DEFAULT_TITLE)
                || unknownAuthor(author) || artworkUrl == null || artworkUrl.isBlank();
    }
}
