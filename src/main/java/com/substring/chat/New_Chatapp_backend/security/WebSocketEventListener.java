package com.substring.chat.New_Chatapp_backend.security;
import com.substring.chat.New_Chatapp_backend.services.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.messaging.SessionDisconnectEvent;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class WebSocketEventListener {

    private static final Logger log = LoggerFactory.getLogger(WebSocketEventListener.class);

    private final RoomService roomService;
    private final SimpMessagingTemplate messagingTemplate;

    public WebSocketEventListener(RoomService roomService,
                                  @Autowired(required = false) SimpMessagingTemplate messagingTemplate) {
        this.roomService = roomService;
        this.messagingTemplate = messagingTemplate;
    }

    @EventListener
    public void handleWebSocketDisconnectListener(SessionDisconnectEvent event) {
        StompHeaderAccessor headerAccessor = StompHeaderAccessor.wrap(event.getMessage());
        Map<String, Object> sessionAttributes = headerAccessor.getSessionAttributes();
        String sessionId = event.getSessionId();
        org.springframework.web.socket.CloseStatus closeStatus = event.getCloseStatus();

        if (sessionAttributes != null) {
            String username = (String) sessionAttributes.get("ws_user");
            String roomId = (String) sessionAttributes.get("ws_room");

            if (username != null && roomId != null) {
                log.info("WebSocket disconnect: user '{}' from room '{}', sessionId='{}', closeStatus={}",
                        username, roomId, sessionId, closeStatus);
                roomService.updateMemberConnection(roomId, username, false);

                if (messagingTemplate != null) {
                    try {
                        Map<String, Object> payload = new LinkedHashMap<>();
                        payload.put("type", "PRESENCE_CHANGED");
                        payload.put("user", username);
                        payload.put("connected", false);
                        payload.put("members", roomService.getMembers(roomId));
                        messagingTemplate.convertAndSend("/topic/room/" + roomId + "/members", (Object) payload);
                    } catch (Exception e) {
                        log.warn("Could not broadcast presence update: {}", e.getMessage());
                    }
                }
            }
        }
    }
}
