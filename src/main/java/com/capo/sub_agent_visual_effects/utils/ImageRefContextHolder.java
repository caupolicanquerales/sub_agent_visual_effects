package com.capo.sub_agent_visual_effects.utils;

public class ImageRefContextHolder {

    private static final ThreadLocal<String> HOLDER = new ThreadLocal<>();

    public static void set(String imageKey) {
        HOLDER.set(imageKey);
    }

    public static String get() {
        return HOLDER.get();
    }

    public static void clear() {
        HOLDER.remove();
    }
}
