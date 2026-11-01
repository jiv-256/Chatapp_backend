package com.substring.chat.New_Chatapp_backend.services;

import com.substring.chat.New_Chatapp_backend.entities.JoinRequest;
import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.entities.RoomMember;
import com.substring.chat.New_Chatapp_backend.exceptions.RemovedMemberException;
import com.substring.chat.New_Chatapp_backend.exceptions.UnauthorizedAdminException;
import com.substring.chat.New_Chatapp_backend.repo.JoinRequestRepo;
import com.substring.chat.New_Chatapp_backend.repo.MessageRepo;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import com.substring.chat.New_Chatapp_backend.security.RoomSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomInvitationAndApprovalTest {

    @Mock
    private RoomRepo roomRepo;

    @Mock
    private MessageRepo messageRepo;

    @Mock
    private JoinRequestRepo joinRequestRepo;

    private RoomSecurityService securityService;
    private MessageService messageService;
    private RoomService roomService;

    @BeforeEach
    void setUp() {
        securityService = new RoomSecurityService("TestSecretKey_2026_SecureAuthTestKey_123456789");
        messageService = new MessageService(messageRepo, roomRepo);
        roomService = new RoomService(roomRepo, messageService, securityService, joinRequestRepo);
    }

    @Test
    void testCreateRoomGeneratesOpaqueInviteTokenAndPersistsAvatar() {
        when(roomRepo.findByRoomId("team-room")).thenReturn(null);
        when(roomRepo.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        Room room = roomService.createRoom("team-room", "Alice", "Team Room", null, "avatar_seed_123");

        assertThat(room).isNotNull();
        assertThat(room.getAdmin()).isEqualTo("Alice");
        assertThat(room.getInvitationVersion()).isEqualTo(1);
        assertThat(room.getInviteTokenHash()).isNotNull().hasSize(64);
        assertThat(room.getEncryptedInviteToken()).isNotNull();

        assertThat(room.getMembers()).hasSize(1);
        RoomMember adminMember = room.getMembers().get(0);
        assertThat(adminMember.getUsername()).isEqualTo("Alice");
        assertThat(adminMember.getRole()).isEqualTo("ADMIN");
        assertThat(adminMember.getAvatar()).isEqualTo("avatar_seed_123");
    }

    @Test
    void testValidateInviteTokenReturnsRoomWhenValid() {
        String token = securityService.generateInviteToken();
        String tokenHash = securityService.hashInviteToken(token);

        Room room = new Room();
        room.setRoomId("alpha-room");
        room.setAdmin("Alice");
        room.setInviteTokenHash(tokenHash);
        room.setLastActivityAt(new Date());

        when(roomRepo.findByInviteTokenHash(tokenHash)).thenReturn(room);

        Room validated = roomService.validateInviteToken(token);
        assertThat(validated).isNotNull();
        assertThat(validated.getRoomId()).isEqualTo("alpha-room");
    }

    @Test
    void testValidateInviteTokenThrowsWhenInvalidOrRotated() {
        String invalidToken = "completely_invalid_or_old_token";
        String tokenHash = securityService.hashInviteToken(invalidToken);
        when(roomRepo.findByInviteTokenHash(tokenHash)).thenReturn(null);

        assertThatThrownBy(() -> roomService.validateInviteToken(invalidToken))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid or expired invitation link");
    }

    @Test
    void testGuestSubmittingJoinRequestCreatesPendingRequest() {
        String token = securityService.generateInviteToken();
        String tokenHash = securityService.hashInviteToken(token);

        Room room = new Room();
        room.setRoomId("cool-room");
        room.setAdmin("Bob");
        room.setInvitationVersion(1);
        room.setInviteTokenHash(tokenHash);
        room.setLastActivityAt(new Date());

        when(roomRepo.findByInviteTokenHash(tokenHash)).thenReturn(room);
        when(joinRequestRepo.findFirstByRoomIdAndUserNameAndStatus("cool-room", "Charlie", "PENDING")).thenReturn(Optional.empty());
        when(joinRequestRepo.save(any(JoinRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        JoinRequest request = roomService.createJoinRequest(token, "Charlie", "charlie_avatar");

        assertThat(request).isNotNull();
        assertThat(request.getUserName()).isEqualTo("Charlie");
        assertThat(request.getAvatar()).isEqualTo("charlie_avatar");
        assertThat(request.getRoomId()).isEqualTo("cool-room");
        assertThat(request.getStatus()).isEqualTo("PENDING");
        assertThat(request.getInvitationVersion()).isEqualTo(1);
    }

    @Test
    void testGuestSubmittingJoinRequestFailsIfMemberWasRemoved() {
        String token = securityService.generateInviteToken();
        String tokenHash = securityService.hashInviteToken(token);

        Room room = new Room();
        room.setRoomId("cool-room");
        room.setAdmin("Bob");
        room.setInvitationVersion(1);
        room.setInviteTokenHash(tokenHash);
        room.setLastActivityAt(new Date());
        room.getRemovedMembers().add("malicious_user");

        when(roomRepo.findByInviteTokenHash(tokenHash)).thenReturn(room);

        assertThatThrownBy(() -> roomService.createJoinRequest(token, "Malicious_User", "avatar1"))
                .isInstanceOf(RemovedMemberException.class)
                .hasMessageContaining("You have been removed from this room");
    }

    @Test
    void testAdminApproveJoinRequestGrantsMembershipAndToken() {
        Room room = new Room();
        room.setRoomId("project-room");
        room.setAdmin("Alice");
        room.setInvitationVersion(1);
        room.getMembers().add(new RoomMember("Alice", "ADMIN", "alice_avatar"));

        JoinRequest req = new JoinRequest("project-room", "Dave", "dave_avatar", 1, "hash123");
        req.setId("req-456");
        req.setStatus("PENDING");

        when(roomRepo.findByRoomId("project-room")).thenReturn(room);
        when(joinRequestRepo.findById("req-456")).thenReturn(Optional.of(req));
        when(joinRequestRepo.save(any(JoinRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roomRepo.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        JoinRequest approved = roomService.approveJoinRequest("project-room", "req-456", "Alice");

        assertThat(approved.getStatus()).isEqualTo("APPROVED");
        assertThat(approved.getApprovedToken()).isNotNull();

        // Check room member list updated
        assertThat(room.getMembers()).hasSize(2);
        RoomMember dave = room.getMembers().stream().filter(m -> m.getUsername().equals("Dave")).findFirst().orElse(null);
        assertThat(dave).isNotNull();
        assertThat(dave.getRole()).isEqualTo("MEMBER");
        assertThat(dave.getAvatar()).isEqualTo("dave_avatar");
    }

    @Test
    void testAdminRejectJoinRequestMarksStatusRejected() {
        Room room = new Room();
        room.setRoomId("project-room");
        room.setAdmin("Alice");

        JoinRequest req = new JoinRequest("project-room", "Eve", "eve_avatar", 1, "hash123");
        req.setId("req-789");
        req.setStatus("PENDING");

        when(roomRepo.findByRoomId("project-room")).thenReturn(room);
        when(joinRequestRepo.findById("req-789")).thenReturn(Optional.of(req));
        when(joinRequestRepo.save(any(JoinRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        JoinRequest rejected = roomService.rejectJoinRequest("project-room", "req-789", "Alice");

        assertThat(rejected.getStatus()).isEqualTo("REJECTED");
        assertThat(rejected.getApprovedToken()).isNull();
    }

    @Test
    void testNonAdminCannotApproveOrRejectJoinRequest() {
        Room room = new Room();
        room.setRoomId("project-room");
        room.setAdmin("Alice");

        when(roomRepo.findByRoomId("project-room")).thenReturn(room);

        assertThatThrownBy(() -> roomService.approveJoinRequest("project-room", "req-1", "Hacker"))
                .isInstanceOf(UnauthorizedAdminException.class);

        assertThatThrownBy(() -> roomService.rejectJoinRequest("project-room", "req-1", "Hacker"))
                .isInstanceOf(UnauthorizedAdminException.class);
    }

    @Test
    void testMemberRemovalRotatesInviteTokenAndVersion() {
        Room room = new Room();
        room.setRoomId("team-room");
        room.setAdmin("Alice");
        room.setInvitationVersion(1);
        room.setInviteTokenHash("old_token_hash");
        room.setEncryptedInviteToken(securityService.encryptInviteCode("old_token"));
        room.getMembers().add(new RoomMember("Alice", "ADMIN"));
        room.getMembers().add(new RoomMember("Bob", "MEMBER"));

        when(roomRepo.findByRoomId("team-room")).thenReturn(room);
        when(roomRepo.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(joinRequestRepo.findByRoomIdAndStatus("team-room", "PENDING")).thenReturn(Collections.emptyList());

        roomService.removeMember("team-room", "Alice", "Bob");

        // Verify Bob is removed and recorded in removedMembers
        assertThat(room.getMembers()).hasSize(1);
        assertThat(room.isMember("Bob")).isFalse();
        assertThat(room.isRemoved("Bob")).isTrue();

        // Verify invitation version incremented and token rotated
        assertThat(room.getInvitationVersion()).isEqualTo(2);
        assertThat(room.getInviteTokenHash()).isNotEqualTo("old_token_hash");
    }

    @Test
    void testPendingRequestWithOlderInvitationVersionCannotBeApproved() {
        Room room = new Room();
        room.setRoomId("team-room");
        room.setAdmin("Alice");
        room.setInvitationVersion(2); // Link has rotated to v2!

        JoinRequest staleReq = new JoinRequest("team-room", "Frank", "frank_avatar", 1, "old_hash"); // Made with v1
        staleReq.setId("stale-1");
        staleReq.setStatus("PENDING");

        when(roomRepo.findByRoomId("team-room")).thenReturn(room);
        when(joinRequestRepo.findById("stale-1")).thenReturn(Optional.of(staleReq));
        when(joinRequestRepo.save(any(JoinRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> roomService.approveJoinRequest("team-room", "stale-1", "Alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("invitation link has expired");

        assertThat(staleReq.getStatus()).isEqualTo("EXPIRED");
    }

    @Test
    void testDirectJoinViaLinkBypassesApprovalWhenConfigured() {
        String token = securityService.generateInviteToken();
        String tokenHash = securityService.hashInviteToken(token);

        Room room = new Room();
        room.setRoomId("open-room");
        room.setAdmin("Alice");
        room.setInvitationVersion(1);
        room.setInviteTokenHash(tokenHash);
        room.setAdminApprovalRequired(false); // Direct Join enabled!

        when(roomRepo.findByInviteTokenHash(tokenHash)).thenReturn(room);
        when(roomRepo.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));
        when(joinRequestRepo.save(any(JoinRequest.class))).thenAnswer(inv -> inv.getArgument(0));

        JoinRequest directReq = roomService.createJoinRequest(token, "Sam", "sam_avatar");

        assertThat(directReq).isNotNull();
        assertThat(directReq.getStatus()).isEqualTo("APPROVED");
        assertThat(directReq.getApprovedToken()).isNotNull();
        assertThat(room.isMember("Sam")).isTrue();
    }

    @Test
    void testAdminCanUpdateInvitationMode() {
        Room room = new Room();
        room.setRoomId("mode-room");
        room.setAdmin("Alice");
        room.setAdminApprovalRequired(true);

        when(roomRepo.findByRoomId("mode-room")).thenReturn(room);
        when(roomRepo.save(any(Room.class))).thenAnswer(inv -> inv.getArgument(0));

        Room updated = roomService.updateInvitationMode("mode-room", "Alice", false);
        assertThat(updated.isAdminApprovalRequired()).isFalse();

        Room backToApproval = roomService.updateInvitationMode("mode-room", "Alice", true);
        assertThat(backToApproval.isAdminApprovalRequired()).isTrue();
    }

    @Test
    void testNonAdminCannotUpdateInvitationMode() {
        Room room = new Room();
        room.setRoomId("mode-room");
        room.setAdmin("Alice");

        when(roomRepo.findByRoomId("mode-room")).thenReturn(room);

        assertThatThrownBy(() -> roomService.updateInvitationMode("mode-room", "Eve", false))
                .isInstanceOf(UnauthorizedAdminException.class)
                .hasMessageContaining("Only the room Admin can modify invitation settings");
    }
}
