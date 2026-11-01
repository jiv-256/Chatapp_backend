package com.substring.chat.New_Chatapp_backend.services;

import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.entities.RoomMember;
import com.substring.chat.New_Chatapp_backend.exceptions.InvalidInviteCodeException;
import com.substring.chat.New_Chatapp_backend.exceptions.RemovedMemberException;
import com.substring.chat.New_Chatapp_backend.exceptions.UnauthorizedAdminException;
import com.substring.chat.New_Chatapp_backend.payload.AuthResponse;
import com.substring.chat.New_Chatapp_backend.repo.MessageRepo;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import com.substring.chat.New_Chatapp_backend.security.RoomSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomAuthenticationAndMemberManagementTest {

    @Mock
    private RoomRepo roomRepo;

    @Mock
    private MessageRepo messageRepo;

    private RoomSecurityService securityService;
    private MessageService messageService;
    private RoomService roomService;

    @BeforeEach
    void setUp() {
        securityService = new RoomSecurityService("TestSecretKey_2026_SecureAuthTestKey_123456789");
        messageService = new MessageService(messageRepo, roomRepo);
        roomService = new RoomService(roomRepo, messageService, securityService);
    }

    @Test
    void testRoomCreationGeneratesSecureInviteCodeAndAdminMembership() {
        when(roomRepo.findByRoomId("dev-room")).thenReturn(null);
        when(roomRepo.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Room created = roomService.createRoom("dev-room", "Alice");

        assertThat(created).isNotNull();
        assertThat(created.getRoomId()).isEqualTo("dev-room");
        assertThat(created.getAdmin()).isEqualTo("Alice");

        // Invite code is hashed and encrypted; plaintext is NOT stored on the room entity
        assertThat(created.getInviteCodeHash()).isNotNull().hasSize(64);
        assertThat(created.getEncryptedInviteCode()).isNotNull();

        // Creator is automatically registered as first member with ADMIN role
        assertThat(created.getMembers()).hasSize(1);
        RoomMember firstMember = created.getMembers().get(0);
        assertThat(firstMember.getUsername()).isEqualTo("Alice");
        assertThat(firstMember.getRole()).isEqualTo("ADMIN");
        assertThat(firstMember.isConnected()).isTrue();
    }

    @Test
    void testValidInviteCodePermitsJoiningAndAddsToMembers() {
        String plainCode = "ABCD-1234-EFGH";
        Room room = new Room();
        room.setRoomId("dev-room");
        room.setAdmin("Alice");
        room.setInviteCodeHash(securityService.hashInviteCode(plainCode));
        room.setEncryptedInviteCode(securityService.encryptInviteCode(plainCode));
        room.setMembers(new ArrayList<>(List.of(new RoomMember("Alice", "ADMIN"))));
        room.setRemovedMembers(new HashSet<>());

        when(roomRepo.findByRoomId("dev-room")).thenReturn(room);
        when(roomRepo.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse auth = roomService.joinRoom("dev-room", "Bob", plainCode);

        assertThat(auth).isNotNull();
        assertThat(auth.getUserName()).isEqualTo("Bob");
        assertThat(auth.isAdmin()).isFalse();
        assertThat(auth.getToken()).isNotNull();
        // Invite code is null for non-admin to prevent exposure
        assertThat(auth.getInviteCode()).isNull();

        // Bob is now in the persistent member list
        assertThat(room.getMembers()).hasSize(2);
        assertThat(room.isMember("Bob")).isTrue();
    }

    @Test
    void testInvalidInviteCodeIsRejected() {
        String plainCode = "ABCD-1234-EFGH";
        Room room = new Room();
        room.setRoomId("dev-room");
        room.setAdmin("Alice");
        room.setInviteCodeHash(securityService.hashInviteCode(plainCode));
        room.setEncryptedInviteCode(securityService.encryptInviteCode(plainCode));
        room.setMembers(new ArrayList<>(List.of(new RoomMember("Alice", "ADMIN"))));

        when(roomRepo.findByRoomId("dev-room")).thenReturn(room);

        // Incorrect code
        assertThatThrownBy(() -> roomService.joinRoom("dev-room", "Mallory", "WRONG-CODE-9999"))
                .isInstanceOf(InvalidInviteCodeException.class)
                .hasMessageContaining("Invalid room ID or invitation code");

        // Empty code
        assertThatThrownBy(() -> roomService.joinRoom("dev-room", "Mallory", ""))
                .isInstanceOf(InvalidInviteCodeException.class);

        verify(roomRepo, never()).save(any());
    }

    @Test
    void testReconnectingMemberUpdatesConnectionStatusWithoutDuplicates() {
        String plainCode = "ABCD-1234-EFGH";
        RoomMember bobMember = new RoomMember("Bob", "MEMBER", new Date(System.currentTimeMillis() - 10000), false, new Date());
        Room room = new Room();
        room.setRoomId("dev-room");
        room.setAdmin("Alice");
        room.setInviteCodeHash(securityService.hashInviteCode(plainCode));
        room.setEncryptedInviteCode(securityService.encryptInviteCode(plainCode));
        room.setMembers(new ArrayList<>(List.of(new RoomMember("Alice", "ADMIN"), bobMember)));
        room.setRemovedMembers(new HashSet<>());

        when(roomRepo.findByRoomId("dev-room")).thenReturn(room);
        when(roomRepo.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse auth = roomService.joinRoom("dev-room", "Bob", plainCode);

        assertThat(auth).isNotNull();
        // Still only 2 members (Alice and Bob), Bob is marked connected
        assertThat(room.getMembers()).hasSize(2);
        assertThat(bobMember.isConnected()).isTrue();
    }

    @Test
    void testOnlyAdminCanViewCurrentInvitationCode() {
        String plainCode = "ABCD-1234-EFGH";
        Room room = new Room();
        room.setRoomId("dev-room");
        room.setAdmin("Alice");
        room.setEncryptedInviteCode(securityService.encryptInviteCode(plainCode));

        when(roomRepo.findByRoomId("dev-room")).thenReturn(room);

        // Alice (Admin) can retrieve code
        String retrieved = roomService.getInviteCode("dev-room", "Alice");
        assertThat(retrieved).isEqualTo(plainCode);

        // Bob (non-Admin) is rejected
        assertThatThrownBy(() -> roomService.getInviteCode("dev-room", "Bob"))
                .isInstanceOf(UnauthorizedAdminException.class)
                .hasMessageContaining("Only the room Admin can view the invitation code");
    }

    @Test
    void testAdminRemovingMemberRevokesAccessAndRotatesCode() {
        String oldCode = "OLDD-1111-CODE";
        Room room = new Room();
        room.setRoomId("dev-room");
        room.setAdmin("Alice");
        room.setInviteCodeHash(securityService.hashInviteCode(oldCode));
        room.setEncryptedInviteCode(securityService.encryptInviteCode(oldCode));
        room.setMembers(new ArrayList<>(List.of(
                new RoomMember("Alice", "ADMIN"),
                new RoomMember("Charlie", "MEMBER")
        )));
        room.setRemovedMembers(new HashSet<>());

        when(roomRepo.findByRoomId("dev-room")).thenReturn(room);
        when(roomRepo.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Alice removes Charlie
        String newCode = roomService.removeMember("dev-room", "Alice", "Charlie");

        assertThat(newCode).isNotNull().isNotEqualTo(oldCode);
        // Charlie removed from members
        assertThat(room.isMember("Charlie")).isFalse();
        // Charlie added to removedMembers
        assertThat(room.isRemoved("Charlie")).isTrue();
        // Old code is invalidated
        assertThat(securityService.verifyInviteCode(oldCode, room.getInviteCodeHash())).isFalse();
        // New code is valid
        assertThat(securityService.verifyInviteCode(newCode, room.getInviteCodeHash())).isTrue();
    }

    @Test
    void testRemovedMemberCannotRejoin() {
        String newCode = "NEWW-2222-CODE";
        Room room = new Room();
        room.setRoomId("dev-room");
        room.setAdmin("Alice");
        room.setInviteCodeHash(securityService.hashInviteCode(newCode));
        room.setEncryptedInviteCode(securityService.encryptInviteCode(newCode));
        room.setMembers(new ArrayList<>(List.of(new RoomMember("Alice", "ADMIN"))));
        room.setRemovedMembers(new HashSet<>(List.of("charlie")));

        when(roomRepo.findByRoomId("dev-room")).thenReturn(room);

        // Charlie tries to join even with valid new code
        assertThatThrownBy(() -> roomService.joinRoom("dev-room", "Charlie", newCode))
                .isInstanceOf(RemovedMemberException.class)
                .hasMessageContaining("You have been removed from this room and cannot rejoin");
    }

    @Test
    void testAdminCannotRemoveThemselves() {
        Room room = new Room();
        room.setRoomId("dev-room");
        room.setAdmin("Alice");
        room.setMembers(new ArrayList<>(List.of(new RoomMember("Alice", "ADMIN"))));

        when(roomRepo.findByRoomId("dev-room")).thenReturn(room);

        assertThatThrownBy(() -> roomService.removeMember("dev-room", "Alice", "Alice"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("The Admin cannot remove themselves");
    }

    @Test
    void testNonAdminCannotRemoveMembers() {
        Room room = new Room();
        room.setRoomId("dev-room");
        room.setAdmin("Alice");
        room.setMembers(new ArrayList<>(List.of(new RoomMember("Alice", "ADMIN"), new RoomMember("Bob", "MEMBER"))));

        when(roomRepo.findByRoomId("dev-room")).thenReturn(room);

        assertThatThrownBy(() -> roomService.removeMember("dev-room", "Bob", "Alice"))
                .isInstanceOf(UnauthorizedAdminException.class)
                .hasMessageContaining("Only the room Admin can remove members");
    }

    @Test
    void testCodeForAnotherRoomIsRejected() {
        String roomACode = "AAAA-1111-AAAA";
        String roomBCode = "BBBB-2222-BBBB";

        Room roomA = new Room();
        roomA.setRoomId("room-a");
        roomA.setAdmin("Alice");
        roomA.setInviteCodeHash(securityService.hashInviteCode(roomACode));
        roomA.setEncryptedInviteCode(securityService.encryptInviteCode(roomACode));

        when(roomRepo.findByRoomId("room-a")).thenReturn(roomA);

        // Trying to join room-a with room-b's code
        assertThatThrownBy(() -> roomService.joinRoom("room-a", "Eve", roomBCode))
                .isInstanceOf(InvalidInviteCodeException.class)
                .hasMessageContaining("Invalid room ID or invitation code");
    }

    @Test
    void testJoiningExpiredInactiveRoomIsRejected() {
        String code = "VALID-CODE-1234";
        Room room = new Room();
        room.setRoomId("expired-room");
        room.setAdmin("Alice");
        room.setInviteCodeHash(securityService.hashInviteCode(code));
        room.setEncryptedInviteCode(securityService.encryptInviteCode(code));
        // Inactive for 31 days
        room.setLastActivityAt(new Date(System.currentTimeMillis() - (31L * 24L * 60L * 60L * 1000L)));

        when(roomRepo.findByRoomId("expired-room")).thenReturn(room);

        assertThatThrownBy(() -> roomService.joinRoom("expired-room", "Bob", code))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Room has expired due to inactivity");
    }

    @Test
    void testRemovingMemberPreservesOtherMembersAndPreviousMessages() {
        String oldCode = "OLDD-1111-CODE";
        Room room = new Room();
        room.setRoomId("dev-room");
        room.setAdmin("Alice");
        room.setInviteCodeHash(securityService.hashInviteCode(oldCode));
        room.setEncryptedInviteCode(securityService.encryptInviteCode(oldCode));
        room.setMembers(new ArrayList<>(List.of(
                new RoomMember("Alice", "ADMIN"),
                new RoomMember("Bob", "MEMBER"),
                new RoomMember("Charlie", "MEMBER")
        )));
        room.setRemovedMembers(new HashSet<>());

        when(roomRepo.findByRoomId("dev-room")).thenReturn(room);
        when(roomRepo.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // Alice removes Charlie
        roomService.removeMember("dev-room", "Alice", "Charlie");

        // Alice and Bob remain members; member count is now 2
        assertThat(room.getMembers()).hasSize(2);
        assertThat(room.isMember("Alice")).isTrue();
        assertThat(room.isMember("Bob")).isTrue();
        assertThat(room.isMember("Charlie")).isFalse();

        // Message repository delete is NEVER called on member removal (messages are preserved)
        verify(messageRepo, never()).delete(any());
        verify(messageRepo, never()).deleteAll(any());
    }

    @Test
    void testRemovedMemberCannotSendMessage() {
        Room room = new Room();
        room.setRoomId("dev-room");
        room.setAdmin("Alice");
        room.setRemovedMembers(new HashSet<>(List.of("charlie")));

        when(roomRepo.findByRoomId("dev-room")).thenReturn(room);

        assertThatThrownBy(() -> messageService.createMessage("dev-room", "Charlie", "Hello"))
                .isInstanceOf(RemovedMemberException.class)
                .hasMessageContaining("You have been removed from this room and cannot send messages");

        verify(messageRepo, never()).save(any());
    }

    @Test
    void testJoinRoomReturnsRoomIdAsPrimaryDisplayNameEvenForLegacyRoom() {
        String plainCode = "ABCD-1234-EFGH";
        Room legacyRoom = new Room();
        legacyRoom.setRoomId("dev-room-legacy");
        legacyRoom.setRoomName("Legacy Custom Room Name");
        legacyRoom.setAdmin("Alice");
        legacyRoom.setInviteCodeHash(securityService.hashInviteCode(plainCode));
        legacyRoom.setEncryptedInviteCode(securityService.encryptInviteCode(plainCode));
        legacyRoom.setMembers(new ArrayList<>(List.of(new RoomMember("Alice", "ADMIN"))));
        legacyRoom.setRemovedMembers(new HashSet<>());

        when(roomRepo.findByRoomId("dev-room-legacy")).thenReturn(legacyRoom);
        when(roomRepo.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse auth = roomService.joinRoom("dev-room-legacy", "Bob", plainCode);

        assertThat(auth).isNotNull();
        assertThat(auth.getRoomId()).isEqualTo("dev-room-legacy");
        // Primary display name is the Room ID
        assertThat(auth.getRoomName()).isEqualTo("dev-room-legacy");
    }

    @Test
    void testCreateRoomWithAuthReturnsRoomIdAsPrimaryDisplayName() {
        when(roomRepo.findByRoomId("new-auth-room")).thenReturn(null);
        when(roomRepo.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AuthResponse auth = roomService.createRoomWithAuth("new-auth-room", "Alice");

        assertThat(auth).isNotNull();
        assertThat(auth.getRoomId()).isEqualTo("new-auth-room");
        assertThat(auth.getRoomName()).isEqualTo("new-auth-room");
        assertThat(auth.isAdmin()).isTrue();
    }

    @Test
    void testCreatorDoesNotAppearInRoomsJoinedAndNormalUserAppearsInRoomsJoined() {
        // Room 1: Created by User A ("Alice")
        Room room1 = new Room();
        room1.setId("db-room-1");
        room1.setRoomId("room-1");
        room1.setRoomName("Room One");
        room1.setAdmin("Alice");
        room1.setAdminId("user-a");
        room1.setCreatedAt(new Date());
        room1.getMembers().add(new RoomMember("user-a", "Alice", "ADMIN", "avatar1.png"));
        // User B joins Room 1
        RoomMember userBMember = new RoomMember("user-b", "Bob", "MEMBER", "avatar2.png");
        userBMember.setJoinedAt(new Date());
        room1.getMembers().add(userBMember);

        // Room 2: Created by User B ("Bob")
        Room room2 = new Room();
        room2.setId("db-room-2");
        room2.setRoomId("room-2");
        room2.setRoomName("Room Two");
        room2.setAdmin("Bob");
        room2.setAdminId("user-b");
        room2.setCreatedAt(new Date());
        room2.getMembers().add(new RoomMember("user-b", "Bob", "ADMIN", "avatar2.png"));
        // User A joins Room 2
        RoomMember userAMember = new RoomMember("user-a", "Alice", "MEMBER", "avatar1.png");
        userAMember.setJoinedAt(new Date());
        room2.getMembers().add(userAMember);

        // Mock repo returns for User A:
        when(roomRepo.findRoomsCreatedByUser(eq("user-a"), eq("Alice"), eq("alice@example.com"))).thenReturn(List.of(room1));
        when(roomRepo.findByAdminId("user-a")).thenReturn(List.of(room1));
        when(roomRepo.findRoomsJoinedByUser(eq("user-a"), eq("Alice"), eq("alice@example.com"))).thenReturn(List.of(room1, room2));

        // Mock repo returns for User B:
        when(roomRepo.findRoomsCreatedByUser(eq("user-b"), eq("Bob"), eq("bob@example.com"))).thenReturn(List.of(room2));
        when(roomRepo.findByAdminId("user-b")).thenReturn(List.of(room2));
        when(roomRepo.findRoomsJoinedByUser(eq("user-b"), eq("Bob"), eq("bob@example.com"))).thenReturn(List.of(room1, room2));

        // Test User A:
        List<Room> userACreated = roomService.getRoomsCreatedByUser("user-a", "Alice", "alice@example.com");
        List<Room> userAJoined = roomService.getRoomsJoinedByUser("user-a", "Alice", "alice@example.com");

        assertThat(userACreated).extracting(Room::getRoomId).containsExactly("room-1");
        // CRITICAL: User A created Room 1, so Room 1 MUST NOT appear in User A's Joined history!
        // User A joined Room 2, so Room 2 MUST appear in User A's Joined history!
        assertThat(userAJoined).extracting(Room::getRoomId).containsExactly("room-2");

        // Test User B:
        List<Room> userBCreated = roomService.getRoomsCreatedByUser("user-b", "Bob", "bob@example.com");
        List<Room> userBJoined = roomService.getRoomsJoinedByUser("user-b", "Bob", "bob@example.com");

        assertThat(userBCreated).extracting(Room::getRoomId).containsExactly("room-2");
        // CRITICAL: User B created Room 2, so Room 2 MUST NOT appear in User B's Joined history!
        // User B joined Room 1, so Room 1 MUST appear in User B's Joined history!
        assertThat(userBJoined).extracting(Room::getRoomId).containsExactly("room-1");
    }

    @Test
    void testRemovedMemberDisappearsFromRoomsJoined() {
        Room room = new Room();
        room.setId("db-room-10");
        room.setRoomId("room-10");
        room.setAdmin("Alice");
        room.setAdminId("user-a");
        room.getMembers().add(new RoomMember("user-a", "Alice", "ADMIN", "avatar1.png"));

        // User B was removed
        room.getRemovedUserIds().add("user-b");
        room.getRemovedMembers().add("bob");

        when(roomRepo.findRoomsJoinedByUser(eq("user-b"), eq("Bob"), eq("bob@example.com"))).thenReturn(List.of(room));

        List<Room> userBJoined = roomService.getRoomsJoinedByUser("user-b", "Bob", "bob@example.com");
        assertThat(userBJoined).isEmpty();
    }
}
