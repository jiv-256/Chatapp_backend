package com.substring.chat.New_Chatapp_backend.controllers;

import com.substring.chat.New_Chatapp_backend.entities.Message;
import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.exceptions.InvalidRetentionException;
import com.substring.chat.New_Chatapp_backend.exceptions.UnauthorizedAdminException;
import com.substring.chat.New_Chatapp_backend.services.InactiveRoomCleanupService;
import com.substring.chat.New_Chatapp_backend.services.MessageService;
import com.substring.chat.New_Chatapp_backend.services.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Date;
import java.util.List;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@ExtendWith(MockitoExtension.class)
class RoomControllerIntegrationTest {

    private MockMvc mockMvc;

    @Mock
    private RoomService roomService;

    @Mock
    private MessageService messageService;

    @Mock
    private InactiveRoomCleanupService inactiveRoomCleanupService;

    @Mock
    private org.springframework.messaging.simp.SimpMessagingTemplate messagingTemplate;

    @Mock
    private com.substring.chat.New_Chatapp_backend.security.WebSocketSessionRegistry webSocketSessionRegistry;

    private com.substring.chat.New_Chatapp_backend.security.RoomSecurityService roomSecurityService;

    @BeforeEach
    void setUp() {
        roomSecurityService = new com.substring.chat.New_Chatapp_backend.security.RoomSecurityService("TestSecretKey_1234567890_TestSecretKey_1234567890");
        RoomController controller = new RoomController(roomService, messageService, inactiveRoomCleanupService, roomSecurityService, webSocketSessionRegistry);
        org.springframework.test.util.ReflectionTestUtils.setField(controller, "messagingTemplate", messagingTemplate);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void testCreateRoomSetsAdmin() throws Exception {
        Room room = new Room();
        room.setRoomId("java-devs");
        room.setAdmin("JohnDoe");
        room.setEncryptedInviteCode(roomSecurityService.encryptInviteCode("ABCD-1234-EFGH"));
        room.setCustomDeletionTimeEnabled(false);
        room.setCustomDeletionAfterSeconds(604800L);

        when(roomService.createRoom(eq("java-devs"), eq("JohnDoe"))).thenReturn(room);

        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\": \"java-devs\", \"creator\": \"JohnDoe\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value("java-devs"))
                .andExpect(jsonPath("$.admin").value("JohnDoe"))
                .andExpect(jsonPath("$.customDeletionTimeEnabled").value(false));
    }

    @Test
    void testNonAdminReceivesHttp403Forbidden() throws Exception {
        when(roomService.updateRetentionSettings(eq("java-devs"), eq("Mallory"), any(), any()))
                .thenThrow(new UnauthorizedAdminException("Only the room Admin can modify deletion settings"));

        mockMvc.perform(put("/api/v1/rooms/java-devs/retention")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Name", "Mallory")
                        .content("{\"customDeletionTimeEnabled\": true, \"customDeletionAfterSeconds\": 300, \"userName\": \"Mallory\"}"))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Only the room Admin can modify deletion settings"));
    }

    @Test
    void testAdminCanUpdateRetentionSettings() throws Exception {
        Room updated = new Room();
        updated.setRoomId("java-devs");
        updated.setAdmin("JohnDoe");
        updated.setCustomDeletionTimeEnabled(true);
        updated.setCustomDeletionAfterSeconds(300L);

        when(roomService.updateRetentionSettings(eq("java-devs"), eq("JohnDoe"), eq(true), eq(300L)))
                .thenReturn(updated);

        mockMvc.perform(put("/api/v1/rooms/java-devs/retention")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Name", "JohnDoe")
                        .content("{\"customDeletionTimeEnabled\": true, \"customDeletionAfterSeconds\": 300, \"userName\": \"JohnDoe\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customDeletionTimeEnabled").value(true))
                .andExpect(jsonPath("$.customDeletionAfterSeconds").value(300));
    }

    @Test
    void testInvalidRetentionDurationReturnsHttp400BadRequest() throws Exception {
        when(roomService.updateRetentionSettings(eq("java-devs"), eq("JohnDoe"), eq(true), eq(999L)))
                .thenThrow(new InvalidRetentionException("Invalid retention duration: 999"));

        mockMvc.perform(put("/api/v1/rooms/java-devs/retention")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-User-Name", "JohnDoe")
                        .content("{\"customDeletionTimeEnabled\": true, \"customDeletionAfterSeconds\": 999, \"userName\": \"JohnDoe\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid retention duration: 999"));
    }

    @Test
    void testGetMessagesReturnsValidMessages() throws Exception {
        Room room = new Room();
        room.setRoomId("java-devs");
        room.getMembers().add(new com.substring.chat.New_Chatapp_backend.entities.RoomMember("Alice", "MEMBER"));
        when(roomService.getRoom("java-devs")).thenReturn(room);

        Message msg = new Message("101", "java-devs", "Alice", "Hello", new Date(), new Date(System.currentTimeMillis() + 60000));
        when(messageService.getValidMessagesForRoom("java-devs")).thenReturn(List.of(msg));

        String token = roomSecurityService.generateToken("java-devs", "Alice", false);

        mockMvc.perform(get("/api/v1/rooms/java-devs/messages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].content").value("Hello"))
                .andExpect(jsonPath("$[0].sender").value("Alice"));
    }

    @Test
    void testGetMessagesWithoutTokenReturnsUnauthorized() throws Exception {
        Room room = new Room();
        room.setRoomId("java-devs");
        when(roomService.getRoom("java-devs")).thenReturn(room);

        mockMvc.perform(get("/api/v1/rooms/java-devs/messages"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().string("Missing authentication token"));
    }

    @Test
    void testGetMessagesForRemovedUserReturnsForbidden() throws Exception {
        Room room = new Room();
        room.setRoomId("java-devs");
        room.getRemovedMembers().add("mallory");
        when(roomService.getRoom("java-devs")).thenReturn(room);

        String token = roomSecurityService.generateToken("java-devs", "Mallory", false);

        mockMvc.perform(get("/api/v1/rooms/java-devs/messages")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().string("You have been removed from this room."));
    }

    @Test
    void testAdminCanViewInviteCodeWithValidToken() throws Exception {
        String adminToken = roomSecurityService.generateToken("java-devs", "JohnDoe", true);
        when(roomService.getInviteCode("java-devs", "JohnDoe")).thenReturn("K8P2-9F4A-M3X7");

        mockMvc.perform(get("/api/v1/rooms/java-devs/invite-code")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value("java-devs"))
                .andExpect(jsonPath("$.inviteCode").value("K8P2-9F4A-M3X7"));
    }

    @Test
    void testNonAdminCannotViewInviteCode() throws Exception {
        String bobToken = roomSecurityService.generateToken("java-devs", "Bob", false);
        when(roomService.getInviteCode("java-devs", "Bob"))
                .thenThrow(new UnauthorizedAdminException("Only the room Admin can view the invitation code"));

        mockMvc.perform(get("/api/v1/rooms/java-devs/invite-code")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Only the room Admin can view the invitation code"));
    }

    @Test
    void testAdminCanRemoveMemberAndRotatesCode() throws Exception {
        String adminToken = roomSecurityService.generateToken("java-devs", "JohnDoe", true);
        when(roomService.removeMember("java-devs", "JohnDoe", "Bob")).thenReturn("NEW-CODE-9999");

        mockMvc.perform(delete("/api/v1/rooms/java-devs/members/Bob")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.newInviteCode").value("NEW-CODE-9999"));

        // Verify notification is sent ONLY to the affected member
        org.mockito.Mockito.verify(messagingTemplate).convertAndSend(
                eq("/topic/room/java-devs/user/bob"),
                any(Object.class)
        );
        // Verify member update is sent to room
        org.mockito.Mockito.verify(messagingTemplate).convertAndSend(
                eq("/topic/room/java-devs/members"),
                any(Object.class)
        );
        // Verify active WebSocket session is closed
        org.mockito.Mockito.verify(webSocketSessionRegistry).closeSessionsForUser("java-devs", "Bob");
    }

    @Test
    void testRemovedMemberCannotAccessRoomMetadataViaGet() throws Exception {
        Room room = new Room();
        room.setRoomId("java-devs");
        room.setAdmin("JohnDoe");
        room.getRemovedMembers().add("bob");

        when(roomService.getRoom("java-devs")).thenReturn(room);

        String bobToken = roomSecurityService.generateToken("java-devs", "Bob", false);

        mockMvc.perform(get("/api/v1/rooms/java-devs")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden())
                .andExpect(content().string("You have been removed from this room."));
    }

    @Test
    void testNonAdminCannotRemoveMember() throws Exception {
        String bobToken = roomSecurityService.generateToken("java-devs", "Bob", false);
        when(roomService.removeMember("java-devs", "Bob", "Charlie"))
                .thenThrow(new UnauthorizedAdminException("Only the room Admin can remove members"));

        mockMvc.perform(delete("/api/v1/rooms/java-devs/members/Charlie")
                        .header("Authorization", "Bearer " + bobToken))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Only the room Admin can remove members"));
    }

    @Test
    void testJoinRoomWithValidCodeReturnsAuthResponse() throws Exception {
        when(roomService.joinRoom(eq("java-devs"), eq("Bob"), eq("ABCD-1234-EFGH")))
                .thenReturn(new com.substring.chat.New_Chatapp_backend.payload.AuthResponse("java-devs", "java-devs", "Bob", false, "token123", null));

        mockMvc.perform(post("/api/v1/rooms/java-devs/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\": \"java-devs\", \"userName\": \"Bob\", \"inviteCode\": \"ABCD-1234-EFGH\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value("java-devs"))
                .andExpect(jsonPath("$.userName").value("Bob"))
                .andExpect(jsonPath("$.admin").value(false))
                .andExpect(jsonPath("$.token").value("token123"));
    }

    @Test
    void testJoinRoomWithInvalidCodeReturnsBadRequest() throws Exception {
        when(roomService.joinRoom(eq("java-devs"), eq("Bob"), eq("WRONG-CODE")))
                .thenThrow(new com.substring.chat.New_Chatapp_backend.exceptions.InvalidInviteCodeException("Invalid room ID or invitation code."));

        mockMvc.perform(post("/api/v1/rooms/java-devs/join")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\": \"java-devs\", \"userName\": \"Bob\", \"inviteCode\": \"WRONG-CODE\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid room ID or invitation code."));
    }

    @Test
    void testCreateRoomOnlyRequiresRoomIdAndUsesRoomIdForDisplay() throws Exception {
        Room room = new Room();
        room.setRoomId("frontend-team");
        room.setAdmin("Sarah");
        room.setEncryptedInviteCode(roomSecurityService.encryptInviteCode("SEC1-2345-CODE"));

        when(roomService.createRoom(eq("frontend-team"), eq("Sarah"))).thenReturn(room);

        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\": \"frontend-team\", \"creator\": \"Sarah\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value("frontend-team"))
                .andExpect(jsonPath("$.roomName").value("frontend-team"))
                .andExpect(jsonPath("$.creator").value("Sarah"));
    }

    @Test
    void testExistingRoomWithLegacyRoomNameUsesRoomIdForDisplayInMetadata() throws Exception {
        Room legacyRoom = new Room();
        legacyRoom.setRoomId("legacy-101");
        legacyRoom.setRoomName("Legacy Custom Name");
        legacyRoom.setAdmin("AdminUser");

        when(roomService.getRoom("legacy-101")).thenReturn(legacyRoom);

        mockMvc.perform(get("/api/v1/rooms/legacy-101"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value("legacy-101"))
                .andExpect(jsonPath("$.roomName").value("legacy-101"))
                .andExpect(jsonPath("$.admin").value("AdminUser"));
    }

    @Test
    void testGenerateInviteCodeEndpointReturnsCodeAndTicket() throws Exception {
        when(roomService.getRoom("fresh-room")).thenReturn(null);

        mockMvc.perform(post("/api/v1/rooms/generate-invite-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\": \"fresh-room\", \"creator\": \"Diana\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value("fresh-room"))
                .andExpect(jsonPath("$.inviteCode").isNotEmpty())
                .andExpect(jsonPath("$.codeTicket").isNotEmpty());
    }

    @Test
    void testGenerateInviteCodeRejectsExistingRoom() throws Exception {
        Room existing = new Room();
        existing.setRoomId("already-taken");
        when(roomService.getRoom("already-taken")).thenReturn(existing);

        mockMvc.perform(post("/api/v1/rooms/generate-invite-code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\": \"already-taken\", \"creator\": \"Diana\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Room already exists! Please choose another Room ID."));
    }

    @Test
    void testCreateRoomWithValidCodeTicketSetsVerifiedInviteCode() throws Exception {
        String code = "SEC2-9999-ABCD";
        String ticket = roomSecurityService.generateCodeTicket("ticket-room", "Dave", code);

        Room room = new Room();
        room.setRoomId("ticket-room");
        room.setAdmin("Dave");
        room.setEncryptedInviteCode(roomSecurityService.encryptInviteCode(code));

        when(roomService.createRoom(eq("ticket-room"), eq("Dave"), isNull(), eq(code))).thenReturn(room);

        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\": \"ticket-room\", \"creator\": \"Dave\", \"codeTicket\": \"" + ticket + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.roomId").value("ticket-room"))
                .andExpect(jsonPath("$.admin").value("Dave"))
                .andExpect(jsonPath("$.inviteCode").value(code));
    }

    @Test
    void testCreateRoomWithTamperedCodeTicketReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/v1/rooms")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"roomId\": \"ticket-room\", \"creator\": \"Dave\", \"codeTicket\": \"tampered.ticket\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().string("Invalid or expired invitation code ticket!"));
    }

    @Test
    void testValidateInviteTokenReturnsValid() throws Exception {
        Room room = new Room();
        room.setRoomId("test-room");
        room.setInvitationVersion(1);
        when(roomService.validateInviteToken("valid-token-123")).thenReturn(room);

        mockMvc.perform(get("/api/v1/rooms/invitations/valid-token-123/validate"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.valid").value(true))
                .andExpect(jsonPath("$.roomId").value("test-room"));
    }

    @Test
    void testValidateInviteTokenReturnsGoneForInvalid() throws Exception {
        when(roomService.validateInviteToken("invalid-token-456"))
                .thenThrow(new IllegalArgumentException("Invalid or expired invitation link. Please ask the room admin for a new invitation."));

        mockMvc.perform(get("/api/v1/rooms/invitations/invalid-token-456/validate"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.valid").value(false))
                .andExpect(jsonPath("$.message").value("Invalid or expired invitation link. Please ask the room admin for a new invitation."));
    }

    @Test
    void testSubmitJoinRequestPending() throws Exception {
        com.substring.chat.New_Chatapp_backend.entities.JoinRequest request =
                new com.substring.chat.New_Chatapp_backend.entities.JoinRequest();
        request.setId("req-1");
        request.setRoomId("test-room");
        request.setUserName("Alice");
        request.setAvatar("Felix");
        request.setStatus("PENDING");

        when(roomService.createJoinRequest("token-123", "Alice", "Felix")).thenReturn(request);

        mockMvc.perform(post("/api/v1/rooms/test-room/join-requests")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\": \"token-123\", \"userName\": \"Alice\", \"avatar\": \"Felix\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.requestId").value("req-1"))
                .andExpect(jsonPath("$.userName").value("Alice"));
    }

    @Test
    void testApproveJoinRequestAsAdmin() throws Exception {
        com.substring.chat.New_Chatapp_backend.entities.JoinRequest approved =
                new com.substring.chat.New_Chatapp_backend.entities.JoinRequest();
        approved.setId("req-1");
        approved.setRoomId("test-room");
        approved.setUserName("Alice");
        approved.setAvatar("Felix");
        approved.setStatus("APPROVED");
        approved.setApprovedToken("jwt-token-xyz");

        when(roomService.approveJoinRequest("test-room", "req-1", "AdminUser")).thenReturn(approved);

        mockMvc.perform(post("/api/v1/rooms/test-room/join-requests/req-1/approve")
                        .header("X-User-Name", "AdminUser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"))
                .andExpect(jsonPath("$.approvedToken").value("jwt-token-xyz"));
    }

    @Test
    void testRejectJoinRequestAsAdmin() throws Exception {
        com.substring.chat.New_Chatapp_backend.entities.JoinRequest rejected =
                new com.substring.chat.New_Chatapp_backend.entities.JoinRequest();
        rejected.setId("req-1");
        rejected.setRoomId("test-room");
        rejected.setUserName("Alice");
        rejected.setStatus("REJECTED");

        when(roomService.rejectJoinRequest("test-room", "req-1", "AdminUser")).thenReturn(rejected);

        mockMvc.perform(post("/api/v1/rooms/test-room/join-requests/req-1/reject")
                        .header("X-User-Name", "AdminUser"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"));
    }

    @Test
    void testUpdateInvitationModeAdmin() throws Exception {
        Room room = new Room();
        room.setRoomId("test-room");
        room.setAdmin("AdminUser");
        room.setAdminApprovalRequired(false);

        when(roomService.updateInvitationMode(eq("test-room"), eq("AdminUser"), eq(false))).thenReturn(room);

        mockMvc.perform(put("/api/v1/rooms/test-room/invitation-mode")
                        .header("X-User-Name", "AdminUser")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"adminApprovalRequired\": false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.adminApprovalRequired").value(false));
    }
}
