package com.capo.sub_agent_visual_effects.utils;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Per-tool-instance call guard with result caching.
 *
 * On the first call, {@link #recordResult(String)} stores the result.
 * On any subsequent call, {@link #getCachedResult()} returns the stored result
 * instead of re-executing — so the LLM sees the same output even if it
 * mistakenly calls the tool more than once, and the pipeline continues without
 * a confusing ERROR message in the conversation history.
 *
 * Thread-safe via AtomicReference; reset() clears both flags for the next request.
 */
public class ToolCallGuard {

    private static final String SENTINEL = "__NOT_CALLED__";
    private final AtomicReference<String> cachedResult = new AtomicReference<>(SENTINEL);

    /**
     * @return {@code true} if this is the first call — the caller should execute
     *         and then call {@link #recordResult(String)} with its output.
     *         {@code false} if the tool has already been called — the caller
     *         should return {@link #getCachedResult()} immediately.
     */
    public boolean isFirstCall() {
        return cachedResult.get() == SENTINEL;
    }

    /**
     * Stores the result produced by the first execution.
     * Must be called exactly once, right after the first call completes.
     */
    public void recordResult(String result) {
        cachedResult.compareAndSet(SENTINEL, result);
    }

    /**
     * Returns the cached result from the first execution.
     * Only valid after {@link #recordResult(String)} has been called.
     */
    public String getCachedResult() {
        return cachedResult.get();
    }

    /**
     * Resets the guard for the next request.
     * Must be called before each top-level orchestration request starts.
     */
    public void reset() {
        cachedResult.set(SENTINEL);
    }
}
