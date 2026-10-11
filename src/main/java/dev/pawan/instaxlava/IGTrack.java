package dev.pawan.instaxlava;

import com.sedmelluq.discord.lavaplayer.container.mpeg.MpegAudioTrack;
import com.sedmelluq.discord.lavaplayer.source.AudioSourceManager;
import com.sedmelluq.discord.lavaplayer.tools.FriendlyException;
import com.sedmelluq.discord.lavaplayer.tools.io.HttpInterface;
import com.sedmelluq.discord.lavaplayer.tools.io.PersistentHttpStream;
import com.sedmelluq.discord.lavaplayer.track.AudioTrack;
import com.sedmelluq.discord.lavaplayer.track.AudioTrackInfo;
import com.sedmelluq.discord.lavaplayer.track.DelegatedAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.InternalAudioTrack;
import com.sedmelluq.discord.lavaplayer.track.playback.LocalAudioTrackExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.util.List;

/**
 * Playable Instagram track — streams the MP4/AAC audio straight from the CDN.
 *
 * Playback strategy, per attempt: every known stream URL (smallest progressive MP4, audio-only DASH,
 * other qualities) is tried in turn, and for each URL two readers:
 *  1. MP4 reader on a seekable HTTP stream (fast, no probing)
 *  2. Lavaplayer's own container detection
 * A stream that ends instantly without playing audio (for example a video-only file) counts as a failure,
 * so the next URL is tried instead of the track silently finishing.
 *
 * Stability measures:
 *  - CDN links expire, so an expired / soon-to-expire link is re-resolved before playback
 *  - failed attempts are retried with freshly resolved links and back-off
 *  - if playback dies mid-track, it re-resolves and resumes from the last position
 *  - PersistentHttpStream itself already reconnects with range requests on dropped connections
 *  - Instagram audio pages with no direct stream fall back to a mirror track on another source
 */
public class IGTrack extends DelegatedAudioTrack {

    private static final Logger log = LoggerFactory.getLogger(IGTrack.class);
    private static final long EXPIRY_SAFETY_MS = 60_000L;

    private final IGSourceManager mgr;
    private final List<String> urls;

    public IGTrack(AudioTrackInfo info, List<String> urls, IGSourceManager mgr) {
        super(info);
        this.urls = urls == null ? List.of() : List.copyOf(urls);
        this.mgr = mgr;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        int max = mgr.getConfig().getMaxRetries();
        long resumeAt = 0;
        Exception last = null;

        for (int attempt = 1; attempt <= max; attempt++) {
            List<String> candidates = pickUrls(attempt);
            if (candidates.isEmpty()) break;

            for (int ui = 0; ui < candidates.size(); ui++) {
                String url = candidates.get(ui);
                for (int mode = 0; mode < 2; mode++) {
                    String name = mode == 0 ? "mp4 reader" : "container probe";
                    long started = System.nanoTime();
                    long startPos = executor.getPosition();
                    try {
                        if (resumeAt > 0) executor.setPosition(resumeAt);
                        log.info("IG {}: opening stream {}/{} with {} (attempt {}/{})",
                                trackInfo.identifier, ui + 1, candidates.size(), name, attempt, max);
                        if (mode == 0) playMpeg(url, executor); else playProbe(url, executor);
                        if (endedTooSoon(started, startPos)) {
                            throw new IOException("stream ended immediately without audio");
                        }
                        return;
                    } catch (InterruptedException e) {
                        throw e; // track was stopped
                    } catch (Exception e) {
                        last = e;
                        if (Thread.currentThread().isInterrupted()) throw e;
                        resumeAt = Math.max(resumeAt, executor.getPosition());
                        log.warn("IG {}: {} failed on stream {}/{} -> {}: {}", trackInfo.identifier, name,
                                ui + 1, candidates.size(), e.getClass().getSimpleName(), e.getMessage());
                    }
                }
            }
            if (attempt < max) Thread.sleep(300L * attempt);
        }

        if (isAudioPage() && mgr.getConfig().isMirrorEnabled()) {
            InternalAudioTrack mirror = mgr.findMirror(trackInfo);
            if (mirror != null) {
                log.info("IG audio {} playing via mirror", trackInfo.identifier);
                processDelegate(mirror, executor);
                return;
            }
        }

        log.error("IG {}: no stream could be played", trackInfo.identifier);
        if (last instanceof FriendlyException fe) throw fe;
        throw new FriendlyException("Instagram stream unavailable: " + trackInfo.title,
                FriendlyException.Severity.COMMON, last);
    }

    private void playMpeg(String url, LocalAudioTrackExecutor executor) throws Exception {
        try (HttpInterface http = mgr.getHttpInterface();
             PersistentHttpStream stream = new PersistentHttpStream(http, new URI(url), null)) {
            processDelegate(new MpegAudioTrack(trackInfo, stream), executor);
        }
    }

    private void playProbe(String url, LocalAudioTrackExecutor executor) throws Exception {
        InternalAudioTrack probe = mgr.probeTrack(url);
        if (probe == null) {
            throw new FriendlyException("Instagram media format not supported", FriendlyException.Severity.COMMON, null);
        }
        processDelegate(probe, executor);
    }

    /** A clip of known length that "finishes" within a second of starting from 0 never produced audio. */
    private boolean endedTooSoon(long startedNanos, long startPosition) {
        long elapsedMs = (System.nanoTime() - startedNanos) / 1_000_000L;
        return elapsedMs < 1000 && startPosition == 0
                && MediaInfo.knownDuration(trackInfo.length) && trackInfo.length > 10_000;
    }

    private boolean isAudioPage() {
        IGUrlParser.Parsed p = IGUrlParser.parse(trackInfo.uri);
        return p != null && p.kind() == IGUrlParser.Kind.AUDIO;
    }

    private List<String> pickUrls(int attempt) {
        if (attempt == 1 && !urls.isEmpty()) {
            long exp = MediaInfo.cdnExpiryMillis(urls.get(0));
            if (exp < 0 || exp - System.currentTimeMillis() > EXPIRY_SAFETY_MS) return urls;
        }
        MediaInfo fresh = mgr.resolveUri(trackInfo.uri, attempt > 1 || !urls.isEmpty());
        return fresh != null ? fresh.allUrls() : List.of();
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new IGTrack(trackInfo, urls, mgr);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return mgr;
    }
}
