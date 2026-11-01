package com.substring.chat.New_Chatapp_backend.services;

import com.substring.chat.New_Chatapp_backend.controllers.RoomController;
import com.substring.chat.New_Chatapp_backend.entities.JoinRequest;
import com.substring.chat.New_Chatapp_backend.entities.Message;
import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.entities.RoomMember;
import com.substring.chat.New_Chatapp_backend.exceptions.InvalidInviteCodeException;
import com.substring.chat.New_Chatapp_backend.exceptions.UnauthorizedAdminException;
import com.substring.chat.New_Chatapp_backend.payload.AuthResponse;
import com.substring.chat.New_Chatapp_backend.payload.JoinInviteRequest;
import com.substring.chat.New_Chatapp_backend.payload.JoinRoomRequest;
import com.substring.chat.New_Chatapp_backend.repo.JoinRequestRepo;
import com.substring.chat.New_Chatapp_backend.repo.MessageRepo;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import com.substring.chat.New_Chatapp_backend.security.RoomSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomActivityAndSystemMessagesTest {

    @Mock
    private RoomRepo roomRepo;

    @Mock
    private MessageRepo messageRepo;

    @Mock
    private JoinRequestRepo joinRequestRepo;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private RoomSecurityService securityService;
    private MessageService messageService;
    private RoomService roomService;
    private RoomController roomController;

    private Room testRoom;

    @BeforeEach
    void setUp() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
        securityService = new RoomSecurityService("TestSecretKey_2026_SecureAuthTestKey_123456789");
        messageService = new MessageService(messageRepo, roomRepo);
        roomService = new RoomService(roomRepo, messageService, securityService, joinRequestRepo);
        roomController = new RoomController(roomService, messageService, null, securityService, null);
        org.springframework.test.util.ReflectionTestUtils.setField(roomController, "messagingTemplate", messagingTemplate);

        testRoom = new Room();
        testRoom.setRoomId("group-chat");
        testRoom.setAdmin("Rajiv");
        testRoom.setCreatedAt(new Date());
        testRoom.setLastActivityAt(new Date());
        testRoom.setCustomDeletionTimeEnabled(true);
        testRoom.setCustomDeletionAfterSeconds(86400L); // 24 hours
        testRoom.setMembers(new ArrayList<>(List.of(
                new RoomMember("Rajiv", "ADMIN", "avatar1"),
                new RoomMember("Sajal", "MEMBER", "avatar2")
        )));

        String code = "123456";
        testRoom.setInviteCodeHash(securityService.hashInviteCode(code));
        testRoom.setEncryptedInviteCode(securityService.encryptInviteCode(code));

        String token = "valid-test-token";
        testRoom.setInviteTokenHash(securityService.hashInviteToken(token));
        testRoom.setEncryptedInviteToken(securityService.encryptInviteCode(token));
        testRoom.setInvitationVersion(1);
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        org.springframework.security.core.context.SecurityContextHolder.clearContext();
    }

    @Test
    @DisplayName("1. Admin Removal creates system message 'Rajiv (Admin) removed Sajal' and broadcasts to room")
    void testAdminRemovalGeneratesSystemMessage() {
        when(roomRepo.findByRoomId("group-chat")).thenReturn(testRoom);
        when(roomRepo.save(any(Room.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepo.save(any(Message.class))).thenAnswer(i -> {
            Message m = i.getArgument(0);
            m.setId("sys-msg-remove");
            return m;
        });

        String adminToken = securityService.generateToken("group-chat", "Rajiv", true);

        ResponseEntity<?> response = roomController.removeMember("group-chat", "Sajal", "Bearer " + adminToken, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify MessageService saved system message
        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepo).save(messageCaptor.capture());
        Message captured = messageCaptor.getValue();

        assertThat(captured.getMessageType()).isEqualTo("SYSTEM");
        assertThat(captured.getEventType()).isEqualTo("MEMBER_REMOVED");
        assertThat(captured.getActorName()).isEqualTo("Rajiv");
        assertThat(captured.getTargetName()).isEqualTo("Sajal");
        assertThat(captured.getSender()).isEqualTo("SYSTEM");
        assertThat(captured.getContent()).isEqualTo("Rajiv (Admin) removed Sajal");
        assertThat(captured.getExpiresAt()).isNotNull();

        // Verify real-time broadcast to /topic/room/{roomId}
        verify(messagingTemplate).convertAndSend(eq("/topic/room/group-chat"), any(Object.class));
    }

    @Test
    @DisplayName("2. User joins via Room ID and code creates system message 'Vikram joined the chat via code'")
    void testJoinWithCodeGeneratesSystemMessage() {
        when(roomRepo.findByRoomId("group-chat")).thenReturn(testRoom);
        when(roomRepo.save(any(Room.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepo.save(any(Message.class))).thenAnswer(i -> {
            Message m = i.getArgument(0);
            m.setId("sys-msg-code");
            return m;
        });

        JoinRoomRequest req = new JoinRoomRequest("group-chat", "Vikram", "123456", "avatar3");
        ResponseEntity<?> response = roomController.joinRoomWithCode("group-chat", req, null, null, null, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepo).save(messageCaptor.capture());
        Message captured = messageCaptor.getValue();

        assertThat(captured.getMessageType()).isEqualTo("SYSTEM");
        assertThat(captured.getEventType()).isEqualTo("MEMBER_JOINED");
        assertThat(captured.getJoinMethod()).isEqualTo("CODE");
        assertThat(captured.getTargetName()).isEqualTo("Vikram");
        assertThat(captured.getContent()).isEqualTo("Vikram joined the chat via code");

        verify(messagingTemplate).convertAndSend(eq("/topic/room/group-chat"), any(Object.class));
    }

    @Test
    @DisplayName("3. User joins via invitation link (direct join) creates system message 'Priya joined the chat via link'")
    void testDirectJoinViaLinkGeneratesSystemMessage() {
        testRoom.setAdminApprovalRequired(false); // Direct Join via Link mode
        when(roomRepo.findByRoomId("group-chat")).thenReturn(testRoom);
        when(roomRepo.findByInviteTokenHash(anyString())).thenReturn(testRoom);
        when(roomRepo.save(any(Room.class))).thenAnswer(i -> i.getArgument(0));
        when(joinRequestRepo.save(any(JoinRequest.class))).thenAnswer(i -> {
            JoinRequest jr = i.getArgument(0);
            jr.setId("req-link-1");
            return jr;
        });
        when(messageRepo.save(any(Message.class))).thenAnswer(i -> {
            Message m = i.getArgument(0);
            m.setId("sys-msg-link");
            return m;
        });

        JoinInviteRequest req = new JoinInviteRequest("valid-test-token", "Priya", "avatar-priya");
        ResponseEntity<?> response = roomController.submitJoinRequest("group-chat", req, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepo).save(messageCaptor.capture());
        Message captured = messageCaptor.getValue();

        assertThat(captured.getMessageType()).isEqualTo("SYSTEM");
        assertThat(captured.getEventType()).isEqualTo("MEMBER_JOINED");
        assertThat(captured.getJoinMethod()).isEqualTo("LINK");
        assertThat(captured.getTargetName()).isEqualTo("Priya");
        assertThat(captured.getContent()).isEqualTo("Priya joined the chat via link");

        verify(messagingTemplate).convertAndSend(eq("/topic/room/group-chat"), any(Object.class));
    }

    @Test
    @DisplayName("4. User joins after admin approval creates system message 'Amit joined the chat'")
    void testAdminApprovalGeneratesSystemMessage() {
        when(roomRepo.findByRoomId("group-chat")).thenReturn(testRoom);
        when(roomRepo.save(any(Room.class))).thenAnswer(i -> i.getArgument(0));

        JoinRequest pendingReq = new JoinRequest("group-chat", "Amit", "avatar-amit", 1, testRoom.getInviteTokenHash());
        pendingReq.setId("req-pending-123");
        pendingReq.setStatus("PENDING");

        when(joinRequestRepo.findById("req-pending-123")).thenReturn(Optional.of(pendingReq));
        when(joinRequestRepo.save(any(JoinRequest.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepo.save(any(Message.class))).thenAnswer(i -> {
            Message m = i.getArgument(0);
            m.setId("sys-msg-approved");
            return m;
        });

        String adminToken = securityService.generateToken("group-chat", "Rajiv", true);

        ResponseEntity<?> response = roomController.approveJoinRequest("group-chat", "req-pending-123", "Bearer " + adminToken, null, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepo).save(messageCaptor.capture());
        Message captured = messageCaptor.getValue();

        assertThat(captured.getMessageType()).isEqualTo("SYSTEM");
        assertThat(captured.getEventType()).isEqualTo("MEMBER_JOINED");
        assertThat(captured.getJoinMethod()).isEqualTo("ADMIN_APPROVAL");
        assertThat(captured.getTargetName()).isEqualTo("Amit");
        assertThat(captured.getActorName()).isEqualTo("Rajiv");
        assertThat(captured.getContent()).isEqualTo("Amit joined the chat");

        verify(messagingTemplate).convertAndSend(eq("/topic/room/group-chat"), any(Object.class));
    }

    @Test
    @DisplayName("5. Voluntarily leaving a room creates system message 'Sajal left the chat'")
    void testVoluntaryLeaveGeneratesSystemMessage() {
        when(roomRepo.findByRoomId("group-chat")).thenReturn(testRoom);
        when(roomRepo.save(any(Room.class))).thenAnswer(i -> i.getArgument(0));
        when(messageRepo.save(any(Message.class))).thenAnswer(i -> {
            Message m = i.getArgument(0);
            m.setId("sys-msg-leave");
            return m;
        });

        String userToken = securityService.generateToken("group-chat", "Sajal", false);

        ResponseEntity<?> response = roomController.leaveRoom("group-chat", "Bearer " + userToken, null, "Sajal");
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify Sajal's active presence is marked disconnected while preserving membership
        assertThat(testRoom.getMembers().stream().filter(m -> m.getUsername().equals("Sajal")).findFirst().get().isConnected()).isFalse();

        ArgumentCaptor<Message> messageCaptor = ArgumentCaptor.forClass(Message.class);
        verify(messageRepo).save(messageCaptor.capture());
        Message captured = messageCaptor.getValue();

        assertThat(captured.getMessageType()).isEqualTo("SYSTEM");
        assertThat(captured.getEventType()).isEqualTo("MEMBER_LEFT");
        assertThat(captured.getTargetName()).isEqualTo("Sajal");
        assertThat(captured.getContent()).isEqualTo("Sajal left the chat");

        verify(messagingTemplate).convertAndSend(eq("/topic/room/group-chat"), any(Object.class));
    }

    @Test
    @DisplayName("6. Failed membership operations do NOT generate system messages")
    void testFailedOperationsDoNotGenerateSystemMessages() {
        when(roomRepo.findByRoomId("group-chat")).thenReturn(testRoom);

        // A. Invalid invite code on join
        JoinRoomRequest badCodeReq = new JoinRoomRequest("group-chat", "Hacker", "wrong-code", "avatar");
        ResponseEntity<?> response = roomController.joinRoomWithCode("group-chat", badCodeReq, null, null, null, null, null, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);

        // B. Non-admin attempting removal
        String memberToken = securityService.generateToken("group-chat", "Sajal", false);
        ResponseEntity<?> removeResponse = roomController.removeMember("group-chat", "Rajiv", "Bearer " + memberToken, null);
        assertThat(removeResponse.getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);

        // C. Non-member leaving again
        String nonMemberToken = securityService.generateToken("group-chat", "GhostUser", false);
        ResponseEntity<?> leaveResponse = roomController.leaveRoom("group-chat", "Bearer " + nonMemberToken, null, "GhostUser");
        assertThat(leaveResponse.getStatusCode()).isEqualTo(HttpStatus.OK);

        // Verify messageRepo.save was NEVER called for failed operations
        verify(messageRepo, never()).save(any(Message.class));
    }

    @Test
    @DisplayName("7. Chronological ordering & backward compatibility: older messages without messageType default to USER")
    void testChronologicalOrderingAndLegacyBackwardCompatibility() {
        Date now = new Date();
        Message legacyUserMsg = new Message("1", "group-chat", "Rajiv", "Hello team", new Date(now.getTime() - 5000), new Date(now.getTime() + 86400000));
        legacyUserMsg.setMessageType(null); // Simulate legacy DB record without messageType field

        Message systemMsg = new Message("2", "group-chat", "SYSTEM", "Vikram joined the chat via code", new Date(now.getTime() - 2000), new Date(now.getTime() + 86400000));
        systemMsg.setMessageType("SYSTEM");

        Message replyMsg = new Message("3", "group-chat", "Vikram", "Glad to be here!", now, new Date(now.getTime() + 86400000));
        replyMsg.setMessageType("USER");

        when(messageRepo.findByRoomIdOrderByTimeStampAsc("group-chat")).thenReturn(List.of(legacyUserMsg, systemMsg, replyMsg));

        List<Message> history = messageService.getValidMessagesForRoom("group-chat");

        assertThat(history).hasSize(3);
        // Verify chronological ordering
        assertThat(history.get(0).getContent()).isEqualTo("Hello team");
        assertThat(history.get(1).getContent()).isEqualTo("Vikram joined the chat via code");
        assertThat(history.get(2).getContent()).isEqualTo("Glad to be here!");

        // Verify backward compatibility
        assertThat(history.get(0).getMessageType()).isEqualTo("USER");
        assertThat(history.get(1).getMessageType()).isEqualTo("SYSTEM");
        assertThat(history.get(2).getMessageType()).isEqualTo("USER");
    }

    @Test
    @DisplayName("8. Reconnecting active members do NOT trigger duplicate join events")
    void testReconnectingMembersDoNotTriggerDuplicateJoinEvents() {
        when(roomRepo.findByRoomId("group-chat")).thenReturn(testRoom);
        when(roomRepo.save(any(Room.class))).thenAnswer(i -> i.getArgument(0));

        // Sajal is already an active member in testRoom
        JoinRoomRequest reconnectReq = new JoinRoomRequest("group-chat", "Sajal", "123456", "avatar2");
        ResponseEntity<?> response = roomController.joinRoomWithCode("group-chat", reconnectReq, null, null, null, null, null, null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        // No system message should be generated because Sajal was already a member
        verify(messageRepo, never()).save(any(Message.class));
    }
}
