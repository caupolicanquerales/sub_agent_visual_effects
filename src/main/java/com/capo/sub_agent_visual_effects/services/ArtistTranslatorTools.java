package com.capo.sub_agent_visual_effects.services;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.capo.sub_agent_visual_effects.utils.ToolCallGuard;

@Service
public class ArtistTranslatorTools {

	private final ToolCallGuard guard = new ToolCallGuard();
	private final ChatClient chatClient;
	private final String systemPrompt;
	
	private static final Logger log = LoggerFactory.getLogger(ArtistTranslatorTools.class);
	
	public ArtistTranslatorTools(@Qualifier("chatClientArtist") ChatClient chatClient,
			@Qualifier("systemPromptArtist") String systemPrompt) {
		this.chatClient= chatClient;
		this.systemPrompt= systemPrompt;
	}
	
	public void resetGuard() {
		guard.reset();
	}

	@Tool(description = """
			Visual Effects Artist Agent — interprets any visual effect description (in English or Spanish,
			including poetic, metaphorical, or domain-specific language) and produces a structured
			[VISUAL EFFECTS PLAN].

			Input: a free-text description of one or more visual effects the user wants to apply to an image.

			Output: a [VISUAL EFFECTS PLAN] block containing:
			  - EFFECT_TITLE: short name for the combined effect.
			  - PHYSICAL_REFERENCE: the real-world phenomenon the effect resembles.
			  - STRUCTURE_PRESERVED: whether image legibility survives all steps (YES/NO).
			  - STEPS: an ordered list of visual transformation steps, each with NAME, DOMAIN
			    (COLOUR | SMOOTHING | TEXTURE | MORPHOLOGY | GEOMETRY | THRESHOLDING | BLENDING),
			    VISUAL_GOAL, PROPERTIES_AFFECTED, CONSTRAINTS, INTENSITY, and ORDER.

		  This plan is intended to be consumed by the Analyst Agent (generateAnalystInterpretion),
			which will translate each step into exact OpenCV call specifications.
			Do NOT call this tool with already-structured plan text; only call it with raw user descriptions.
			""")
	public String generateArtistInterpretion(String prompt){
		if (!guard.isFirstCall()) {
			return guard.getCachedResult();
		}
		String result = this.chatClient.prompt()
				.messages(new SystemMessage(systemPrompt))
				.user(prompt)
				.call()
				.content();
		guard.recordResult(result);
		return result;
	}
}
