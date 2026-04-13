package com.capo.sub_agent_visual_effects.configuration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PostConstruct;
import nu.pattern.OpenCV;

@Configuration
public class OpenCVConfig {
	private static final Logger log = LoggerFactory.getLogger(OpenCVConfig.class);

    @PostConstruct
    public void init() {
        try {
            OpenCV.loadLocally();
            log.info("OpenCV native library loaded successfully.");
        } catch (Exception e) {
            log.error("Failed to load OpenCV: ", e);
        }
    }
}
