package com.substring.chat.New_Chatapp_backend.services;

import com.substring.chat.New_Chatapp_backend.config.RetentionConstants;
import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.repo.MessageRepo;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
public class InactiveRoomCleanupService {

    private static final Logger log = LoggerFactory.getLogger(InactiveRoomCleanupService.class);

    private final RoomRepo roomRepo;
    private final MessageRepo messageRepo;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public InactiveRoomCleanupService(RoomRepo roomRepo, MessageRepo messageRepo) {
        this.roomRepo = roomRepo;
        this.messageRepo = messageRepo;
    }

    public static record CleanupResult(int deletedRoomsCount, int failedRoomsCount, Date cutoffDate, long deletedExpiredMessagesCount) {}

    /**
     * Scheduled cleanup job running every 15 minutes to reclaim MongoDB Atlas Free-Tier storage.
     * Permanently purges expired messages and deletes rooms inactive for 30 consecutive days.
     */
    @Scheduled(cron = "${app.cleanup.cron:0 */15 * * * *}")
    public CleanupResult runScheduledCleanup() {
        Date roomCutoff = new Date(System.currentTimeMillis() - RetentionConstants.INACTIVITY_MILLIS);
        Date messageCutoff = new Date();

        long expiredMessagesDeleted = cleanExpiredMessages(messageCutoff);
        CleanupResult roomResult = cleanInactiveRooms(roomCutoff);

        return new CleanupResult(
                roomResult.deletedRoomsCount(),
                roomResult.failedRoomsCount(),
                roomCutoff,
                expiredMessagesDeleted
        );
    }

    /**
     * Permanently deletes messages whose expiresAt timestamp is before cutoffDate.
     * This directly executes an indexed deleteMany query in MongoDB Atlas to reclaim storage.
     */
    public long cleanExpiredMessages(Date cutoffDate) {
        try {
            long deletedCount = messageRepo.deleteByExpiresAtBefore(cutoffDate);
            if (deletedCount > 0) {
                log.info("Physically deleted {} expired message(s) from MongoDB Atlas storage (cutoff: {}).", deletedCount, cutoffDate);
            }
            return deletedCount;
        } catch (Exception e) {
            log.error("Failed to clean expired messages: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Finds and permanently deletes rooms whose lastActivityAt is before cutoffDate,
     * along with all messages associated with those rooms.
     * Prevents concurrent runs and safely handles individual room failures.
     */
    public CleanupResult cleanInactiveRooms(Date cutoffDate) {
        if (!isRunning.compareAndSet(false, true)) {
            log.info("Cleanup job is already running; skipping concurrent execution.");
            return new CleanupResult(0, 0, cutoffDate, 0);
        }

        int deletedRooms = 0;
        int failedRooms = 0;

        try {
            // Find rooms with lastActivityAt strictly before cutoffDate.
            // Rooms with missing lastActivityAt are not returned here and are protected from accidental deletion.
            List<Room> eligibleRooms = roomRepo.findByLastActivityAtBefore(cutoffDate);
            log.info("Found {} inactive room(s) eligible for cleanup (cutoff: {})", eligibleRooms.size(), cutoffDate);

            for (Room room : eligibleRooms) {
                String roomId = room.getRoomId();
                try {
                    // 1. Permanently delete all messages associated with this room to prevent orphans
                    messageRepo.deleteByRoomId(roomId);

                    // 2. Permanently delete the room document itself
                    roomRepo.delete(room);

                    deletedRooms++;
                    log.info("Physically deleted inactive room '{}' and associated messages from MongoDB Atlas.", roomId);
                } catch (Exception ex) {
                    failedRooms++;
                    log.error("Failed to delete inactive room '{}': {}", roomId, ex.getMessage());
                }
            }

            log.info("Inactive room cleanup completed: {} room(s) deleted, {} failure(s).", deletedRooms, failedRooms);
        } finally {
            isRunning.set(false);
        }

        return new CleanupResult(deletedRooms, failedRooms, cutoffDate, 0);
    }
}
