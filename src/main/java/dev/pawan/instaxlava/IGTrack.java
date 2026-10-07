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

import java.net.URI;

/**
 * Playable Instagram track — streams the MP4/AAC audio straight from the CDN.
 *
 * Stability measures:
 *  - CDN links expire, so an expired / soon-to-expire link is re-resolved before playback
 *  - startup failures (403/410, stale link, network blip) are retried with a fresh link and back-off
 *  - if playback dies mid-track, it re-resolves and resumes from the last position
 *  - PersistentHttpStream itself already reconnects with range requests on dropped connections
 *  - Instagram audio pages with no direct stream fall back to a mirror track on another source
 */
public class IGTrack extends DelegatedAudioTrack {

    private static final Logger log = LoggerFactory.getLogger(IGTrack.class);
    private static final long EXPIRY_SAFETY_MS = 60_000L;

    private final IGSourceManager mgr;
    private final String cdnUrl;

    public IGTrack(AudioTrackInfo info, String cdnUrl, IGSourceManager mgr) {
        super(info);
        this.cdnUrl = cdnUrl;
        this.mgr = mgr;
    }

    @Override
    public void process(LocalAudioTrackExecutor executor) throws Exception {
        int max = mgr.getConfig().getMaxRetries();
        long resumeAt = 0;
        Exception last = null;

        for (int attempt = 1; attempt <= max; attempt++) {
            String url = pickUrl(attempt);
            if (url == null) break;

            try (HttpInterface http = mgr.getHttpInterface();
                 PersistentHttpStream stream = new PersistentHttpStream(http, new URI(url), null)) {
                if (resumeAt > 0) executor.setPosition(resumeAt);
                processDelegate(new MpegAudioTrack(trackInfo, stream), executor);
                return;
            } catch (InterruptedException e) {
                throw e; // track was stopped
            } catch (Exception e) {
                last = e;
                if (Thread.currentThread().isInterrupted()) throw e;
                resumeAt = Math.max(resumeAt, executor.getPosition());
                log.warn("IG playback attempt {}/{} failed for {}: {}", attempt, max, trackInfo.identifier, e.getMessage());
                if (attempt < max) Thread.sleep(300L * attempt);
            }
        }

        if (isAudioPage() && mgr.getConfig().isMirrorEnabled()) {
            InternalAudioTrack mirror = mgr.findMirror(trackInfo);
            if (mirror != null) {
                log.info("IG audio {} playing via mirror", trackInfo.identifier);
                processDelegate(mirror, executor);
                return;
            }
        }

        if (last instanceof FriendlyException fe) throw fe;
        throw new FriendlyException("Instagram stream unavailable: " + trackInfo.title,
                FriendlyException.Severity.COMMON, last);
    }

    private boolean isAudioPage() {
        IGUrlParser.Parsed p = IGUrlParser.parse(trackInfo.uri);
        return p != null && p.kind() == IGUrlParser.Kind.AUDIO;
    }

    private String pickUrl(int attempt) {
        if (attempt == 1 && cdnUrl != null && !cdnUrl.isEmpty()) {
            long exp = MediaInfo.cdnExpiryMillis(cdnUrl);
            if (exp < 0 || exp - System.currentTimeMillis() > EXPIRY_SAFETY_MS) return cdnUrl;
        }
        MediaInfo fresh = mgr.resolveUri(trackInfo.uri, attempt > 1 || cdnUrl != null);
        return fresh != null ? fresh.streamUrl() : null;
    }

    @Override
    protected AudioTrack makeShallowClone() {
        return new IGTrack(trackInfo, cdnUrl, mgr);
    }

    @Override
    public AudioSourceManager getSourceManager() {
        return mgr;
    }
}
