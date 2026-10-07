package dev.pawan.instaxlava;

import com.sedmelluq.discord.lavaplayer.player.AudioLoadResultHandler;
import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.Units;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpClientTools;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterfaceManager;
import com.sedmelluq.discord.lavaplayer.track.AudioItem;
import com.sedmelluq.discord.lavaplayer.track.AudioPlaylist;
import com.sedmelluq.discord.lavaplayer.track.AudioReference;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Lavalink AudioSourceManager for Instagram posts, reels, IGTV, share links, media IDs and audio pages. */
public class IGSourceManager implements AudioSourceManager {

    private static final Logger log = LoggerFactory.getLogger(IGSourceManager.class);
    private static final int MAX_MIRROR_SEARCHES = 6;

    private final InstaXlavaConfig config;
    private final IGMediaResolver resolver;
    private final HttpInterfaceManager httpManager;
    private volatile AudioPlayerManager playerManager;

    public IGSourceManager(InstaXlavaConfig config) {
        this.config = config;
        this.resolver = new IGMediaResolver(config);
        this.httpManager = HttpClientTools.createDefaultThreadLocalManager();
        httpManager.configureBuilder(b -> {
            b.setUserAgent(IGMediaResolver.UA);
            if (config.hasProxy()) b.setProxy(new HttpHost(config.getProxyHost(), config.getProxyPort()));
        });
        httpManager.configureRequests(rc -> RequestConfig.copy(rc)
                .setConnectTimeout(10_000)
                .setConnectionRequestTimeout(10_000)
                .setSocketTimeout(20_000)
                .build());
    }

    public void setPlayerManager(AudioPlayerManager manager) {
        this.playerManager = manager;
    }

    @Override
    public String getSourceName() {
        return "instagram";
    }

    public InstaXlavaConfig getConfig() {
        return config;
    }

    public HttpInterface getHttpInterface() {
        return httpManager.getInterface();
    }

    /** Re-resolve a stored track URI to a fresh stream (used by IGTrack when the CDN link is stale). */
    public MediaInfo resolveUri(String uri, boolean force) {
        IGUrlParser.Parsed p = IGUrlParser.parse(uri);
        if (p == null) return null;
        if (p.kind() == IGUrlParser.Kind.SHARE) {
            p = resolver.expandShare(p.id());
            if (p == null) return null;
        }
        return resolver.resolve(p, force);
    }

    @Override
    public AudioItem loadItem(AudioPlayerManager manager, AudioReference ref) {
        IGUrlParser.Parsed p = IGUrlParser.parse(ref.identifier);
        if (p == null) return null; // not ours, let other sources try
        try {
            if (p.kind() == IGUrlParser.Kind.SHARE) {
                p = resolver.expandShare(p.id());
                if (p == null) return AudioReference.NO_TRACK;
            }
            MediaInfo mi = resolver.resolve(p, false);
            if (mi != null && mi.streamUrl() != null) {
                long len = mi.durationMs() > 0 ? mi.durationMs() : Units.DURATION_MS_UNKNOWN;
                AudioTrackInfo info = new AudioTrackInfo(mi.title(), mi.author(), len, p.id(), false,
                        p.canonicalUrl(), emptyToNull(mi.artworkUrl()), null);
                return new IGTrack(info, mi.streamUrl(), this);
            }

            // Audio page with no direct stream: keep the metadata and play a mirror from another source.
            if (p.kind() == IGUrlParser.Kind.AUDIO && config.isMirrorEnabled()) {
                IGMediaResolver.AudioOg og = resolver.resolveAudioOg(p.id());
                if (og != null) {
                    AudioTrackInfo info = new AudioTrackInfo(og.title(), og.author(), Units.DURATION_MS_UNKNOWN,
                            p.id(), false, p.canonicalUrl(), emptyToNull(og.artworkUrl()), null);
                    return new IGTrack(info, null, this);
                }
            }
            return AudioReference.NO_TRACK;
        } catch (Exception e) {
            log.warn("Instagram load failed for {}: {}", ref.identifier, e.getMessage());
            throw new FriendlyException("Instagram load failed", FriendlyException.Severity.SUSPICIOUS, e);
        }
    }

