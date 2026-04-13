package com.capo.sub_agent_visual_effects.utils;

import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.function.Supplier;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public class SseStreamUtil {

    public static SseEmitter stream(ExecutorService executor, String eventName, String startMessage,
            Supplier<CompletableFuture<String>> supplier,
            Function<String, String> resultTransformer) {

        final SseEmitter emitter = new SseEmitter(300_000L);
        executor.execute(() -> {
            try {
                emitter.send(SseEmitter.event().name(eventName).data(startMessage));

                supplier.get().whenComplete((result, exception) -> {
                    if (exception != null) {
                        emitter.completeWithError(exception);
                        return;
                    }

                    try {
                        String payload = resultTransformer.apply(result);
                        emitter.send(SseEmitter.event().name(eventName).data(payload));
                        emitter.complete();
                    } catch (IOException ioException) {
                        emitter.completeWithError(ioException);
                    }
                });

            } catch (Exception e) {
                emitter.completeWithError(e);
            }
        });

        return emitter;
    }

}
