package com.capo.sub_agent_visual_effects.services;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.capo.sub_agent_visual_effects.utils.ImageRefContextHolder;


@Service
public class ExecutingActionOpenCVService {
	
	private final ChatClient chatClient;
	private final String systemPrompt;
	private final ArtistTranslatorTools artistTools;
	private final AnalystTranslatorTools analistTools;
	
	public ExecutingActionOpenCVService(@Qualifier("chatClientGeneral") ChatClient chatClient,
			@Qualifier("systemPrompt") String systemPrompt,
			ArtistTranslatorTools artistTools,
			AnalystTranslatorTools analistTools) {
		this.chatClient = chatClient;
		this.systemPrompt = systemPrompt;
		this.artistTools = artistTools;
		this.analistTools = analistTools;
	}
	
	public CompletableFuture<String> generateActionOpneCVAsync(String prompt, List<String> imageReferences){
		String imageKey = (imageReferences != null && !imageReferences.isEmpty())
				? imageReferences.get(0) : null;
		return CompletableFuture.supplyAsync(() -> {
			artistTools.resetGuard();
			analistTools.resetGuard();
			ImageRefContextHolder.set(imageKey);
			try {
				return this.chatClient.prompt()
						.messages(new SystemMessage(systemPrompt))
						.user(prompt)
						.call()
						.content();
			} finally {
				ImageRefContextHolder.clear();
			}
		});
	}
	
}