    /**
     * Finds a playable equivalent of an Instagram audio on another source (e.g. YouTube) by searching
     * with the configured prefixes and keeping only candidates that match the title / author tokens.
     */
    public InternalAudioTrack findMirror(AudioTrackInfo info) {
        AudioPlayerManager pm = playerManager;
        if (pm == null || !config.isMirrorEnabled() || config.getMirrorSearchPrefixes().isEmpty()) return null;

        String title = info.title;
        String author = info.author;
        String preferred = null;

        boolean weak = title == null || title.isBlank() || title.equals("Instagram Audio")
                || IGMirror.unknownAuthor(author);
        if (weak) {
            IGUrlParser.Parsed p = IGUrlParser.parse(info.uri);
            if (p != null && p.kind() == IGUrlParser.Kind.AUDIO) {
                IGMediaResolver.AudioOg og = resolver.resolveAudioOg(p.id());
                if (og != null) {
                    title = og.title();
                    author = og.author();
                    preferred = og.searchQuery();
                }
            }
        }

        String t = title == null ? "" : title;
        String a = IGMirror.unknownAuthor(author) ? "" : author;
        Set<String> queries = new LinkedHashSet<>();
        if (preferred != null) queries.add(preferred);
        queries.add((a + " - " + t).trim());
        queries.add(("\"" + t + "\" " + a).trim());
        queries.add((t + " " + a).trim());
        queries.add((a + " " + t).trim());
        queries.add(t);
        queries.add(a);
        queries.removeIf(q -> q.isBlank() || q.equals("-") || q.equals("\"\""));

        int budget = MAX_MIRROR_SEARCHES;
        for (String q : queries) {
            for (String prefix : config.getMirrorSearchPrefixes()) {
                if (budget-- <= 0) return null;
                List<AudioTrack> results = search(pm, prefix + q);
                AudioTrack best = null;
                for (AudioTrack c : results) {
                    if (!IGMirror.acceptable(t, a, c.getInfo().title, c.getInfo().author)) continue;
                    if (best == null || closerToLength(c, best, info.length)) best = c;
                }
                if (best != null) {
                    AudioTrack clone = best.makeClone();
                    if (clone instanceof InternalAudioTrack internal) return internal;
                }
            }
        }
        return null;
    }

    private static boolean closerToLength(AudioTrack cand, AudioTrack best, long target) {
        if (target <= 0 || target == Units.DURATION_MS_UNKNOWN) return false; // keep first (search rank)
        return Math.abs(cand.getDuration() - target) < Math.abs(best.getDuration() - target);
    }

    private List<AudioTrack> search(AudioPlayerManager pm, String identifier) {
        CompletableFuture<List<AudioTrack>> future = new CompletableFuture<>();
        pm.loadItem(identifier, new AudioLoadResultHandler() {
            @Override public void trackLoaded(AudioTrack track) { future.complete(List.of(track)); }
            @Override public void playlistLoaded(AudioPlaylist playlist) { future.complete(new ArrayList<>(playlist.getTracks())); }
            @Override public void noMatches() { future.complete(List.of()); }
            @Override public void loadFailed(FriendlyException exception) { future.complete(List.of()); }
        });
        try {
            return future.get(10, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (Exception e) {
            log.debug("IG mirror search '{}' failed: {}", identifier, e.getMessage());
            return List.of();
        }
    }

    private static String emptyToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    @Override
    public boolean isTrackEncodable(AudioTrack track) {
        return true;
    }

    @Override
    public void encodeTrack(AudioTrack track, DataOutput output) {
        // nothing extra: the canonical URI is stored in AudioTrackInfo and re-resolved on demand
    }

    @Override
    public AudioTrack decodeTrack(AudioTrackInfo trackInfo, DataInput input) {
        return new IGTrack(trackInfo, null, this);
    }

    @Override
    public void shutdown() {
        try {
            httpManager.close();
        } catch (IOException e) {
            log.error("Failed closing IG http manager", e);
        }
    }
}
