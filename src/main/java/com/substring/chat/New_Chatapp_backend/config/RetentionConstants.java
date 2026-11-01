package com.substring.chat.New_Chatapp_backend.config;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public class RetentionConstants {

    // Default message retention is 7 days (604,800 seconds) for group rooms
    public static final long DEFAULT_RETENTION_SECONDS = 604800L;

    // Strict 24-hour auto-delete retention for 1-on-1 private direct chats (86,400 seconds)
    public static final long DIRECT_CHAT_RETENTION_SECONDS = 86400L;

    // Room inactivity threshold is 30 consecutive days
    public static final long INACTIVITY_DAYS = 30L;
    public static final long INACTIVITY_MILLIS = INACTIVITY_DAYS * 24L * 60L * 60L * 1000L;

    // Exactly 7 allowed custom retention durations
    public static final Set<Long> ALLOWED_CUSTOM_DURATIONS;

    static {
        Set<Long> durations = new LinkedHashSet<>();
        durations.add(60L);      // 1 minute — 60 seconds
        durations.add(300L);     // 5 minutes — 300 seconds
        durations.add(1800L);    // 30 minutes — 1800 seconds
        durations.add(3600L);    // 1 hour — 3600 seconds
        durations.add(7200L);    // 2 hours — 7200 seconds
        durations.add(21600L);   // 6 hours — 21600 seconds
        durations.add(86400L);   // 24 hours — 86400 seconds
        durations.add(172800L);  // 2 days — 172800 seconds
        ALLOWED_CUSTOM_DURATIONS = Collections.unmodifiableSet(durations);
    }

    public static boolean isAllowedDuration(Long seconds) {
        return seconds != null && ALLOWED_CUSTOM_DURATIONS.contains(seconds);
    }
}
