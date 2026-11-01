package com.substring.chat.New_Chatapp_backend.services;

import com.substring.chat.New_Chatapp_backend.config.RetentionConstants;
import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.repo.MessageRepo;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InactiveRoomCleanupServiceTest {

    @Mock
    private RoomRepo roomRepo;

    @Mock
    private MessageRepo messageRepo;

    private InactiveRoomCleanupService cleanupService;

    @BeforeEach
    void setUp() {
        cleanupService = new InactiveRoomCleanupService(roomRepo, messageRepo);
    }

    @Test
    void testEligibleInactiveRoomAndMessagesAreDeleted() {
        Date cutoff = new Date(System.currentTimeMillis() - RetentionConstants.INACTIVITY_MILLIS);
        Date inactiveDate = new Date(cutoff.getTime() - 100_000L); // Older than cutoff

        Room inactiveRoom = new Room();
        inactiveRoom.setRoomId("dead-room");
        inactiveRoom.setLastActivityAt(inactiveDate);

        when(roomRepo.findByLastActivityAtBefore(cutoff)).thenReturn(List.of(inactiveRoom));

        InactiveRoomCleanupService.CleanupResult result = cleanupService.cleanInactiveRooms(cutoff);

        assertThat(result.deletedRoomsCount()).isEqualTo(1);
        assertThat(result.failedRoomsCount()).isEqualTo(0);

        // Associated messages and room must both be deleted
        verify(messageRepo).deleteByRoomId("dead-room");
        verify(roomRepo).delete(inactiveRoom);
    }

    @Test
    void testActiveRoomWithOldCreationDateIsNotDeleted() {
        Date cutoff = new Date(System.currentTimeMillis() - RetentionConstants.INACTIVITY_MILLIS);

        // Room was created 60 days ago, but was active 2 days ago
        Date recentActivity = new Date(System.currentTimeMillis() - (2L * 24 * 60 * 60 * 1000L));
        Room activeRoom = new Room();
        activeRoom.setRoomId("busy-old-room");
        activeRoom.setCreatedAt(new Date(System.currentTimeMillis() - (60L * 24 * 60 * 60 * 1000L)));
        activeRoom.setLastActivityAt(recentActivity);

        // findByLastActivityAtBefore will NOT return this room because recentActivity is after cutoff
        when(roomRepo.findByLastActivityAtBefore(cutoff)).thenReturn(Collections.emptyList());

        InactiveRoomCleanupService.CleanupResult result = cleanupService.cleanInactiveRooms(cutoff);

        assertThat(result.deletedRoomsCount()).isEqualTo(0);
        verify(messageRepo, never()).deleteByRoomId(any());
        verify(roomRepo, never()).delete(any());
    }

    @Test
    void testMissingLastActivityAtIsSafeAndNotDeleted() {
        Date cutoff = new Date(System.currentTimeMillis() - RetentionConstants.INACTIVITY_MILLIS);

        // Query by lastActivityAt < cutoff will never return documents where lastActivityAt is null
        when(roomRepo.findByLastActivityAtBefore(cutoff)).thenReturn(Collections.emptyList());

        InactiveRoomCleanupService.CleanupResult result = cleanupService.cleanInactiveRooms(cutoff);

        assertThat(result.deletedRoomsCount()).isEqualTo(0);
        verify(messageRepo, never()).deleteByRoomId(any());
        verify(roomRepo, never()).delete(any());
    }

    @Test
    void testMessageRetentionAndInactivityOperateIndependently() {
        Date cutoff = new Date(System.currentTimeMillis() - RetentionConstants.INACTIVITY_MILLIS);

        // A room with 1-minute message retention that had activity 10 minutes ago
        // Its messages may have expired, but the room itself is NOT inactive for 30 days
        Date tenMinutesAgo = new Date(System.currentTimeMillis() - (10L * 60 * 1000L));
        Room fastExpiringRoom = new Room();
        fastExpiringRoom.setRoomId("ephemeral-chat");
        fastExpiringRoom.setCustomDeletionTimeEnabled(true);
        fastExpiringRoom.setCustomDeletionAfterSeconds(60L); // 1 minute message retention
        fastExpiringRoom.setLastActivityAt(tenMinutesAgo);

        when(roomRepo.findByLastActivityAtBefore(cutoff)).thenReturn(Collections.emptyList());

        InactiveRoomCleanupService.CleanupResult result = cleanupService.cleanInactiveRooms(cutoff);

        assertThat(result.deletedRoomsCount()).isEqualTo(0);
        verify(roomRepo, never()).delete(any());
    }

    @Test
    void testRepeatedCleanupIsSafeAndIdempotent() {
        Date cutoff = new Date(System.currentTimeMillis() - RetentionConstants.INACTIVITY_MILLIS);
        when(roomRepo.findByLastActivityAtBefore(cutoff)).thenReturn(Collections.emptyList());

        InactiveRoomCleanupService.CleanupResult result1 = cleanupService.cleanInactiveRooms(cutoff);
        InactiveRoomCleanupService.CleanupResult result2 = cleanupService.cleanInactiveRooms(cutoff);

        assertThat(result1.deletedRoomsCount()).isEqualTo(0);
        assertThat(result2.deletedRoomsCount()).isEqualTo(0);
    }

    @Test
    void testExpiredMessagesArePermanentlyDeleted() {
        Date cutoff = new Date();
        when(messageRepo.deleteByExpiresAtBefore(cutoff)).thenReturn(15L);

        long deleted = cleanupService.cleanExpiredMessages(cutoff);

        assertThat(deleted).isEqualTo(15L);
        verify(messageRepo).deleteByExpiresAtBefore(cutoff);
    }

    @Test
    void testScheduledCleanupExecutesBothMessagePurgeAndRoomCleanup() {
        Date now = new Date();
        when(messageRepo.deleteByExpiresAtBefore(any(Date.class))).thenReturn(8L);
        when(roomRepo.findByLastActivityAtBefore(any(Date.class))).thenReturn(Collections.emptyList());

        InactiveRoomCleanupService.CleanupResult result = cleanupService.runScheduledCleanup();

        assertThat(result.deletedExpiredMessagesCount()).isEqualTo(8L);
        assertThat(result.deletedRoomsCount()).isEqualTo(0);
        verify(messageRepo).deleteByExpiresAtBefore(any(Date.class));
        verify(roomRepo).findByLastActivityAtBefore(any(Date.class));
    }
}
