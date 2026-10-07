package dev.pawan.instaxlava;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.net.CookieManager;
import java.net.InetSocketAddress;
import java.net.ProxySelector;
import java.net.URI;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Instagram media resolver.
 *
 * Posts / reels / IGTV, tried in order:
 *   1. logged-out clips query (no session needed, currently the most reliable)
 *   2. session GraphQL query
 *   3. public embed page
 * Audio pages: clips music API (with paging-cursor retry), then OG metadata (used for mirror search).
 *
 * NOTE: unofficial endpoints, they can change without notice.
 */
public class IGMediaResolver {

    private static final Logger log = LoggerFactory.getLogger(IGMediaResolver.class);
    private static final ObjectMapper JSON = new ObjectMapper();
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36";
    private static final String DEFAULT_DOC_ID = "10015901848480474";
    private static final String CLIPS_DOC_ID = "27695069083459414";
    private static final long EXPIRY_SAFETY_MS = 60_000L;

    private record Session(String csrf, String appId, String lsd, String docId, long createdAt) {}
    private record CacheEntry(MediaInfo info, long expiresAt) {}

    /** Metadata scraped from an audio page's OG tags (no stream URL). */
    public record AudioOg(String title, String author, String artworkUrl, String searchQuery) {}

    private final InstaXlavaConfig cfg;
    private final HttpClient http;
    private final CookieManager cookies = new CookieManager();
    private final Semaphore gate;
    private final Object sessionLock = new Object();
    private final Map<String, CacheEntry> cache = new ConcurrentHashMap<>();
    private volatile Session session;
    private volatile long lastAcquireFailure = 0;

