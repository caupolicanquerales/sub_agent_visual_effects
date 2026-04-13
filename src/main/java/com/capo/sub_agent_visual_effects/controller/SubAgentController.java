package com.capo.sub_agent_visual_effects.controller;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.capo.sub_agent_visual_effects.request.GenerationSyntheticDataRequest;
import com.capo.sub_agent_visual_effects.services.ExecutingActionOpenCVService;
import com.capo.sub_agent_visual_effects.utils.SseStreamUtil;



@RestController
@RequestMapping("sub-agent-visual-effects")
public class SubAgentController {

	private static final String REDIS_KEY_PREFIX = "Result stored in Redis at key: ";

	private final ExecutorService executor = Executors.newCachedThreadPool();
	private final ExecutingActionOpenCVService executingActionOpenCV;
	private final RedisTemplate<String, Object> redisTemplate;

	@Value(value="${event.name.image}")
	private String eventName;

	public SubAgentController(ExecutingActionOpenCVService executingActionOpenCV,
			RedisTemplate<String, Object> redisTemplate) {
		this.executingActionOpenCV = executingActionOpenCV;
		this.redisTemplate = redisTemplate;
	}

	@PostMapping(path = "/stream-image", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamImageGeneration(@RequestBody GenerationSyntheticDataRequest request) {
        return SseStreamUtil.stream(executor, eventName, "Image visual effect started for prompt",
                () -> executingActionOpenCV.generateActionOpneCVAsync(
                        request.getPrompt(), request.getImageReferences()),
                result -> {
                    if (result != null && result.contains(REDIS_KEY_PREFIX)) {
                        String redisKey = result.substring(
                                result.indexOf(REDIS_KEY_PREFIX) + REDIS_KEY_PREFIX.length())
                                .lines()
                                .findFirst()
                                .orElse("")
                                .trim();
                        if (!redisKey.isEmpty()) {
                            Object image = redisTemplate.opsForValue().get(redisKey);
                            if (image != null) {
                                return image.toString();
                            }
                        }
                    }
                    return result;
                });
	}
}