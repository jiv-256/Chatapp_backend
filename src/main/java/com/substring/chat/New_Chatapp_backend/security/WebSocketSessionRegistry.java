package com.substring.chat.New_Chatapp_backend.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.WebSocketHandlerDecorator;

import java.io.IOException;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Component
public class WebSocketSessionRegistry {

    private static final Logger log = LoggerFactory.getLogger(WebSocketSessionRegistry.class);
    private static final CloseStatus REMOVAL_STATUS = new CloseStatus(4003, "Removed from room by Admin");

    // sessionId -> WebSocketSession
    private final Map<String, WebSocketSession> activeSessions = new ConcurrentHashMap<>();

    // "roomId:username" -> Set of sessionIds
    private final Map<String, Set<String>> userSessions = new ConcurrentHashMap<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-session-closer");
        t.setDaemon(true);
        return t;
    });

    public WebSocketHandler createDecorator(WebSocketHandler handler) {
        return new WebSocketHandlerDecorator(handler) {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                activeSessions.put(session.getId(), session);
                super.afterConnectionEstablished(session);
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) throws Exception {
                activeSessions.remove(session.getId());
                removeSessionFromUserMapping(session.getId());
                super.afterConnectionClosed(session, closeStatus);
            }
        };
    }

    public void registerUserSession(String roomId, String username, String sessionId) {
        if (roomId == null || username == null || sessionId == null) return;
        String key = buildKey(roomId, username);
        userSessions.computeIfAbsent(key, k -> ConcurrentHashMap.newKeySet()).add(sessionId);
    }

    public void closeSessionsForUser(String roomId, String username) {
        if (roomId == null || username == null) return;
        String key = buildKey(roomId, username);
        Set<String> sessionIds = userSessions.remove(key);
        if (sessionIds == null || sessionIds.isEmpty()) return;

        // Give a short 300ms window so the targeted STOMP notification reaches the client first
        scheduler.schedule(() -> {
            for (String sessionId : sessionIds) {
                WebSocketSession session = activeSessions.remove(sessionId);
                if (session != null && session.isOpen()) {
                    try {
                        log.info("Closing active WebSocket session '{}' for removed member '{}/{}'", sessionId, roomId, username);
                        session.close(REMOVAL_STATUS);
                    } catch (IOException e) {
                        log.warn("Error closing WebSocket session '{}': {}", sessionId, e.getMessage());
                    }
                }
            }
        }, 300, TimeUnit.MILLISECONDS);
    }

    private void removeSessionFromUserMapping(String sessionId) {
        for (Set<String> set : userSessions.values()) {
            set.remove(sessionId);
        }
    }

    private String buildKey(String roomId, String username) {
        return roomId.trim().toLowerCase() + ":" + username.trim().toLowerCase();
    }
}
