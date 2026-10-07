package dev.pawan.instaxlava;

import java.math.BigInteger;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Recognises every Instagram identifier we can play:
 *
 *   /p/CODE/   /reel/CODE/   /reels/CODE/   /tv/CODE/
 *   /USER/p/CODE/   /USER/reel/CODE/   /USER/tv/CODE/
 *   /reels/audio/ID/
 *   /share/CODE   /share/p/CODE   /share/reel/CODE   (redirect links, expanded by the resolver)
 *   hosts: instagram.com, www., m., instagr.am, ddinstagram.com, kkinstagram.com, vxinstagram.com
 *   raw numeric media IDs (15+ digits, optional _userid suffix)
 *   any query string / fragment is ignored
 */
public final class IGUrlParser {

    public enum Kind { POST, REEL, TV, AUDIO, SHARE }

    public record Parsed(Kind kind, String id) {
        public String canonicalUrl() {
            return switch (kind) {
                case POST -> "https://www.instagram.com/p/" + id + "/";
                case REEL -> "https://www.instagram.com/reel/" + id + "/";
                case TV -> "https://www.instagram.com/tv/" + id + "/";
                case AUDIO -> "https://www.instagram.com/reels/audio/" + id + "/";
                case SHARE -> id;
            };
        }

        /** Path segment used for the Referer header. */
        public String segment() {
            return switch (kind) {
                case REEL -> "reel";
                case TV -> "tv";
                default -> "p";
            };
        }
    }

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_";
    private static final BigInteger SIXTY_FOUR = BigInteger.valueOf(64);
    private static final Set<String> HOSTS = Set.of(
            "instagram.com", "instagr.am", "ddinstagram.com", "kkinstagram.com", "vxinstagram.com");
    private static final Set<String> MEDIA_KEYS = Set.of("p", "reel", "reels", "tv");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern NUMERIC_ID = Pattern.compile("\\d{15,}(?:_\\d+)?");

    private IGUrlParser() {}

    /** @return parsed identifier, or null if this is not an Instagram media URL / ID. */
    public static Parsed parse(String input) {
        if (input == null) return null;
        String trimmed = input.trim();

        if (NUMERIC_ID.matcher(trimmed).matches()) {
            String sc = mediaIdToShortcode(trimmed);
            return sc == null ? null : new Parsed(Kind.POST, sc);
        }

        URI uri;
        try {
            uri = URI.create(trimmed);
        } catch (IllegalArgumentException e) {
            return null;
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) return null;
        String host = uri.getHost();
        if (host == null || !isInstagramHost(host.toLowerCase(Locale.ROOT))) return null;

        String path = uri.getPath();
        if (path == null) return null;
        List<String> seg = Arrays.stream(path.split("/")).filter(s -> !s.isEmpty()).toList();
        if (seg.size() < 2) return null;

        int i = 0;
        // optional leading username: /username/reel/CODE
        if (seg.size() >= 3 && MEDIA_KEYS.contains(seg.get(1).toLowerCase(Locale.ROOT))
                && !MEDIA_KEYS.contains(seg.get(0).toLowerCase(Locale.ROOT))
                && !seg.get(0).equalsIgnoreCase("share")) {
            i = 1;
        }

        String key = seg.get(i).toLowerCase(Locale.ROOT);
        String next = seg.get(i + 1);

        switch (key) {
            case "p":
                return code(Kind.POST, next);
            case "tv":
                return code(Kind.TV, next);
            case "reel":
                return code(Kind.REEL, next);
            case "reels":
                if (next.equalsIgnoreCase("audio")) {
                    if (seg.size() > i + 2 && DIGITS.matcher(seg.get(i + 2)).matches())
                        return new Parsed(Kind.AUDIO, seg.get(i + 2));
                    return null;
                }
                return code(Kind.REEL, next);
            case "share":
                String shareUrl = "https://www.instagram.com/" + String.join("/", seg.subList(i, seg.size())) + "/";
                return new Parsed(Kind.SHARE, shareUrl);
            default:
                return null;
        }
    }

    /** Numeric media ID (optionally "ID_USERID") to base64-ish shortcode. */
    public static String mediaIdToShortcode(String mediaId) {
        String s = mediaId;
        int us = s.indexOf('_');
        if (us >= 0) s = s.substring(0, us);
        try {
            BigInteger n = new BigInteger(s);
            if (n.signum() <= 0) return null;
            StringBuilder sb = new StringBuilder();
            while (n.signum() > 0) {
                BigInteger[] qr = n.divideAndRemainder(SIXTY_FOUR);
                sb.append(ALPHABET.charAt(qr[1].intValue()));
                n = qr[0];
            }
            return sb.reverse().toString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** Shortcode to numeric media ID, or null if it contains invalid characters. */
    public static BigInteger shortcodeToMediaId(String code) {
        BigInteger r = BigInteger.ZERO;
        for (int i = 0; i < code.length(); i++) {
            int idx = ALPHABET.indexOf(code.charAt(i));
            if (idx < 0) return null;
            r = r.multiply(SIXTY_FOUR).add(BigInteger.valueOf(idx));
        }
        return r;
    }

    private static Parsed code(Kind kind, String id) {
        return CODE.matcher(id).matches() ? new Parsed(kind, id) : null;
    }

    private static boolean isInstagramHost(String host) {
        for (String h : HOSTS) {
            if (host.equals(h) || host.endsWith("." + h)) return true;
        }
        return false;
    }
}
