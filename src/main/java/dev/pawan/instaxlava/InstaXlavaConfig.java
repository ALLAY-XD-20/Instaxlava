package dev.pawan.instaxlava;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Bound from application.yml under plugins.instaxlava
 * Minimal setup:  plugins.instaxlava.engine: enable
 */
@Component
@ConfigurationProperties(prefix = "plugins.instaxlava")
public class InstaXlavaConfig {

    private String engine = "enable";
    private int sessionTtlMinutes = 30;
    private int urlCacheTtlSeconds = 900;
    private int maxConcurrentRequests = 4;
    private int maxRetries = 3;
    private boolean preferDashAudio = false;
    private boolean mirrorEnabled = true;
    private List<String> mirrorSearchPrefixes = new ArrayList<>(List.of("ytmsearch:", "ytsearch:"));
    private String cookies = "";
    private String proxyHost = "";
    private int proxyPort = 0;

    /** "enable" (default) turns the Instagram source on; "disable" / "off" / "false" turns it off. */
    public String getEngine() { return engine; }
    public void setEngine(String engine) { this.engine = engine; }

    public boolean isEngineEnabled() {
        String e = engine == null ? "enable" : engine.trim().toLowerCase(Locale.ROOT);
        return !(e.equals("disable") || e.equals("disabled") || e.equals("false")
                || e.equals("off") || e.equals("no") || e.equals("0"));
    }

    public int getSessionTtlMinutes() { return sessionTtlMinutes; }
    public void setSessionTtlMinutes(int v) { this.sessionTtlMinutes = Math.max(1, v); }

    public int getUrlCacheTtlSeconds() { return urlCacheTtlSeconds; }
    public void setUrlCacheTtlSeconds(int v) { this.urlCacheTtlSeconds = Math.max(0, v); }

    public int getMaxConcurrentRequests() { return maxConcurrentRequests; }
    public void setMaxConcurrentRequests(int v) { this.maxConcurrentRequests = Math.max(1, v); }

    public int getMaxRetries() { return maxRetries; }
    public void setMaxRetries(int v) { this.maxRetries = Math.max(1, v); }

    public boolean isPreferDashAudio() { return preferDashAudio; }
    public void setPreferDashAudio(boolean v) { this.preferDashAudio = v; }

    public boolean isMirrorEnabled() { return mirrorEnabled; }
    public void setMirrorEnabled(boolean v) { this.mirrorEnabled = v; }

    public List<String> getMirrorSearchPrefixes() { return mirrorSearchPrefixes; }
    public void setMirrorSearchPrefixes(List<String> v) { this.mirrorSearchPrefixes = v == null ? new ArrayList<>() : v; }

    public String getCookies() { return cookies == null ? "" : cookies; }
    public void setCookies(String cookies) { this.cookies = cookies; }

    public String getProxyHost() { return proxyHost == null ? "" : proxyHost; }
    public void setProxyHost(String proxyHost) { this.proxyHost = proxyHost; }

    public int getProxyPort() { return proxyPort; }
    public void setProxyPort(int proxyPort) { this.proxyPort = proxyPort; }

    public boolean hasProxy() { return !getProxyHost().isBlank() && proxyPort > 0; }
}
