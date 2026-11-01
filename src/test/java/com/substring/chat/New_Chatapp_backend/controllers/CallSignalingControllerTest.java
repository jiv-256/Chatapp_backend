package com.substring.chat.New_Chatapp_backend.controllers;

import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.entities.RoomMember;
import com.substring.chat.New_Chatapp_backend.payload.CallSignalMessage;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CallSignalingControllerTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    @Mock
    private RoomRepo roomRepo;

    private CallSignalingController signalingController;

    @BeforeEach
    void setUp() {
        signalingController = new CallSignalingController(messagingTemplate, roomRepo);
    }

    @Test
    void testAuthorizedMemberCanBroadcastCallSignal() {
        Room room = new Room();
        room.setRoomId("dev-room");
        room.setAdmin("Alice");
        room.setMembers(new ArrayList<>(List.of(
                new RoomMember("u-1", "Alice", "ADMIN", "Felix"),
                new RoomMember("u-2", "Bob", "MEMBER", "Aneka")
        )));

        when(roomRepo.findByRoomId("dev-room")).thenReturn(room);

        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put("ws_user", "Bob");
        headerAccessor.setSessionAttributes(sessionAttributes);

        CallSignalMessage signal = new CallSignalMessage();
        signal.setType("INITIATE");
        signal.setCallType("video");
        signal.setCallId("call-123");
        signal.setSender("Bob");

        signalingController.handleCallSignal("dev-room", signal, headerAccessor);

        ArgumentCaptor<CallSignalMessage> captor = ArgumentCaptor.forClass(CallSignalMessage.class);
        verify(messagingTemplate).convertAndSend(eq("/topic/room/dev-room/call"), captor.capture());

        CallSignalMessage dispatched = captor.getValue();
        assertThat(dispatched.getType()).isEqualTo("INITIATE");
        assertThat(dispatched.getSender()).isEqualTo("Bob");
        assertThat(dispatched.getRoomId()).isEqualTo("dev-room");
        assertThat(dispatched.getCallType()).isEqualTo("video");
    }

    @Test
    void testNonMemberCannotBroadcastCallSignal() {
        Room room = new Room();
        room.setRoomId("secret-room");
        room.setAdmin("Alice");
        room.setMembers(new ArrayList<>(List.of(
                new RoomMember("u-1", "Alice", "ADMIN", "Felix")
        )));

        when(roomRepo.findByRoomId("secret-room")).thenReturn(room);

        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put("ws_user", "Hacker");
        headerAccessor.setSessionAttributes(sessionAttributes);

        CallSignalMessage signal = new CallSignalMessage();
        signal.setType("INITIATE");
        signal.setCallType("voice");

        signalingController.handleCallSignal("secret-room", signal, headerAccessor);

        // Must drop unauthorized signal
        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void testRemovedMemberCannotBroadcastCallSignal() {
        Room room = new Room();
        room.setRoomId("team-room");
        room.setAdmin("Alice");
        room.getRemovedMembers().add("Bob");

        when(roomRepo.findByRoomId("team-room")).thenReturn(room);

        SimpMessageHeaderAccessor headerAccessor = SimpMessageHeaderAccessor.create();
        Map<String, Object> sessionAttributes = new HashMap<>();
        sessionAttributes.put("ws_user", "Bob");
        headerAccessor.setSessionAttributes(sessionAttributes);

        CallSignalMessage signal = new CallSignalMessage();
        signal.setType("OFFER");

        signalingController.handleCallSignal("team-room", signal, headerAccessor);

        verify(messagingTemplate, never()).convertAndSend(anyString(), any(Object.class));
    }

    @Test
    void testGetWebRtcConfigReturnsStunServers() {
        ResponseEntity<Map<String, Object>> response = signalingController.getWebRtcConfig();

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        Map<String, Object> body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.get("topology")).isEqualTo("mesh");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> iceServers = (List<Map<String, Object>>) body.get("iceServers");
        assertThat(iceServers).isNotEmpty();
        assertThat(iceServers.get(0).get("urls")).toString().contains("stun:stun.l.google.com:19302");
    }

    @Test
    void testGroupCallLifecycleAndActiveStatus() {
        Room room = new Room();
        room.setRoomId("group-room");
        room.setRoomName("Developers");
        room.setAdmin("Alice");
        room.setMembers(new ArrayList<>(List.of(
                new RoomMember("u-1", "Alice", "ADMIN", "Felix"),
                new RoomMember("u-2", "Bob", "MEMBER", "Aneka")
        )));

        when(roomRepo.findByRoomId("group-room")).thenReturn(room);

        // 1. Initially no active call
        ResponseEntity<Map<String, Object>> initialStatus = signalingController.getActiveCall("group-room");
        assertThat(initialStatus.getBody()).isNotNull();
        assertThat(initialStatus.getBody().get("active")).isEqualTo(false);

        // 2. Bob initiates call
        SimpMessageHeaderAccessor bobHeaders = SimpMessageHeaderAccessor.create();
        bobHeaders.setSessionAttributes(Map.of("ws_user", "Bob"));

        CallSignalMessage initSignal = new CallSignalMessage();
        initSignal.setType("INITIATE");
        initSignal.setCallType("video");
        initSignal.setSender("Bob");
        initSignal.setCallId("call-grp-1");

        signalingController.handleCallSignal("group-room", initSignal, bobHeaders);

        // Verify active call state
        ResponseEntity<Map<String, Object>> activeStatus1 = signalingController.getActiveCall("group-room");
        assertThat(activeStatus1.getBody()).isNotNull();
        assertThat(activeStatus1.getBody().get("active")).isEqualTo(true);
        assertThat(activeStatus1.getBody().get("callType")).isEqualTo("video");
        assertThat(activeStatus1.getBody().get("initiator")).isEqualTo("Bob");

        // 3. Alice accepts call
        SimpMessageHeaderAccessor aliceHeaders = SimpMessageHeaderAccessor.create();
        aliceHeaders.setSessionAttributes(Map.of("ws_user", "Alice"));

        CallSignalMessage acceptSignal = new CallSignalMessage();
        acceptSignal.setType("ACCEPT");
        acceptSignal.setCallType("video");
        acceptSignal.setCallId("call-grp-1");
        acceptSignal.setSender("Alice");

        signalingController.handleCallSignal("group-room", acceptSignal, aliceHeaders);

        ResponseEntity<Map<String, Object>> activeStatus2 = signalingController.getActiveCall("group-room");
        assertThat(activeStatus2.getBody()).isNotNull();
        assertThat(activeStatus2.getBody().get("active")).isEqualTo(true);
        List<?> participants = (List<?>) activeStatus2.getBody().get("participants");
        assertThat(participants).hasSize(2);

        // 4. Bob leaves call (Alice remains)
        CallSignalMessage bobLeaveSignal = new CallSignalMessage();
        bobLeaveSignal.setType("LEAVE");
        bobLeaveSignal.setCallId("call-grp-1");
        bobLeaveSignal.setSender("Bob");

        signalingController.handleCallSignal("group-room", bobLeaveSignal, bobHeaders);

        ResponseEntity<Map<String, Object>> activeStatus3 = signalingController.getActiveCall("group-room");
        assertThat(activeStatus3.getBody()).isNotNull();
        assertThat(activeStatus3.getBody().get("active")).isEqualTo(true);
        List<?> participantsAfterBobLeave = (List<?>) activeStatus3.getBody().get("participants");
        assertThat(participantsAfterBobLeave).hasSize(1);

        // 5. Alice leaves call (call terminates)
        CallSignalMessage aliceLeaveSignal = new CallSignalMessage();
        aliceLeaveSignal.setType("LEAVE");
        aliceLeaveSignal.setCallId("call-grp-1");
        aliceLeaveSignal.setSender("Alice");

        signalingController.handleCallSignal("group-room", aliceLeaveSignal, aliceHeaders);

        ResponseEntity<Map<String, Object>> activeStatusFinal = signalingController.getActiveCall("group-room");
        assertThat(activeStatusFinal.getBody()).isNotNull();
        assertThat(activeStatusFinal.getBody().get("active")).isEqualTo(false);
    }

    @Test
    void testDirectOneToOneCallRoutingAndDecline() {
        Room directRoom = new Room();
        directRoom.setRoomId("direct_Alice_Bob");
        directRoom.setRoomType("DIRECT");
        directRoom.setAdmin("Alice");
        directRoom.setMembers(new ArrayList<>(List.of(
                new RoomMember("u-1", "Alice", "MEMBER", "Felix"),
                new RoomMember("u-2", "Bob", "MEMBER", "Aneka")
        )));

        when(roomRepo.findByRoomId("direct_Alice_Bob")).thenReturn(directRoom);

        // Alice calls Bob
        SimpMessageHeaderAccessor aliceHeaders = SimpMessageHeaderAccessor.create();
        aliceHeaders.setSessionAttributes(Map.of("ws_user", "Alice"));

        CallSignalMessage initSignal = new CallSignalMessage();
        initSignal.setType("INITIATE");
        initSignal.setCallType("voice");
        initSignal.setSender("Alice");

        signalingController.handleCallSignal("direct_Alice_Bob", initSignal, aliceHeaders);

        // Verify notification sent to Bob's personal channel
        verify(messagingTemplate).convertAndSend(eq("/topic/user/bob/calls"), any(CallSignalMessage.class));

        // Bob declines call
        SimpMessageHeaderAccessor bobHeaders = SimpMessageHeaderAccessor.create();
        bobHeaders.setSessionAttributes(Map.of("ws_user", "Bob"));

        CallSignalMessage declineSignal = new CallSignalMessage();
        declineSignal.setType("DECLINE");
        declineSignal.setCallType("voice");
        declineSignal.setSender("Bob");

        signalingController.handleCallSignal("direct_Alice_Bob", declineSignal, bobHeaders);

        // Verify decline notification routed back to Alice
        verify(messagingTemplate).convertAndSend(eq("/topic/user/alice/calls"), any(CallSignalMessage.class));

        // Call status should now be inactive
        ResponseEntity<Map<String, Object>> directStatus = signalingController.getActiveCall("direct_Alice_Bob");
        assertThat(directStatus.getBody()).isNotNull();
        assertThat(directStatus.getBody().get("active")).isEqualTo(false);
    }
}
