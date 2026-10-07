package dev.pawan.instaxlava;

import com.sedmelluq.discord.lavaplayer.player.AudioPlayerManager;
import dev.arbjerg.lavalink.api.AudioPlayerManagerConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * InstaXlava — Instagram source for Lavalink v4.
 * Author: Pawan
 *
 * Enable with:  plugins.instaxlava.engine: enable
 */
@Service
public class InstaXlavaPlugin implements AudioPlayerManagerConfiguration {

    private static final Logger log = LoggerFactory.getLogger(InstaXlavaPlugin.class);

    private final InstaXlavaConfig config;

    public InstaXlavaPlugin(InstaXlavaConfig config) {
        this.config = config;
    }

    @Override
    public AudioPlayerManager configure(AudioPlayerManager manager) {
        if (!config.isEngineEnabled()) {
            log.info("InstaXlava engine: disable — Instagram source not registered");
            return manager;
        }
        IGSourceManager source = new IGSourceManager(config);
        source.setPlayerManager(manager);
        manager.registerSourceManager(source);
        log.info("InstaXlava engine: enable — Instagram source registered");
        return manager;
    }
}
