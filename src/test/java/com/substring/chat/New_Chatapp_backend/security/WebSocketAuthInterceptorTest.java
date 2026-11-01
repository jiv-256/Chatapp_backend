package com.substring.chat.New_Chatapp_backend.security;

import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.entities.RoomMember;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import com.substring.chat.New_Chatapp_backend.services.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WebSocketAuthInterceptorTest {

    @Mock
    private RoomSecurityService roomSecurityService;

    @Mock
    private RoomRepo roomRepo;

    @Mock
    private RoomService roomService;

    @Mock
    private WebSocketSessionRegistry webSocketSessionRegistry;

    @Mock
    private UserTokenService userTokenService;

    @Mock
    private MessageChannel messageChannel;

    private WebSocketAuthInterceptor interceptor;

    @BeforeEach
    void setUp() {
        interceptor = new WebSocketAuthInterceptor(
                roomSecurityService,
                roomRepo,
                roomService,
                webSocketSessionRegistry,
                userTokenService
        );
    }

    @Test
    void testConnectWithUserJwtTokenPopulatesSessionUserAndId() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.CONNECT);
        accessor.addNativeHeader("Authorization", "Bearer user-jwt-token-123");
        accessor.setSessionId("sess-1");
        accessor.setSessionAttributes(new HashMap<>());

        when(userTokenService.validateToken("user-jwt-token-123")).thenReturn(
                new UserTokenClaims("user-id-456", "bob@example.com", "Bob", "avatar-1", "google-123")
        );

        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        Message<?> result = interceptor.preSend(message, messageChannel);

        assertThat(result).isNotNull();
        StompHeaderAccessor resultAccessor = StompHeaderAccessor.wrap(result);
        Map<String, Object> sessionAttrs = resultAccessor.getSessionAttributes();
        assertThat(sessionAttrs).isNotNull();
        assertThat(sessionAttrs.get("ws_user")).isEqualTo("Bob");
        assertThat(sessionAttrs.get("ws_userId")).isEqualTo("user-id-456");
        assertThat(sessionAttrs.get("ws_email")).isEqualTo("bob@example.com");
    }

    @Test
    void testSubscribeUserCallsAllowedWhenMatchesUsernameCaseInsensitively() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/user/bob/calls");
        accessor.setSessionId("sess-1");

        Map<String, Object> sessionAttrs = new HashMap<>();
        sessionAttrs.put("ws_user", "Bob");
        sessionAttrs.put("ws_userId", "user-id-456");
        accessor.setSessionAttributes(sessionAttrs);

        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        Message<?> result = interceptor.preSend(message, messageChannel);

        assertThat(result).isNotNull();
    }

    @Test
    void testSubscribeUserCallsAllowedWhenMatchesUserId() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/user/user-id-456/calls");
        accessor.setSessionId("sess-1");

        Map<String, Object> sessionAttrs = new HashMap<>();
        sessionAttrs.put("ws_user", "Bob");
        sessionAttrs.put("ws_userId", "user-id-456");
        accessor.setSessionAttributes(sessionAttrs);

        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        Message<?> result = interceptor.preSend(message, messageChannel);

        assertThat(result).isNotNull();
    }

    @Test
    void testSubscribeUserCallsRejectedForUnauthorizedUser() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination("/topic/user/alice/calls");
        accessor.setSessionId("sess-1");

        Map<String, Object> sessionAttrs = new HashMap<>();
        sessionAttrs.put("ws_user", "Bob");
        sessionAttrs.put("ws_userId", "user-id-456");
        accessor.setSessionAttributes(sessionAttrs);

        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, messageChannel))
                .isInstanceOf(SecurityException.class)
                .hasMessageContaining("Unauthorized: Cannot subscribe to another user's call channel");
    }

    @Test
    void testSendToCallTopicAllowedForRoomParticipant() {
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination("/app/call/direct_Alice_Bob");
        accessor.addNativeHeader("Authorization", "Bearer user-token-alice");
        accessor.setSessionId("sess-1");

        Map<String, Object> sessionAttrs = new HashMap<>();
        sessionAttrs.put("ws_user", "Alice");
        sessionAttrs.put("ws_userId", "u-1");
        accessor.setSessionAttributes(sessionAttrs);

        when(userTokenService.validateToken("user-token-alice")).thenReturn(
                new UserTokenClaims("u-1", "alice@example.com", "Alice", "avatar-1", "google-alice")
        );

        Room room = new Room();
        room.setRoomId("direct_Alice_Bob");
        room.setMembers(new ArrayList<>(List.of(
                new RoomMember("u-1", "Alice", "MEMBER", "Felix"),
                new RoomMember("u-2", "Bob", "MEMBER", "Aneka")
        )));

        when(roomRepo.findByRoomId("direct_Alice_Bob")).thenReturn(room);

        Message<?> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
        Message<?> result = interceptor.preSend(message, messageChannel);

        assertThat(result).isNotNull();
    }
}
