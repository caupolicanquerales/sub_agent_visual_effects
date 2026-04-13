package com.capo.sub_agent_visual_effects.configuration;

import java.io.IOException;
import java.nio.charset.Charset;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

@Configuration
public class AgentAnalystTranslatorConfig {
	
	@Value("classpath:prompts/system-prompt-analist.md")
    private Resource systemPromptResource;
	
	@Bean
    public String systemPromptAnalist() throws IOException {
        return systemPromptResource.getContentAsString(Charset.defaultCharset());
    }

	@Bean
    public ChatClient chatClientAnalist(ChatClient.Builder builder) {
        return builder
    		.clone()
            .build();
    }

}
