package com.capo.sub_agent_visual_effects.configuration;

import java.io.IOException;
import java.nio.charset.Charset;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import com.capo.sub_agent_visual_effects.services.ActionWithOpenCVTools;
import com.capo.sub_agent_visual_effects.services.AnalystTranslatorTools;
import com.capo.sub_agent_visual_effects.services.ArtistTranslatorTools;


@Configuration
public class AgentGeneralOrchestratorConfig {
	
	@Value("classpath:prompts/system-prompt.md")
    private Resource systemPromptResource;
	
	@Bean
    public String systemPrompt() throws IOException {
        return systemPromptResource.getContentAsString(Charset.defaultCharset());
    }

	@Bean
    public ChatClient chatClientGeneral(ChatClient.Builder builder,
    		ActionWithOpenCVTools actionWithOpenCVTool,
    		ArtistTranslatorTools artistTranslatorTools, AnalystTranslatorTools analistTranslatorTools) {
        return builder
    		.clone()
    		.defaultOptions(OpenAiChatOptions.builder().parallelToolCalls(false).build())
    		.defaultTools(artistTranslatorTools,analistTranslatorTools, actionWithOpenCVTool)
            .build();
    }

}
