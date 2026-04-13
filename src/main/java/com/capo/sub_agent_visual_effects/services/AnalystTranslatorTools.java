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
public class AnalystTranslatorTools {

	private final ToolCallGuard guard = new ToolCallGuard();
	private final ChatClient chatClient;
	private final String systemPrompt;
	
	private static final Logger log = LoggerFactory.getLogger(AnalystTranslatorTools.class);
	
	public AnalystTranslatorTools(@Qualifier("chatClientAnalist") ChatClient chatClient,
			@Qualifier("systemPromptAnalist") String systemPrompt) {
		this.chatClient= chatClient;
		this.systemPrompt= systemPrompt;
	}
	
	public void resetGuard() {
		guard.reset();
	}

	@Tool(description = """
			OpenCV Analyst Agent — receives a [VISUAL EFFECTS PLAN] produced by the Artist Agent
			(generateArtistInterpretion) and translates every step into exact OpenCV call specifications.

			Input: the full [VISUAL EFFECTS PLAN] block text output by the Artist Agent.

			Output: an [OPENCV EXECUTION PLAN] block containing one OPERATION entry per required
			OpenCV call, each with:
			  - SOURCE_STEP: the originating step from the artist plan.
			  - CATEGORY: the exact lower-case OpenCV category
			    (smoothing | geometric | thresholding | morphological | colorspace | detection).
			  - OPERATION_NAME: the exact lower-case operation key within that category.
			  - PARAMS_JSON: a valid JSON object with only the parameters that differ from defaults.
			  - RATIONALE: one sentence linking the visual goal to the chosen operation.

			This plan is intended to be consumed by the orchestrator, which will call
			executingActionWithOpenCVTool once per OPERATION entry in sequence.
			This agent does NOT call any tool itself — it only produces the execution plan.
			""")
	public String generateAnalystInterpretion(String prompt){
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
