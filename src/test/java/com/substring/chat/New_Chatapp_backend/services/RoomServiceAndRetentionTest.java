package com.substring.chat.New_Chatapp_backend.services;

import com.substring.chat.New_Chatapp_backend.config.RetentionConstants;
import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.exceptions.InvalidRetentionException;
import com.substring.chat.New_Chatapp_backend.exceptions.UnauthorizedAdminException;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import com.substring.chat.New_Chatapp_backend.security.RoomSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RoomServiceAndRetentionTest {

    @Mock
    private RoomRepo roomRepo;

    @Mock
    private MessageService messageService;

    private RoomSecurityService roomSecurityService;
    private RoomService roomService;

    @BeforeEach
    void setUp() {
        roomSecurityService = new RoomSecurityService("TestSecretKey_1234567890_TestSecretKey_1234567890");
        roomService = new RoomService(roomRepo, messageService, roomSecurityService);
    }

    @Test
    void testRoomCreatorIsSavedAsAdminWithDefaultRetention() {
        when(roomRepo.findByRoomId("test-room")).thenReturn(null);
        when(roomRepo.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Room created = roomService.createRoom("test-room", "Alice");

        assertThat(created).isNotNull();
        assertThat(created.getRoomId()).isEqualTo("test-room");
        assertThat(created.getAdmin()).isEqualTo("Alice");
        assertThat(created.getCreator()).isEqualTo("Alice");
        assertThat(created.isCustomDeletionTimeEnabled()).isFalse();
        assertThat(created.getCustomDeletionAfterSeconds()).isEqualTo(RetentionConstants.DEFAULT_RETENTION_SECONDS);
        assertThat(created.getEffectiveRetentionSeconds()).isEqualTo(604800L);
        assertThat(created.getCreatedAt()).isNotNull();
        assertThat(created.getLastActivityAt()).isNotNull();
    }

    @Test
    void testNonAdminReceivesForbiddenWhenUpdatingSettings() {
        Room room = new Room();
        room.setRoomId("test-room");
        room.setAdmin("Alice");
        when(roomRepo.findByRoomId("test-room")).thenReturn(room);

        // Bob attempts to change settings for Alice's room
        assertThatThrownBy(() ->
                roomService.updateRetentionSettings("test-room", "Bob", true, 300L)
        ).isInstanceOf(UnauthorizedAdminException.class)
         .hasMessageContaining("Only the room Admin can modify deletion settings");

        verify(roomRepo, never()).save(any());
        verify(messageService, never()).recalculateExpirationForRoom(any(), anyLong());
    }

    @Test
    void testAdminCanEnableAllAllowedDurations() {
        Long[] allowedDurations = {60L, 300L, 1800L, 3600L, 7200L, 21600L, 86400L, 172800L};

        for (Long duration : allowedDurations) {
            Room room = new Room();
            room.setRoomId("test-room-" + duration);
            room.setAdmin("Alice");
            when(roomRepo.findByRoomId("test-room-" + duration)).thenReturn(room);
            when(roomRepo.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

            Room updated = roomService.updateRetentionSettings("test-room-" + duration, "Alice", true, duration);

            assertThat(updated.isCustomDeletionTimeEnabled()).isTrue();
            assertThat(updated.getCustomDeletionAfterSeconds()).isEqualTo(duration);
            assertThat(updated.getEffectiveRetentionSeconds()).isEqualTo(duration);
            verify(messageService).recalculateExpirationForRoom("test-room-" + duration, duration);
        }
    }

    @Test
    void testUnsupportedRetentionDurationsAreRejected() {
        Room room = new Room();
        room.setRoomId("test-room");
        room.setAdmin("Alice");
        when(roomRepo.findByRoomId("test-room")).thenReturn(room);

        Long[] invalidDurations = {0L, 45L, 99L, 500L, 999999L, -10L, null};

        for (Long invalid : invalidDurations) {
            assertThatThrownBy(() ->
                    roomService.updateRetentionSettings("test-room", "Alice", true, invalid)
            ).isInstanceOf(InvalidRetentionException.class);
        }

        verify(roomRepo, never()).save(any());
    }

    @Test
    void testDisablingCustomRetentionRestoresDefaultSevenDays() {
        Room room = new Room();
        room.setRoomId("test-room");
        room.setAdmin("Alice");
        room.setCustomDeletionTimeEnabled(true);
        room.setCustomDeletionAfterSeconds(300L);
        when(roomRepo.findByRoomId("test-room")).thenReturn(room);
        when(roomRepo.save(any(Room.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Room updated = roomService.updateRetentionSettings("test-room", "Alice", false, null);

        assertThat(updated.isCustomDeletionTimeEnabled()).isFalse();
        assertThat(updated.getCustomDeletionAfterSeconds()).isEqualTo(RetentionConstants.DEFAULT_RETENTION_SECONDS);
        assertThat(updated.getEffectiveRetentionSeconds()).isEqualTo(604800L);
        verify(messageService).recalculateExpirationForRoom("test-room", 604800L);
    }

    @Test
    void testDirectRoomsExcludedFromHistory() {
        // Setup a group room
        Room groupRoom = new Room();
        groupRoom.setRoomId("dev-team");
        groupRoom.setRoomType("GROUP");
        groupRoom.setAdmin("Alice");
        groupRoom.setAdminId("user-alice");
        groupRoom.setCreatedAt(new Date());

        // Setup a direct room with roomType DIRECT
        Room directRoom1 = new Room();
        directRoom1.setRoomId("direct_alice_bob");
        directRoom1.setRoomType("DIRECT");
        directRoom1.setAdmin("Alice");
        directRoom1.setAdminId("user-alice");
        directRoom1.setCreatedAt(new Date());

        // Setup a direct room where roomId starts with direct_
        Room directRoom2 = new Room();
        directRoom2.setRoomId("direct_alice_charlie");
        directRoom2.setAdmin("Alice");
        directRoom2.setAdminId("user-alice");
        directRoom2.setCreatedAt(new Date());

        when(roomRepo.findRoomsCreatedByUser(eq("user-alice"), eq("Alice"), eq("---")))
                .thenReturn(java.util.List.of(groupRoom, directRoom1, directRoom2));

        java.util.List<Room> created = roomService.getRoomsCreatedByUser("user-alice", "Alice", null);
        assertThat(created).hasSize(1);
        assertThat(created.get(0).getRoomId()).isEqualTo("dev-team");
    }

    @Test
    void testCreatorNeverAppearsInRoomsJoinedHistory() {
        Room myGroup = new Room();
        myGroup.setRoomId("my-awesome-group");
        myGroup.setRoomType("GROUP");
        myGroup.setAdmin("Alice");
        myGroup.setAdminId("user-alice");
        myGroup.getMembers().add(new com.substring.chat.New_Chatapp_backend.entities.RoomMember("user-alice", "Alice", "ADMIN", "Felix"));

        Room otherGroup = new Room();
        otherGroup.setRoomId("bobs-group");
        otherGroup.setRoomType("GROUP");
        otherGroup.setAdmin("Bob");
        otherGroup.setAdminId("user-bob");
        otherGroup.getMembers().add(new com.substring.chat.New_Chatapp_backend.entities.RoomMember("user-alice", "Alice", "MEMBER", "Felix"));

        when(roomRepo.findRoomsJoinedByUser(eq("user-alice"), eq("Alice"), eq("---")))
                .thenReturn(java.util.List.of(myGroup, otherGroup));

        java.util.List<Room> joined = roomService.getRoomsJoinedByUser("user-alice", "Alice", null);
        assertThat(joined).hasSize(1);
        assertThat(joined.get(0).getRoomId()).isEqualTo("bobs-group");
    }

    @Test
    void testDirectChatRetentionSettingsCannotBeModified() {
        Room directRoom = new Room();
        directRoom.setRoomId("direct_alice_bob");
        directRoom.setRoomType("DIRECT");
        directRoom.setAdmin("Alice");
        when(roomRepo.findByRoomId("direct_alice_bob")).thenReturn(directRoom);

        assertThatThrownBy(() ->
                roomService.updateRetentionSettings("direct_alice_bob", "Alice", true, 300L)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Private chats use a fixed 24-hour auto-delete policy and cannot be reconfigured.");

        verify(roomRepo, never()).save(any());
        verify(messageService, never()).recalculateExpirationForRoom(any(), anyLong());
    }

    @Test
    void testDirectChatWithPrefixRetentionSettingsCannotBeModified() {
        Room directRoom = new Room();
        directRoom.setRoomId("direct_123_456");
        directRoom.setAdmin("Alice");
        when(roomRepo.findByRoomId("direct_123_456")).thenReturn(directRoom);

        assertThatThrownBy(() ->
                roomService.updateRetentionSettings("direct_123_456", "Alice", false, null)
        ).isInstanceOf(IllegalArgumentException.class)
         .hasMessageContaining("Private chats use a fixed 24-hour auto-delete policy and cannot be reconfigured.");

        verify(roomRepo, never()).save(any());
    }
}