    public IGMediaResolver(InstaXlavaConfig cfg) {
        this.cfg = cfg;
        this.gate = new Semaphore(cfg.getMaxConcurrentRequests());
        HttpClient.Builder b = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(12))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .cookieHandler(cookies);
        if (cfg.hasProxy()) {
            b.proxy(ProxySelector.of(new InetSocketAddress(cfg.getProxyHost(), cfg.getProxyPort())));
        }
        this.http = b.build();
    }

    // ── public API ──────────────────────────────────────────────────────────

    /** Resolve a parsed (non-share) identifier to a playable stream. Null if nothing playable was found. */
    public MediaInfo resolve(IGUrlParser.Parsed p, boolean forceRefresh) {
        String key = p.kind() + ":" + p.id();
        long now = System.currentTimeMillis();
        if (!forceRefresh) {
            CacheEntry e = cache.get(key);
            if (e != null && e.expiresAt() > now) return e.info();
        }
        MediaInfo info = switch (p.kind()) {
            case AUDIO -> resolveAudio(p.id());
            case SHARE -> null;
            default -> resolvePost(p.id(), p.segment());
        };
        if (info == null) {
            cache.remove(key);
            return null;
        }
        long ttlEnd = now + cfg.getUrlCacheTtlSeconds() * 1000L;
        long cdnExp = info.expiresAtMillis();
        if (cdnExp > 0) ttlEnd = Math.min(ttlEnd, cdnExp - EXPIRY_SAFETY_MS);
        if (ttlEnd > now) {
            if (cache.size() > 1000) {
                cache.entrySet().removeIf(en -> en.getValue().expiresAt() <= System.currentTimeMillis());
                if (cache.size() > 1000) cache.clear();
            }
            cache.put(key, new CacheEntry(info, ttlEnd));
        }
        return info;
    }

    /** Follow an instagram.com/share/... redirect and return the real post/reel/audio target. */
    public IGUrlParser.Parsed expandShare(String shareUrl) {
        try {
            HttpRequest req = base(shareUrl).header("Accept", "text/html").timeout(Duration.ofSeconds(10)).GET().build();
            gate.acquire();
            HttpResponse<Void> resp;
            try {
                resp = http.send(req, HttpResponse.BodyHandlers.discarding());
            } finally {
                gate.release();
            }
            URI finalUri = resp.uri();
            IGUrlParser.Parsed p = IGUrlParser.parse(finalUri.toString());
            if (p != null && p.kind() != IGUrlParser.Kind.SHARE) return p;
            // logged-out redirect: /accounts/login/?next=/reel/CODE/
            String q = finalUri.getRawQuery();
            if (q != null) {
                for (String part : q.split("&")) {
                    if (part.startsWith("next=")) {
                        String next = URLDecoder.decode(part.substring(5), StandardCharsets.UTF_8);
                        IGUrlParser.Parsed n = IGUrlParser.parse("https://www.instagram.com" + next);
                        if (n != null && n.kind() != IGUrlParser.Kind.SHARE) return n;
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            log.debug("IG share expand failed: {}", e.getMessage());
        }
        return null;
    }

    /** Title/author/artwork from the audio page's OG tags. Used when the audio API gives no stream. */
    public AudioOg resolveAudioOg(String audioId) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create("https://www.instagram.com/reels/audio/" + audioId + "/"))
                    .header("User-Agent", "facebookexternalhit/1.1")
                    .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> resp = send(req);
            if (resp.statusCode() != 200 || resp.body() == null) return null;
            String html = resp.body();
            String ogTitle = meta(html, "og:title");
            String ogDesc = meta(html, "og:description");
            String ogImage = meta(html, "og:image");
            if (ogTitle == null && ogDesc == null) return null;

            String nt = (ogTitle == null ? "" : ogTitle).replaceAll("(?i)\\s+on Instagram$", "").trim();
            String author = null, title = null;
            if (nt.contains(" | ")) {
                String[] parts = nt.split(" \\| ", 2);
                author = blankToNull(parts[0]);
                title = blankToNull(parts[1]);
            }
            if ((author == null || title == null) && ogDesc != null) {
                Matcher m = Pattern.compile("Listen to (.+?) on Instagram and watch reels using (.+?) audio",
                        Pattern.CASE_INSENSITIVE).matcher(ogDesc);
                if (m.find()) {
                    if (author == null) author = blankToNull(m.group(1));
                    if (title == null) title = blankToNull(m.group(2));
                }
            }
            String finalTitle = title != null ? title : (!nt.isEmpty() ? nt : "Instagram Audio");
            StringBuilder q = new StringBuilder();
            if (author != null) q.append(author).append(' ');
            if (title != null) q.append(title);
            String query = q.toString().trim();
            if (query.isEmpty()) query = finalTitle;
            return new AudioOg(finalTitle, author != null ? author : "Unknown", ogImage == null ? "" : ogImage, query);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("IG audio OG fetch failed: {}", e.getMessage());
            return null;
        }
    }

    // ── session management ──────────────────────────────────────────────────

    private Session ensureSession() {
        long ttl = cfg.getSessionTtlMinutes() * 60_000L;
        Session s = session;
        if (s != null && System.currentTimeMillis() - s.createdAt() < ttl) return s;
        synchronized (sessionLock) {
            s = session;
            if (s != null && System.currentTimeMillis() - s.createdAt() < ttl) return s;
            if (System.currentTimeMillis() - lastAcquireFailure < 5_000L) return null; // brief cool-down
            s = acquireSession();
            session = s;
            if (s == null) lastAcquireFailure = System.currentTimeMillis();
            return s;
        }
    }

    private void invalidate(Session bad) {
        synchronized (sessionLock) {
            if (session == bad) {
                session = null;
                cookies.getCookieStore().removeAll();
            }
        }
    }

    private Session acquireSession() {
        try {
            HttpRequest req = base("https://www.instagram.com/").header("Accept", "text/html")
                    .timeout(Duration.ofSeconds(10)).GET().build();
            HttpResponse<String> resp = send(req);
            if (resp.statusCode() != 200 || resp.body() == null) {
                log.warn("IG homepage returned HTTP {}", resp.statusCode());
                return null;
            }
            String html = resp.body();
            String csrf = grab("\"csrf_token\":\"(.*?)\"", html);
            String appId = grab("\"appId\":\"(.*?)\"", html);
            String lsd = grab("\"LSD\",\\[],\\{\"token\":\"(.*?)\"\\},", html);
            if (lsd == null) lsd = grab("name=\"lsd\" value=\"(.*?)\"", html);
            String docId = grab("\"PostPage\",\\[],\"(\\d+)\",", html);
            if (docId == null) docId = DEFAULT_DOC_ID;
            if (csrf == null || appId == null || lsd == null) {
                log.error("IG session scrape incomplete — csrf={} appId={} lsd={}", csrf != null, appId != null, lsd != null);
                return null;
            }
            log.info("IG session tokens acquired");
            return new Session(csrf, appId, lsd, docId, System.currentTimeMillis());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.error("IG session init error: {}", e.getMessage());
            return null;
        }
    }

    // ── resolution flows ────────────────────────────────────────────────────

    private MediaInfo resolvePost(String code, String segment) {
        MediaInfo m = queryClips(code);
        if (m != null) return m;

        Session s = ensureSession();
        m = s == null ? null : queryGraphQL(s, code, segment);
        if (m == null && s != null) {
            invalidate(s);
            s = ensureSession();
            if (s != null) m = queryGraphQL(s, code, segment);
        }
        if (m == null) m = queryEmbed(code);
        return m;
    }

    private MediaInfo resolveAudio(String audioId) {
        Session s = ensureSession();
        MediaInfo m = s == null ? null : queryAudio(s, audioId);
        if (m == null && s != null) {
            invalidate(s);
            s = ensureSession();
            if (s != null) m = queryAudio(s, audioId);
        }
        return m;
    }

    /** Logged-out clips query: shortcode to media id, then pick our media out of the returned chain. */
    private MediaInfo queryClips(String code) {
        try {
            BigInteger mediaId = IGUrlParser.shortcodeToMediaId(code);
            if (mediaId == null) return null;

            ObjectNode vars = JSON.createObjectNode();
            ObjectNode data = vars.putObject("data");
            data.put("chaining_mode", "same_author");
            data.put("clips_media_id", mediaId.toString());
            vars.put("__relay_internal__pv__PolarisReelsRecoDebugOverlayEnabledrelayprovider", false);
            vars.put("__relay_internal__pv__PolarisShortDramaEnabledrelayprovider", false);

            String url = "https://www.instagram.com/graphql/query/?doc_id=" + CLIPS_DOC_ID
                    + "&variables=" + URLEncoder.encode(vars.toString(), StandardCharsets.UTF_8);
            HttpRequest req = base(url).header("Accept", "*/*").timeout(Duration.ofSeconds(15)).GET().build();
            HttpResponse<String> resp = send(req);
            if (resp.statusCode() != 200 || resp.body() == null) return null;

            JsonNode edges = JSON.readTree(resp.body())
                    .at("/data/xdt_api__v1__clips__clips_on_logged_out_connection_v2/edges");
            JsonNode media = null;
            for (JsonNode edge : edges) {
                JsonNode cand = edge.at("/node/media");
                if (code.equals(cand.path("code").asText(""))) {
                    media = cand;
                    break;
                }
            }
            if (media == null || media.path("media_type").asInt(0) != 2) return null;

            String videoUrl = null;
            for (JsonNode v : media.path("video_versions")) {
                String u = txt(v.path("url"));
                if (u != null) { videoUrl = u; break; }
            }
            if (videoUrl == null) return null;
            if (cfg.isPreferDashAudio()) {
                String dash = dashAudioUrl(media.path("video_dash_manifest").asText(""));
                if (dash != null) videoUrl = dash;
            }

            return new MediaInfo(videoUrl, cleanTitle(txt(media.at("/caption/text"))),
                    first(txt(media.at("/user/username")), "Unknown"),
                    dashDurationMs(media.path("video_dash_manifest").asText("")),
                    first(txt(media.at("/image_versions2/candidates/0/url")), ""));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("IG clips query failed for {}: {}", code, e.getMessage());
            return null;
        }
    }

    private MediaInfo queryGraphQL(Session s, String shortcode, String segment) {
        try {
            ObjectNode vars = JSON.createObjectNode();
            vars.put("shortcode", shortcode);
            vars.put("fetch_comment_count", "null");
            vars.put("fetch_related_profile_media_count", "null");
            vars.put("parent_comment_count", "null");
            vars.put("child_comment_count", "null");
            vars.put("fetch_like_count", "null");
            vars.put("fetch_tagged_user_count", "null");
            vars.put("fetch_preview_comment_count", "null");
            vars.put("has_threaded_comments", "false");
            vars.put("hoisted_comment_id", "null");
            vars.put("hoisted_reply_id", "null");

            String payload = form("av", "0", "__user", "0", "__a", "1", "__req", "3", "dpr", "1",
                    "__ccg", "UNKNOWN", "lsd", s.lsd(), "jazoest", "2957", "doc_id", s.docId(),
                    "variables", vars.toString(), "fb_api_req_friendly_name", "PolarisPostActionLoadPostQueryQuery",
                    "fb_api_caller_class", "RelayModern");

            HttpRequest req = base("https://www.instagram.com/api/graphql")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-CSRFToken", s.csrf()).header("X-IG-App-ID", s.appId())
                    .header("X-FB-LSD", s.lsd()).header("X-FB-Friendly-Name", "PolarisPostActionLoadPostQueryQuery")
                    .header("X-ASBD-ID", "129477")
                    .header("Origin", "https://www.instagram.com")
                    .header("Referer", "https://www.instagram.com/" + segment + "/" + shortcode + "/")
                    .header("Sec-Fetch-Site", "same-origin").timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<String> resp = send(req);
            if (resp.statusCode() != 200 || resp.body() == null) {
                log.debug("IG GQL HTTP {}", resp.statusCode());
                return null;
            }
            JsonNode media = JSON.readTree(resp.body()).at("/data/xdt_shortcode_media");
            if (media.isMissingNode() || media.isNull()) return null;
            JsonNode vid = pickVideo(media);
            if (vid == null) return null;

            String url = vid.path("video_url").asText(null);
            if (cfg.isPreferDashAudio()) {
                String dash = dashAudioUrl(vid.path("video_dash_manifest").asText(""));
                if (dash != null) url = dash;
            }
            if (url == null || url.isBlank()) return null;

            long dur = (long) (vid.path("video_duration").asDouble(0) * 1000);
            if (dur <= 0) dur = dashDurationMs(vid.path("video_dash_manifest").asText(""));

            return new MediaInfo(url, cleanTitle(txt(media.at("/edge_media_to_caption/edges/0/node/text"))),
                    media.at("/owner/username").asText("Unknown"), dur,
                    vid.path("display_url").asText(media.path("display_url").asText("")));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("IG GQL query failed: {}", e.getMessage());
            return null;
        }
    }

    /** Fallback: public embed page, no session needed. */
    private MediaInfo queryEmbed(String code) {
        try {
            HttpRequest req = base("https://www.instagram.com/p/" + code + "/embed/captioned/")
                    .header("Accept", "text/html").timeout(Duration.ofSeconds(12)).GET().build();
            HttpResponse<String> resp = send(req);
            if (resp.statusCode() != 200 || resp.body() == null) return null;
            String body = resp.body().replace("\\\"", "\"");
            String raw = grab("\"video_url\":\"((?:[^\"\\\\]|\\\\.)*)\"", body);
            if (raw == null) return null;
            String url = raw;
            for (int i = 0; i < 2 && url.contains("\\"); i++) url = jsonUnescape(url);
            if (!url.startsWith("http")) return null;
            String author = grab("\"username\":\"([^\"]+)\"", body);
            return new MediaInfo(url, "Instagram Video", author != null ? author : "Unknown",
                    Units.DURATION_MS_UNKNOWN, "");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("IG embed fallback failed: {}", e.getMessage());
            return null;
        }
    }

    // ── audio ───────────────────────────────────────────────────────────────

    private MediaInfo queryAudio(Session s, String audioId) {
        JsonNode root = postClipsMusic(s, audioId, null);
        if (root == null) return null;
        MediaInfo m = parseClipsMusic(root);
        if (m == null) {
            // first page can be empty; the paging cursor yields valid items
            JsonNode payload = root.has("payload") ? root.get("payload") : root;
            String maxId = txt(payload.at("/paging_info/max_id"));
            if (maxId == null) maxId = txt(root.at("/paging_info/max_id"));
            if (maxId != null) {
                JsonNode second = postClipsMusic(s, audioId, maxId);
                if (second != null) m = parseClipsMusic(second);
            }
        }
        return m;
    }

    private JsonNode postClipsMusic(Session s, String audioId, String maxId) {
        try {
            String payload = maxId == null
                    ? form("audio_cluster_id", audioId, "lsd", s.lsd(), "jazoest", "2957", "__user", "0", "__a", "1")
                    : form("audio_cluster_id", audioId, "lsd", s.lsd(), "jazoest", "2957", "__user", "0", "__a", "1",
                    "max_id", maxId, "original_sound_audio_asset_id", audioId);
            HttpRequest req = base("https://www.instagram.com/api/v1/clips/music/")
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("X-CSRFToken", s.csrf()).header("X-IG-App-ID", s.appId())
                    .header("X-FB-LSD", s.lsd()).header("X-FB-Friendly-Name", "PolarisClipsAudioRoute")
                    .header("X-ASBD-ID", "129477")
                    .header("Origin", "https://www.instagram.com")
                    .header("Referer", "https://www.instagram.com/reels/audio/" + audioId + "/")
                    .header("Sec-Fetch-Site", "same-origin").timeout(Duration.ofSeconds(15))
                    .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
            HttpResponse<String> resp = send(req);
            if (resp.statusCode() != 200 || resp.body() == null) return null;
            String body = resp.body();
            if (body.startsWith("for (;;);")) body = body.substring(9);
            return JSON.readTree(body);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return null;
        } catch (Exception e) {
            log.debug("IG audio query failed: {}", e.getMessage());
            return null;
        }
    }

    private MediaInfo parseClipsMusic(JsonNode root) {
        JsonNode payload = root.has("payload") ? root.get("payload") : root;
        JsonNode metadata = payload.path("metadata");

        JsonNode orig = metadata.path("original_sound_info");
        if (present(orig)) {
            String url = txt(orig.path("progressive_download_url"));
            if (url == null) return null;
            return new MediaInfo(url, first(txt(orig.path("original_audio_title")), "Instagram Audio"),
                    first(txt(orig.at("/ig_artist/username")), "Unknown"), orig.path("duration_in_ms").asLong(0),
                    first(txt(orig.at("/ig_artist/profile_pic_url")), ""));
        }

        JsonNode music = metadata.path("music_info");
        if (!present(music)) music = payload.at("/items/0/media/clips_metadata/music_info");
        if (!present(music)) return null;

        JsonNode asset = music.path("music_asset_info");
        String url = first(txt(asset.path("fast_start_progressive_download_url")),
                txt(asset.path("progressive_download_url")), txt(music.path("progressive_download_url")));
        if (url == null) {
            String mpd = music.at("/music_consumption_info/dash_manifest").asText("");
            Matcher m = Pattern.compile("<BaseURL>(.*?)</BaseURL>").matcher(mpd);
            if (m.find()) url = m.group(1).replace("&amp;", "&");
        }
        if (url == null) return null;
        return new MediaInfo(url, first(txt(asset.path("title")), "Instagram Audio"),
                first(txt(asset.path("display_artist")), txt(asset.path("artist_name")), "Unknown"),
                asset.path("duration_in_ms").asLong(0),
                first(txt(asset.path("cover_artwork_uri")), txt(asset.path("cover_artwork_thumbnail_uri")),
                        txt(music.path("cover_artwork_thumbnail_uri")), ""));
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private HttpRequest.Builder base(String url) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url))
                .header("User-Agent", UA)
                .header("Accept-Language", "en-US,en;q=0.9");
        String c = cfg.getCookies();
        if (!c.isBlank()) b.header("Cookie", c);
        return b;
    }

    private HttpResponse<String> send(HttpRequest req) throws java.io.IOException, InterruptedException {
        gate.acquire();
        try {
            return http.send(req, HttpResponse.BodyHandlers.ofString());
        } finally {
            gate.release();
        }
    }

    private JsonNode pickVideo(JsonNode media) {
        if (media.path("is_video").asBoolean(false)) return media;
        if ("XDTGraphSidecar".equals(media.path("__typename").asText(""))) {
            for (JsonNode edge : media.at("/edge_sidecar_to_children/edges"))
                if (edge.at("/node/is_video").asBoolean(false)) return edge.path("node");
        }
        return null;
    }

    private static boolean present(JsonNode n) {
        return n != null && !n.isMissingNode() && !n.isNull();
    }

    private static String txt(JsonNode n) {
        if (!present(n)) return null;
        String s = n.asText();
        return s == null || s.isBlank() ? null : s;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static String first(String... v) {
        for (String s : v) if (s != null) return s;
        return null;
    }

    private static String cleanTitle(String caption) {
        if (caption == null) return "Instagram Video";
        String c = caption.strip();
        int nl = c.indexOf('\n');
        if (nl > 0) c = c.substring(0, nl).strip();
        if (c.isEmpty()) return "Instagram Video";
        return c.length() > 100 ? c.substring(0, 97) + "..." : c;
    }

    private static String dashAudioUrl(String mpd) {
        if (mpd == null || mpd.isEmpty()) return null;
        Matcher m = Pattern.compile("<AdaptationSet[^>]*contentType=\"audio\".*?<BaseURL>(.*?)</BaseURL>", Pattern.DOTALL).matcher(mpd);
        return m.find() ? m.group(1).replace("&amp;", "&") : null;
    }

    private static long dashDurationMs(String mpd) {
        if (mpd == null || mpd.isEmpty()) return 0;
        Matcher m = Pattern.compile("mediaPresentationDuration=\"PT(?:(\\d+)H)?(?:(\\d+)M)?([\\d.]+)S\"").matcher(mpd);
        if (!m.find()) return 0;
        try {
            double h = m.group(1) == null ? 0 : Double.parseDouble(m.group(1));
            double mn = m.group(2) == null ? 0 : Double.parseDouble(m.group(2));
            double sec = Double.parseDouble(m.group(3));
            return Math.round(((h * 60 + mn) * 60 + sec) * 1000);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String meta(String html, String prop) {
        String q = Pattern.quote(prop);
        Pattern p1 = Pattern.compile("<meta[^>]+property=[\"']" + q + "[\"'][^>]*?content=(?:\"([^\"]*)\"|'([^']*)')", Pattern.CASE_INSENSITIVE);
        Pattern p2 = Pattern.compile("<meta[^>]+content=(?:\"([^\"]*)\"|'([^']*)')[^>]*?property=[\"']" + q + "[\"']", Pattern.CASE_INSENSITIVE);
        for (Pattern p : new Pattern[]{p1, p2}) {
            Matcher m = p.matcher(html);
            if (m.find()) {
                String v = m.group(1) != null ? m.group(1) : m.group(2);
                if (v != null && !v.isBlank()) return htmlDecode(v);
            }
        }
        return null;
    }

    private static String htmlDecode(String s) {
        s = replaceCodePoints(s, Pattern.compile("&#x([0-9a-fA-F]+);"), 16);
        s = replaceCodePoints(s, Pattern.compile("&#(\\d+);"), 10);
        return s.replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'")
                .replace("&lt;", "<").replace("&gt;", ">").replace("&nbsp;", " ")
                .replace("&amp;", "&").trim();
    }

    private static String replaceCodePoints(String s, Pattern p, int radix) {
        Matcher m = p.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String rep;
            try {
                rep = new String(Character.toChars(Integer.parseInt(m.group(1), radix)));
            } catch (Exception e) {
                rep = m.group();
            }
            m.appendReplacement(sb, Matcher.quoteReplacement(rep));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String jsonUnescape(String s) {
        try {
            return JSON.readValue("\"" + s + "\"", String.class);
        } catch (Exception e) {
            return s.replace("\\/", "/").replace("\\u0026", "&");
        }
    }

    private static String grab(String regex, String text) {
        Matcher m = Pattern.compile(regex).matcher(text);
        return m.find() ? m.group(1) : null;
    }

    private static String form(String... kv) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < kv.length; i += 2) {
            if (sb.length() > 0) sb.append('&');
            sb.append(URLEncoder.encode(kv[i], StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(kv[i + 1], StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
