package dev.pawan.instaxlava;

import java.math.BigInteger;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Recognises every Instagram identifier we can play. Anything after the shortcode (query string, fragment,
 * trailing path such as /embed/captioned/) is ignored, so share tokens like ?xtok=...==, ?igsh=..., ?utm_source=...
 * and ?img_index=2 all work.
 *
 *   /p/CODE   /reel/CODE   /reels/CODE   /tv/CODE
 *   /USER/p/CODE   /USER/reel/CODE   /USER/tv/CODE
 *   /reels/audio/ID
 *   /share/CODE   /share/p/CODE   /share/reel/CODE   /share/v/CODE   (redirect links, expanded by the resolver)
 *   embed pages: /p/CODE/embed/, /reel/CODE/embed/captioned/
 *   link shims: l.instagram.com/?u=ENCODED_URL, /accounts/login/?next=/reel/CODE/
 *   hosts: instagram.com (www., m., any subdomain), instagr.am, ddinstagram.com, kkinstagram.com,
 *          vxinstagram.com, fxinstagram.com, instagramez.com
 *   with or without scheme (instagram.com/reel/CODE, //www.instagram.com/...), http or https, uppercase hosts,
 *   surrounding quotes / angle brackets / extra text around the link
 *   app deep links: instagram://media?id=NUMERIC
 *   raw numeric media IDs (15+ digits, optional _userid suffix)
 *
 * Not supported (they need a logged-in session): stories and highlights.
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
    private static final Set<String> MEDIA_KEYS = Set.of("p", "reel", "reels", "tv");
    private static final Pattern CODE = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Pattern DIGITS = Pattern.compile("\\d+");
    private static final Pattern NUMERIC_ID = Pattern.compile("\\d{15,}(?:_\\d+)?");
    private static final Pattern DEEP_LINK = Pattern.compile("(?i)^instagram://[a-z]+\\?(?:.*&)?id=(\\d{15,}(?:_\\d+)?)");
    private static final Pattern URL = Pattern.compile(
            "(?is)^(?:[a-z][a-z0-9+.-]*:)?(?://)?(?:[^/@\\s]*@)?"
                    + "((?:[a-z0-9-]+\\.)*(?:instagram\\.com|instagr\\.am|ddinstagram\\.com|kkinstagram\\.com"
                    + "|vxinstagram\\.com|fxinstagram\\.com|instagramez\\.com))\\.?(?::\\d+)?(/.*)?$");

    private IGUrlParser() {}

    /** @return parsed identifier, or null if this is not an Instagram media URL / ID. */
    public static Parsed parse(String input) {
        return parse(input, 0);
    }

    private static Parsed parse(String input, int depth) {
        if (input == null || depth > 3) return null;
        String s = clean(input);
        if (s.isEmpty()) return null;

        // text around the link: try each whitespace-separated token
        if (s.matches("(?s).*\\s.*")) {
            for (String token : s.split("\\s+")) {
                Parsed p = parse(token, depth + 1);
                if (p != null) return p;
            }
            return null;
        }

        if (NUMERIC_ID.matcher(s).matches()) return fromMediaId(s);
        Matcher dl = DEEP_LINK.matcher(s);
        if (dl.find()) return fromMediaId(dl.group(1));

        Matcher m = URL.matcher(s);
        if (!m.matches()) return null;
        String host = m.group(1).toLowerCase(Locale.ROOT);
        String rest = m.group(2) == null ? "" : m.group(2);

        String pathPart = rest;
        String query = "";
        int cut = firstOf(rest, '?', '#');
        if (cut >= 0) {
            pathPart = rest.substring(0, cut);
            int hash = rest.indexOf('#');
            query = rest.charAt(cut) == '?' ? rest.substring(cut + 1, hash > cut ? hash : rest.length()) : "";
        }

        // link shim: l.instagram.com/?u=<encoded url>
        if (host.equals("l.instagram.com")) {
            String u = queryParam(query, "u");
            return u == null ? null : parse(u, depth + 1);
        }
        // login wall redirect: /accounts/login/?next=/reel/CODE/
        if (pathPart.toLowerCase(Locale.ROOT).startsWith("/accounts/")) {
            String next = queryParam(query, "next");
            if (next == null) return null;
            return parse(next.startsWith("/") ? "https://www.instagram.com" + next : next, depth + 1);
        }

        List<String> seg = Arrays.stream(pathPart.split("/")).filter(x -> !x.isEmpty()).map(IGUrlParser::decode).toList();
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

    private static String clean(String input) {
        String s = input.trim();
        // strip wrapping quotes, angle brackets, markdown parentheses and trailing punctuation
        while (s.length() > 1 && "<\"'`(".indexOf(s.charAt(0)) >= 0) s = s.substring(1).trim();
        while (s.length() > 1 && ">\"'`),.;".indexOf(s.charAt(s.length() - 1)) >= 0) s = s.substring(0, s.length() - 1).trim();
        return s;
    }

    private static int firstOf(String s, char a, char b) {
        int x = s.indexOf(a), y = s.indexOf(b);
        if (x < 0) return y;
        if (y < 0) return x;
        return Math.min(x, y);
    }

    private static String queryParam(String query, String name) {
        if (query == null || query.isEmpty()) return null;
        for (String part : query.split("&")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name)) return decode(part.substring(eq + 1));
        }
        return null;
    }

    private static String decode(String s) {
        try {
            return URLDecoder.decode(s.replace("+", "%2B"), StandardCharsets.UTF_8);
        } catch (IllegalArgumentException e) {
            return s;
        }
    }

    private static Parsed fromMediaId(String id) {
        String sc = mediaIdToShortcode(id);
        return sc == null ? null : new Parsed(Kind.POST, sc);
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
}
