package com.substring.chat.New_Chatapp_backend.security;

import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class WebSocketAuthInterceptor implements ChannelInterceptor {

    private static final Logger log = LoggerFactory.getLogger(WebSocketAuthInterceptor.class);
    private static final Pattern TOPIC_ROOM_PATTERN = Pattern.compile("^/topic/room/([^/]+)");
    private static final Pattern USER_DESTINATION_PATTERN = Pattern.compile("^/topic/room/([^/]+)/user/([^/]+)");
    private static final Pattern USER_CALLS_TOPIC_PATTERN = Pattern.compile("^/topic/user/([^/]+)/calls");
    private static final Pattern JOIN_REQUEST_TOPIC_PATTERN = Pattern.compile("^/topic/room/([^/]+)/join-request/([^/]+)");
    private static final Pattern APP_SEND_PATTERN = Pattern.compile("^/app/(?:sendMessage|call)/([^/]+)");

    private final RoomSecurityService roomSecurityService;
    private final RoomRepo roomRepo;
    private final com.substring.chat.New_Chatapp_backend.services.RoomService roomService;
    private final WebSocketSessionRegistry webSocketSessionRegistry;
    private final UserTokenService userTokenService;

    @Autowired
    public WebSocketAuthInterceptor(RoomSecurityService roomSecurityService,
                                    RoomRepo roomRepo,
                                    com.substring.chat.New_Chatapp_backend.services.RoomService roomService,
                                    @Autowired(required = false) WebSocketSessionRegistry webSocketSessionRegistry,
                                    @Autowired(required = false) UserTokenService userTokenService) {
        this.roomSecurityService = roomSecurityService;
        this.roomRepo = roomRepo;
        this.roomService = roomService;
        this.webSocketSessionRegistry = webSocketSessionRegistry;
        this.userTokenService = userTokenService;
    }

    public WebSocketAuthInterceptor(RoomSecurityService roomSecurityService,
                                    RoomRepo roomRepo,
                                    com.substring.chat.New_Chatapp_backend.services.RoomService roomService) {
        this(roomSecurityService, roomRepo, roomService, null, null);
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
        StompCommand command = accessor.getCommand();

        if (command == null) {
            return message;
        }

        if (command == StompCommand.CONNECT) {
            String token = extractToken(accessor);
            if (token != null) {
                RoomTokenClaims claims = roomSecurityService.validateToken(token, null);
                if (claims != null) {
                    Room room = roomRepo.findByRoomId(claims.getRoomId());
                    if (room != null) {
                        boolean isMemberOrAdmin = claims.isAdmin() ||
                                (room.getAdmin() != null && room.getAdmin().equalsIgnoreCase(claims.getUsername())) ||
                                room.isMember(claims.getUsername());

                        if (room.isRemoved(claims.getUsername()) || !isMemberOrAdmin) {
                            log.warn("Removed or non-member '{}' attempted WebSocket CONNECT to room '{}'", claims.getUsername(), claims.getRoomId());
                            throw new SecurityException("Access denied: You have been removed from room " + claims.getRoomId());
                        }
                    }
                    Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                    if (sessionAttributes != null) {
                        sessionAttributes.put("ws_token", token);
                        sessionAttributes.put("ws_user", claims.getUsername());
                        sessionAttributes.put("ws_room", claims.getRoomId());
                        sessionAttributes.put("ws_admin", claims.isAdmin());
                    }
                    log.info("STOMP CONNECT verified for room user '{}' in room '{}', session '{}'",
                            claims.getUsername(), claims.getRoomId(), accessor.getSessionId());
                } else if (userTokenService != null) {
                    // Authenticate global user session via JWT user auth token
                    UserTokenClaims userClaims = userTokenService.validateToken(token);
                    if (userClaims != null) {
                        Map<String, Object> sessionAttributes = accessor.getSessionAttributes();
                        if (sessionAttributes != null) {
                            sessionAttributes.put("ws_token", token);
                            sessionAttributes.put("ws_user", userClaims.getName());
                            sessionAttributes.put("ws_userId", userClaims.getUserId());
                            sessionAttributes.put("ws_email", userClaims.getEmail());
                        }
                        log.info("STOMP CONNECT verified for global user '{}' (id: '{}'), session '{}'",
                                userClaims.getName(), userClaims.getUserId(), accessor.getSessionId());
                    } else {
                        log.warn("STOMP CONNECT token validation failed for session '{}'", accessor.getSessionId());
                    }
                } else {
                    log.warn("STOMP CONNECT token validation failed for session '{}'", accessor.getSessionId());
                }
            } else {
                log.debug("STOMP CONNECT received without token header for session '{}'", accessor.getSessionId());
            }
            return message;
        }

        if (command == StompCommand.SUBSCRIBE) {
            String destination = accessor.getDestination();
            if (destination != null) {
                // Guests waiting for approval can subscribe to their specific request updates
                Matcher joinReqMatcher = JOIN_REQUEST_TOPIC_PATTERN.matcher(destination);
                if (joinReqMatcher.find()) {
                    log.debug("Guest subscribed to join-request topic: {}", destination);
                    return message;
                }

                // Global user call notifications (/topic/user/{username_or_id}/calls)
                Matcher userCallsMatcher = USER_CALLS_TOPIC_PATTERN.matcher(destination);
                if (userCallsMatcher.find()) {
                    String targetUser = userCallsMatcher.group(1);
                    String sessionUser = null;
                    String sessionUserId = null;
                    if (accessor.getSessionAttributes() != null) {
                        sessionUser = (String) accessor.getSessionAttributes().get("ws_user");
                        sessionUserId = (String) accessor.getSessionAttributes().get("ws_userId");
                    }
                    String token = extractToken(accessor);
                    if (token != null && userTokenService != null) {
                        UserTokenClaims uClaims = userTokenService.validateToken(token);
                        if (uClaims != null) {
                            sessionUser = uClaims.getName();
                            sessionUserId = uClaims.getUserId();
                        }
                    }
                    boolean isAuthorized = (sessionUser != null && sessionUser.equalsIgnoreCase(targetUser))
                            || (sessionUserId != null && sessionUserId.equalsIgnoreCase(targetUser));
                    if (!isAuthorized) {
                        log.warn("User '{}' attempted unauthorized subscription to calls channel '{}'", sessionUser, destination);
                        throw new SecurityException("Unauthorized: Cannot subscribe to another user's call channel");
                    }
                    log.info("Authorized user '{}' subscribed to personal calls channel '{}'", targetUser, destination);
                    return message;
                }

                Matcher matcher = TOPIC_ROOM_PATTERN.matcher(destination);
                if (matcher.find()) {
                    String roomId = matcher.group(1);
                    RoomTokenClaims claims = validateRoomAccess(accessor, roomId, "subscribe to " + destination);

                    // Ensure only room Admin can subscribe to the admin topic
                    if (destination.equals("/topic/room/" + roomId + "/admin")) {
                        if (!claims.isAdmin()) {
                            log.warn("Non-admin user '{}' attempted unauthorized subscription to admin topic '{}'", claims.getUsername(), destination);
                            throw new SecurityException("Unauthorized: Only the room Admin can subscribe to the admin channel");
                        }
                    }

                    // Ensure users can only subscribe to their own private user notifications channel
                    Matcher userMatcher = USER_DESTINATION_PATTERN.matcher(destination);
                    if (userMatcher.find()) {
                        String targetUser = userMatcher.group(2);
                        if (!claims.getUsername().equalsIgnoreCase(targetUser)) {
                            log.warn("User '{}' attempted unauthorized subscription to user channel '{}'", claims.getUsername(), destination);
                            throw new SecurityException("Unauthorized: Cannot subscribe to another user's private channel");
                        }
                    }

                    // If subscribing to the main room topic, update member connection
                    if (destination.equals("/topic/room/" + roomId)) {
                        roomService.updateMemberConnection(roomId, claims.getUsername(), true);
                    }
                    log.debug("User '{}' subscribed to destination '{}'", claims.getUsername(), destination);
                }
            }
            return message;
        }

        if (command == StompCommand.SEND) {
            String destination = accessor.getDestination();
            if (destination != null) {
                Matcher matcher = APP_SEND_PATTERN.matcher(destination);
                if (matcher.find()) {
                    String roomId = matcher.group(1);
                    String token = extractToken(accessor);
                    if (token == null && accessor.getSessionAttributes() != null) {
                        token = (String) accessor.getSessionAttributes().get("ws_token");
                    }
                    if (token == null) {
                        log.warn("Unauthorized SEND without token for room '{}'", roomId);
                        throw new SecurityException("Unauthorized WebSocket access: token missing for room " + roomId);
                    }
                    RoomTokenClaims claims = roomSecurityService.validateToken(token, roomId);
                    if (claims != null) {
                        if (accessor.getSessionAttributes() != null) {
                            accessor.getSessionAttributes().put("ws_user", claims.getUsername());
                            accessor.getSessionAttributes().put("ws_room", claims.getRoomId());
                        }
                    } else if (userTokenService != null) {
                        // Support user authentication token for call signaling in rooms user is part of
                        UserTokenClaims uClaims = userTokenService.validateToken(token);
                        if (uClaims != null) {
                            Room room = roomRepo.findByRoomId(roomId);
                            boolean isAuthorized = room != null && (
                                    room.isMember(uClaims.getName())
                                    || (room.getParticipantUserIds() != null && room.getParticipantUserIds().contains(uClaims.getUserId()))
                                    || (room.getAdmin() != null && room.getAdmin().equalsIgnoreCase(uClaims.getName()))
                            );
                            if (!isAuthorized) {
                                log.warn("User '{}' not authorized for room '{}'", uClaims.getName(), roomId);
                                throw new SecurityException("Access denied for room " + roomId);
                            }
                            if (accessor.getSessionAttributes() != null) {
                                accessor.getSessionAttributes().put("ws_user", uClaims.getName());
                                accessor.getSessionAttributes().put("ws_userId", uClaims.getUserId());
                                accessor.getSessionAttributes().put("ws_room", roomId);
                            }
                        } else {
                            log.warn("Invalid or expired token on SEND for room '{}'", roomId);
                            throw new SecurityException("Invalid or expired authentication token for room " + roomId);
                        }
                    } else {
                        log.warn("Invalid or expired token on SEND for room '{}'", roomId);
                        throw new SecurityException("Invalid or expired authentication token for room " + roomId);
                    }
                }
            }
            return message;
        }

        return message;
    }

    private RoomTokenClaims validateRoomAccess(StompHeaderAccessor accessor, String roomId, String action) {
        String token = extractToken(accessor);
        if (token == null && accessor.getSessionAttributes() != null) {
            token = (String) accessor.getSessionAttributes().get("ws_token");
        }

        if (token == null) {
            log.warn("Unauthorized WebSocket action '{}' without authentication token for room '{}'", action, roomId);
            throw new SecurityException("Unauthorized WebSocket access: token missing for room " + roomId);
        }

        RoomTokenClaims claims = roomSecurityService.validateToken(token, roomId);
        if (claims == null) {
            log.warn("Invalid or expired WebSocket token on '{}' for room '{}'", action, roomId);
            throw new SecurityException("Invalid or expired authentication token for room " + roomId);
        }

        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            throw new SecurityException("Room does not exist: " + roomId);
        }

        if (room.isRemoved(claims.getUsername())) {
            log.warn("Removed user '{}' attempted WebSocket action '{}' in room '{}'", claims.getUsername(), action, roomId);
            throw new SecurityException("Access denied: You have been removed from room " + roomId);
        }

        boolean isMemberOrAdmin = claims.isAdmin() ||
                (room.getAdmin() != null && room.getAdmin().equalsIgnoreCase(claims.getUsername())) ||
                room.isMember(claims.getUsername());

        if (!isMemberOrAdmin) {
            log.warn("Non-member '{}' attempted WebSocket action '{}' in room '{}'", claims.getUsername(), action, roomId);
            throw new SecurityException("Access denied: You are not an active member of room " + roomId);
        }

        // Cache authenticated user info in session
        if (accessor.getSessionAttributes() != null) {
            accessor.getSessionAttributes().put("ws_user", claims.getUsername());
            accessor.getSessionAttributes().put("ws_room", claims.getRoomId());
            accessor.getSessionAttributes().put("ws_admin", claims.isAdmin());
            accessor.getSessionAttributes().put("ws_token", token);
        }

        if (webSocketSessionRegistry != null && accessor.getSessionId() != null) {
            webSocketSessionRegistry.registerUserSession(roomId, claims.getUsername(), accessor.getSessionId());
        }

        return claims;
    }

    private String extractToken(StompHeaderAccessor accessor) {
        String token = accessor.getFirstNativeHeader("token");
        if (token == null) {
            token = accessor.getFirstNativeHeader("X-Room-Token");
        }
        if (token == null) {
            token = accessor.getFirstNativeHeader("Authorization");
        }
        if (token == null) {
            token = accessor.getFirstNativeHeader("authorization");
        }
        if (token == null) {
            token = accessor.getPasscode();
        }
        if (token != null && (token.startsWith("Bearer ") || token.startsWith("bearer "))) {
            token = token.substring(7).trim();
        }
        return token;
    }
}
