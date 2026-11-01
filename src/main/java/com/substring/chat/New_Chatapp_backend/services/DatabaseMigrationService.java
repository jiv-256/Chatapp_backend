package com.substring.chat.New_Chatapp_backend.services;

import com.substring.chat.New_Chatapp_backend.config.RetentionConstants;
import com.substring.chat.New_Chatapp_backend.entities.Message;
import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.repo.MessageRepo;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import com.substring.chat.New_Chatapp_backend.security.RoomSecurityService;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class DatabaseMigrationService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseMigrationService.class);

    private final MongoTemplate mongoTemplate;
    private final RoomRepo roomRepo;
    private final MessageRepo messageRepo;
    private final RoomSecurityService roomSecurityService;

    public DatabaseMigrationService(MongoTemplate mongoTemplate,
                                    RoomRepo roomRepo,
                                    MessageRepo messageRepo,
                                    RoomSecurityService roomSecurityService) {
        this.mongoTemplate = mongoTemplate;
        this.roomRepo = roomRepo;
        this.messageRepo = messageRepo;
        this.roomSecurityService = roomSecurityService;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void runMigration() {
        log.info("Starting safe MongoDB schema and index migration...");
        try {
            migrateIndexes();
            backfillRooms();
            backfillMessages();
            log.info("MongoDB schema and index migration completed successfully.");
        } catch (Exception e) {
            log.error("Migration encountered an error: {}", e.getMessage(), e);
        }
    }

    /**
     * Safely drops conflicting legacy TTL indexes and creates dynamic TTL indexes.
     * Preserves all collections, document data, and Atlas configuration.
     */
    public void migrateIndexes() {
        // 1. Remove legacy TTL index on rooms.createdAt (43200s / 12h) to avoid premature room deletion
        try {
            for (Document doc : mongoTemplate.getCollection("rooms").listIndexes()) {
                String name = doc.getString("name");
                Document key = doc.get("key", Document.class);
                if (key != null && key.containsKey("createdAt") && doc.containsKey("expireAfterSeconds")) {
                    log.info("Dropping legacy TTL index '{}' on rooms collection.", name);
                    mongoTemplate.getCollection("rooms").dropIndex(name);
                }
            }
        } catch (Exception e) {
            log.warn("Notice checking/dropping legacy rooms index: {}", e.getMessage());
        }

        // 2. Remove legacy TTL index on messages.timeStamp (21600s / 6h) to allow dynamic retention
        try {
            for (Document doc : mongoTemplate.getCollection("messages").listIndexes()) {
                String name = doc.getString("name");
                Document key = doc.get("key", Document.class);
                if (key != null && key.containsKey("timeStamp") && doc.containsKey("expireAfterSeconds")) {
                    log.info("Dropping legacy TTL index '{}' on messages collection.", name);
                    mongoTemplate.getCollection("messages").dropIndex(name);
                }
            }
        } catch (Exception e) {
            log.warn("Notice checking/dropping legacy messages index: {}", e.getMessage());
        }

        // 3. Ensure dynamic TTL index on messages.expiresAt (expireAfterSeconds = 0)
        try {
            boolean hasExpiresAtIndex = false;
            for (Document doc : mongoTemplate.getCollection("messages").listIndexes()) {
                Document key = doc.get("key", Document.class);
                if (key != null && key.containsKey("expiresAt")) {
                    hasExpiresAtIndex = true;
                    break;
                }
            }
            if (!hasExpiresAtIndex) {
                log.info("Creating dynamic TTL index on messages.expiresAt (expireAfterSeconds=0)...");
                mongoTemplate.indexOps(Message.class)
                        .ensureIndex(new Index().on("expiresAt", Sort.Direction.ASC).expire(0));
            }
        } catch (Exception e) {
            log.warn("Notice ensuring expiresAt index: {}", e.getMessage());
        }

        // 4. Ensure index on messages.roomId if not already existing
        try {
            boolean hasRoomIdIndex = false;
            for (Document doc : mongoTemplate.getCollection("messages").listIndexes()) {
                Document key = doc.get("key", Document.class);
                if (key != null && key.containsKey("roomId")) {
                    hasRoomIdIndex = true;
                    break;
                }
            }
            if (!hasRoomIdIndex) {
                mongoTemplate.indexOps(Message.class)
                        .ensureIndex(new Index().on("roomId", Sort.Direction.ASC));
            }
        } catch (Exception e) {
            log.warn("Notice checking messages.roomId index: {}", e.getMessage());
        }

        // 5. Ensure index on rooms.lastActivityAt if not already existing
        try {
            boolean hasLastActivityAtIndex = false;
            for (Document doc : mongoTemplate.getCollection("rooms").listIndexes()) {
                Document key = doc.get("key", Document.class);
                if (key != null && key.containsKey("lastActivityAt")) {
                    hasLastActivityAtIndex = true;
                    break;
                }
            }
            if (!hasLastActivityAtIndex) {
                mongoTemplate.indexOps(Room.class)
                        .ensureIndex(new Index().on("lastActivityAt", Sort.Direction.ASC));
            }
        } catch (Exception e) {
            log.warn("Notice checking rooms.lastActivityAt index: {}", e.getMessage());
        }

        // 6. Ensure unique index on users.email if not already existing
        try {
            boolean hasEmailIndex = false;
            for (Document doc : mongoTemplate.getCollection("users").listIndexes()) {
                Document key = doc.get("key", Document.class);
                if (key != null && key.containsKey("email")) {
                    hasEmailIndex = true;
                    break;
                }
            }
            if (!hasEmailIndex) {
                mongoTemplate.indexOps("users")
                        .ensureIndex(new Index().on("email", Sort.Direction.ASC).unique());
            }
        } catch (Exception e) {
            log.warn("Notice checking users.email index: {}", e.getMessage());
        }
    }

    /**
     * Backfills missing fields in existing room documents:
     * - createdAt: defaults to new Date() if missing
     * - lastActivityAt: uses timestamp of most recent message if available, otherwise createdAt
     * - customDeletionAfterSeconds: defaults to 604800 (7 days)
     */
    public void backfillRooms() {
        List<Room> rooms;
        try {
            org.springframework.data.mongodb.core.query.Query query =
                    new org.springframework.data.mongodb.core.query.Query(
                            new org.springframework.data.mongodb.core.query.Criteria().orOperator(
                                    org.springframework.data.mongodb.core.query.Criteria.where("lastActivityAt").is(null),
                                    org.springframework.data.mongodb.core.query.Criteria.where("inviteCodeHash").is(null),
                                    org.springframework.data.mongodb.core.query.Criteria.where("createdAt").is(null)
                            )
                    );
            rooms = mongoTemplate.find(query, Room.class);
        } catch (Exception e) {
            rooms = roomRepo.findAll();
        }
        for (Room room : rooms) {
            boolean changed = false;

            if (room.getCreatedAt() == null) {
                room.setCreatedAt(new Date());
                changed = true;
            }

            if (room.getRoomName() == null || room.getRoomName().trim().isEmpty()) {
                room.setRoomName(room.getRoomId());
                changed = true;
            }

            if (room.getLastActivityAt() == null) {
                List<Message> msgs = messageRepo.findByRoomIdOrderByTimeStampAsc(room.getRoomId());
                if (!msgs.isEmpty()) {
                    Message latestMsg = msgs.get(msgs.size() - 1);
                    room.setLastActivityAt(latestMsg.getTimeStamp() != null ? latestMsg.getTimeStamp() : room.getCreatedAt());
                } else {
                    room.setLastActivityAt(room.getCreatedAt());
                }
                changed = true;
            }

            if (room.getCustomDeletionAfterSeconds() == null) {
                room.setCustomDeletionAfterSeconds(RetentionConstants.DEFAULT_RETENTION_SECONDS);
                changed = true;
            }

            if (room.getInviteCodeHash() == null) {
                String inviteCode = roomSecurityService.generateInviteCode();
                room.setInviteCodeHash(roomSecurityService.hashInviteCode(inviteCode));
                room.setEncryptedInviteCode(roomSecurityService.encryptInviteCode(inviteCode));
                changed = true;
            }

            if (room.getMembers() == null || room.getMembers().isEmpty()) {
                if (room.getAdmin() != null && !room.getAdmin().trim().isEmpty()) {
                    room.getMembers().add(new com.substring.chat.New_Chatapp_backend.entities.RoomMember(room.getAdmin().trim(), "ADMIN"));
                    changed = true;
                }
            }

            if (room.getRemovedMembers() == null) {
                room.setRemovedMembers(new java.util.HashSet<>());
                changed = true;
            }

            if (room.getRoomId() != null && room.getRoomId().startsWith("direct_")) {
                if (!"DIRECT".equalsIgnoreCase(room.getRoomType())) {
                    room.setRoomType("DIRECT");
                    changed = true;
                }
            } else if (room.getRoomType() == null || room.getRoomType().trim().isEmpty()) {
                room.setRoomType("GROUP");
                changed = true;
            }

            if (changed) {
                roomRepo.save(room);
                log.info("Backfilled room '{}' with lastActivityAt: {}", room.getRoomId(), room.getLastActivityAt());
            }
        }
    }

    /**
     * Backfills missing expiresAt timestamps in existing message documents using
     * the message's original creation timestamp plus the room's effective retention period.
     */
    public void backfillMessages() {
        List<Message> messages;
        try {
            org.springframework.data.mongodb.core.query.Query query =
                    new org.springframework.data.mongodb.core.query.Query(
                            org.springframework.data.mongodb.core.query.Criteria.where("expiresAt").is(null)
                    );
            messages = mongoTemplate.find(query, Message.class);
        } catch (Exception e) {
            messages = messageRepo.findAll();
        }
        for (Message message : messages) {
            if (message.getExpiresAt() == null) {
                Date original = message.getCreatedAt() != null ? message.getCreatedAt() :
                        (message.getTimeStamp() != null ? message.getTimeStamp() : new Date());
                if (message.getCreatedAt() == null) {
                    message.setCreatedAt(original);
                }
                if (message.getTimeStamp() == null) {
                    message.setTimeStamp(original);
                }
                Room room = roomRepo.findByRoomId(message.getRoomId());
                long retention = (room != null) ? room.getEffectiveRetentionSeconds() : RetentionConstants.DEFAULT_RETENTION_SECONDS;
                message.setExpiresAt(new Date(original.getTime() + retention * 1000L));
                messageRepo.save(message);
                log.info("Backfilled message '{}' with expiresAt: {}", message.getId(), message.getExpiresAt());
            }
        }
    }
}
