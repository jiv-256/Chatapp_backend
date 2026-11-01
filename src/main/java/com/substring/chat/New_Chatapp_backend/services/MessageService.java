package com.substring.chat.New_Chatapp_backend.services;

import com.substring.chat.New_Chatapp_backend.entities.Message;
import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.entities.RoomMember;
import com.substring.chat.New_Chatapp_backend.repo.MessageRepo;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class MessageService {

    private final MessageRepo messageRepo;
    private final RoomRepo roomRepo;
    private final MongoTemplate mongoTemplate;

    @Autowired
    public MessageService(MessageRepo messageRepo,
                          RoomRepo roomRepo,
                          @Autowired(required = false) MongoTemplate mongoTemplate) {
        this.messageRepo = messageRepo;
        this.roomRepo = roomRepo;
        this.mongoTemplate = mongoTemplate;
    }

    public MessageService(MessageRepo messageRepo, RoomRepo roomRepo) {
        this(messageRepo, roomRepo, null);
    }

    /**
     * Creates and persists a new message, applying the room's current effective retention
     * and updating the room's lastActivityAt timestamp.
     */
    public Message createMessage(String roomId, String sender, String content) {
        return createMessage(roomId, sender, content, null, null);
    }

    public Message createMessage(String roomId, String sender, String content, String clientTempId) {
        return createMessage(roomId, sender, content, clientTempId, null);
    }

    /**
     * Creates and persists a new message with clientTempId reconciliation support and avatar persistence.
     * Verifies room existence, member removal, and active room membership.
     * Atomically refreshes lastActivityAt.
     */
    public Message createMessage(String roomId, String sender, String content, String clientTempId, String avatar) {
        return createMessage(roomId, sender, content, clientTempId, avatar, null, null, null);
    }

    /**
     * Creates and persists a new message with clientTempId reconciliation support, avatar persistence,
     * and WhatsApp-style reply association.
     * Verifies room existence, member removal, active room membership, and reply room boundaries.
     * Atomically refreshes lastActivityAt.
     */
    public Message createMessage(String roomId, String sender, String content, String clientTempId, String avatar,
                                 String replyToMessageId, String replyToSender, String replyToText) {
        return createMessage(roomId, sender, content, clientTempId, avatar, replyToMessageId, replyToSender, replyToText, false, null);
    }

    /**
     * Creates and persists a new message with optional End-to-End Encryption (E2EE) metadata.
     * E2EE is strictly enforced for DIRECT (one-to-one) rooms only.
     */
    public Message createMessage(String roomId, String sender, String content, String clientTempId, String avatar,
                                 String replyToMessageId, String replyToSender, String replyToText,
                                 boolean encrypted, String iv) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            throw new RuntimeException("room not found!!");
        }

        if (sender != null && room.isRemoved(sender)) {
            throw new com.substring.chat.New_Chatapp_backend.exceptions.RemovedMemberException(
                    "You have been removed from this room and cannot send messages."
            );
        }

        if (sender != null && !sender.trim().isEmpty() && room.getMembers() != null && !room.getMembers().isEmpty()) {
            if (!room.isMember(sender)) {
                throw new SecurityException("Access denied: You are not an active member of room " + roomId);
            }
        }

        Date now = new Date();
        long retentionSeconds = room.getEffectiveRetentionSeconds();
        Date expiresAt = new Date(now.getTime() + retentionSeconds * 1000L);

        String resolvedAvatar = avatar;
        if ((resolvedAvatar == null || resolvedAvatar.trim().isEmpty()) && sender != null && room.getMembers() != null) {
            resolvedAvatar = room.getMembers().stream()
                    .filter(m -> m.getUsername() != null && m.getUsername().equalsIgnoreCase(sender.trim()))
                    .map(RoomMember::getAvatar)
                    .filter(a -> a != null && !a.trim().isEmpty())
                    .findFirst()
                    .orElse(null);
        }

        // Strictly enforce E2EE only for DIRECT conversations
        boolean isDirectRoom = "DIRECT".equalsIgnoreCase(room.getRoomType());
        boolean effectiveEncrypted = encrypted && isDirectRoom;
        String effectiveIv = effectiveEncrypted ? iv : null;

        Message message = new Message();
        message.setRoomId(roomId);
        message.setSender(sender);
        message.setAvatar(resolvedAvatar);
        message.setContent(content);
        message.setCreatedAt(now);
        message.setTimeStamp(now);
        message.setExpiresAt(expiresAt);
        message.setClientTempId(clientTempId);
        message.setMessageType("USER");
        message.setEncrypted(effectiveEncrypted);
        message.setIv(effectiveIv);

        // WhatsApp-style reply association & security boundary validation
        if (replyToMessageId != null && !replyToMessageId.trim().isEmpty()) {
            String trimmedReplyId = replyToMessageId.trim();
            Message referencedMsg = messageRepo.findById(trimmedReplyId).orElse(null);
            if (referencedMsg != null) {
                // If the referenced message has already expired, do not expose its contents
                if (referencedMsg.isExpired(now)) {
                    message.setReplyToMessageId(referencedMsg.getId());
                    message.setReplyToSender(referencedMsg.getSender());
                    message.setReplyToText("Original message expired");
                } else if (referencedMsg.getRoomId() != null && referencedMsg.getRoomId().equals(roomId)) {
                    // Ensure users cannot use a reply reference to gain unauthorized access to another room's messages
                    message.setReplyToMessageId(referencedMsg.getId());
                    message.setReplyToSender(referencedMsg.getSender());
                    String refContent = referencedMsg.getContent();
                    if (referencedMsg.isEncrypted()) {
                        message.setReplyToText("🔒 Encrypted message");
                    } else if (refContent != null && refContent.startsWith("sticker:")) {
                        message.setReplyToText("🎨 Sticker");
                    } else if (refContent != null && refContent.length() > 140) {
                        message.setReplyToText(refContent.substring(0, 140) + "...");
                    } else {
                        message.setReplyToText(refContent != null ? refContent : "");
                    }
                }
            } else {
                // If original message is unavailable or deleted, display a safe fallback
                message.setReplyToMessageId(trimmedReplyId);
                message.setReplyToSender(replyToSender != null && !replyToSender.trim().isEmpty() ? replyToSender.trim() : "Member");
                message.setReplyToText(replyToText != null && !replyToText.trim().isEmpty() ? replyToText.trim() : "Original message unavailable");
            }
        }

        Message savedMessage = messageRepo.save(message);
        savedMessage.setClientTempId(clientTempId);

        // A new message updates the room's lastActivityAt atomically to restart the 30-day inactivity timer
        if (mongoTemplate != null) {
            try {
                mongoTemplate.updateFirst(
                        Query.query(Criteria.where("roomId").is(roomId)),
                        Update.update("lastActivityAt", now),
                        Room.class
                );
            } catch (Exception ignored) {
                room.setLastActivityAt(now);
                roomRepo.save(room);
            }
        } else {
            room.setLastActivityAt(now);
            roomRepo.save(room);
        }

        return savedMessage;
    }

    /**
     * Creates and persists a verified system activity message.
     * System messages are strictly generated on the backend following successful membership operations.
     */
    public Message createSystemMessage(String roomId, String eventType, String content,
                                        String actorName, String targetName, String joinMethod) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            throw new RuntimeException("Room not found: " + roomId);
        }

        Date now = new Date();
        long retentionSeconds = room.getEffectiveRetentionSeconds();
        Date expiresAt = new Date(now.getTime() + retentionSeconds * 1000L);

        Message message = new Message();
        message.setRoomId(roomId);
        message.setSender("SYSTEM");
        message.setContent(content);
        message.setCreatedAt(now);
        message.setTimeStamp(now);
        message.setExpiresAt(expiresAt);
        message.setMessageType("SYSTEM");
        message.setEventType(eventType);
        message.setActorName(actorName);
        message.setTargetName(targetName);
        message.setJoinMethod(joinMethod);

        Message savedMessage = messageRepo.save(message);

        // System activity updates the room's lastActivityAt atomically
        if (mongoTemplate != null) {
            try {
                mongoTemplate.updateFirst(
                        Query.query(Criteria.where("roomId").is(roomId)),
                        Update.update("lastActivityAt", now),
                        Room.class
                );
            } catch (Exception ignored) {
                room.setLastActivityAt(now);
                roomRepo.save(room);
            }
        } else {
            room.setLastActivityAt(now);
            roomRepo.save(room);
        }

        return savedMessage;
    }

    /**
     * Retrieves active messages for the room, filtering out any messages whose
     * expiresAt timestamp is in the past.
    /**
     * Retrieves active messages for the room, filtering out expired messages and messages deleted for current user.
     */
    public List<Message> getValidMessagesForRoom(String roomId) {
        return getValidMessagesForRoom(roomId, null, null);
    }

    public List<Message> getValidMessagesForRoom(String roomId, String currentUserId, String currentUsername) {
        Date now = new Date();
        List<Message> messages = messageRepo.findByRoomIdOrderByTimeStampAsc(roomId);

        // Identify IDs of all active unexpired messages in this room to validate reply references
        java.util.Set<String> activeMessageIds = messages.stream()
                .filter(msg -> msg.getExpiresAt() == null || !msg.getExpiresAt().before(now))
                .map(Message::getId)
                .filter(java.util.Objects::nonNull)
                .collect(Collectors.toSet());

        return messages.stream()
                .filter(msg -> msg.getExpiresAt() == null || !msg.getExpiresAt().before(now))
                .filter(msg -> !msg.isDeletedFor(currentUserId) && !msg.isDeletedFor(currentUsername))
                .map(msg -> {
                    if (msg.isDeletedForEveryone()) {
                        msg.setContent("This message was deleted");
                    }
                    // Requirement 2 & 4: Ensure expired messages cannot be recovered through reply previews
                    if (msg.getReplyToMessageId() != null && !activeMessageIds.contains(msg.getReplyToMessageId())) {
                        msg.setReplyToText("Original message expired");
                    }
                    return msg;
                })
                .collect(Collectors.toList());
    }

    /**
     * Delete messages for current user only (persisted in MongoDB).
     */
    public int deleteMessagesForMe(String roomId, List<String> messageIds, String userId, String username) {
        if (messageIds == null || messageIds.isEmpty()) return 0;
        int count = 0;
        for (String id : messageIds) {
            Message msg = messageRepo.findById(id).orElse(null);
            if (msg != null && roomId.equals(msg.getRoomId())) {
                boolean changed = false;
                if (userId != null && !userId.trim().isEmpty() && !msg.getDeletedForUsers().contains(userId.trim())) {
                    msg.getDeletedForUsers().add(userId.trim());
                    changed = true;
                }
                if (username != null && !username.trim().isEmpty() && !msg.getDeletedForUsers().contains(username.trim())) {
                    msg.getDeletedForUsers().add(username.trim());
                    changed = true;
                }
                if (changed) {
                    messageRepo.save(msg);
                    count++;
                }
            }
        }
        return count;
    }

    /**
     * Delete messages for everyone.
     * Enforces STRICT ownership check on backend: every message MUST belong to the authenticated user.
     */
    public List<Message> deleteMessagesForEveryone(String roomId, List<String> messageIds, String userId, String username) {
        if (messageIds == null || messageIds.isEmpty()) return java.util.Collections.emptyList();

        List<Message> targetMessages = new java.util.ArrayList<>();
        for (String id : messageIds) {
            Message msg = messageRepo.findById(id).orElse(null);
            if (msg == null || !roomId.equals(msg.getRoomId())) {
                throw new IllegalArgumentException("Message not found in this room: " + id);
            }

            // Validate ownership
            boolean isOwner = (username != null && msg.getSender() != null && msg.getSender().equalsIgnoreCase(username.trim()));
            if (!isOwner) {
                throw new SecurityException("Unauthorized: You can only delete your own messages for everyone.");
            }
            targetMessages.add(msg);
        }

        // Apply deletion for everyone
        for (Message msg : targetMessages) {
            msg.setDeletedForEveryone(true);
            msg.setContent("This message was deleted");
            msg.setReplyToText(null);
            messageRepo.save(msg);
        }

        return targetMessages;
    }

    /**
     * Toggles an emoji reaction on a message for the authenticated user.
     */
    public Message toggleReaction(String roomId, String messageId, String username, String emoji) {
        if (messageId == null || emoji == null || username == null) {
            throw new IllegalArgumentException("Message ID, username and emoji are required.");
        }
        Message msg = messageRepo.findById(messageId).orElse(null);
        if (msg == null || !roomId.equals(msg.getRoomId())) {
            throw new IllegalArgumentException("Message not found in room: " + roomId);
        }
        if (msg.isDeletedForEveryone()) {
            throw new IllegalStateException("Cannot react to a deleted message.");
        }

        String cleanedUser = username.trim();
        String cleanedEmoji = emoji.trim();

        if (cleanedEmoji.equals(msg.getReactions().get(cleanedUser))) {
            // Same emoji: toggle off / remove
            msg.getReactions().remove(cleanedUser);
        } else {
            // Set or update reaction
            msg.getReactions().put(cleanedUser, cleanedEmoji);
        }

        return messageRepo.save(msg);
    }

    /**
     * Forwards an existing message to one or more target rooms.
     */
    public List<Message> forwardMessage(String sourceMessageId, List<String> targetRoomIds, String senderUsername, String senderAvatar) {
        if (sourceMessageId == null || targetRoomIds == null || targetRoomIds.isEmpty()) {
            return java.util.Collections.emptyList();
        }

        Message original = messageRepo.findById(sourceMessageId).orElse(null);
        if (original == null) {
            throw new IllegalArgumentException("Original message not found: " + sourceMessageId);
        }
        if (original.isDeletedForEveryone()) {
            throw new IllegalStateException("Cannot forward a deleted message.");
        }

        List<Message> forwardedMessages = new java.util.ArrayList<>();
        Date now = new Date();

        for (String targetRoomId : targetRoomIds) {
            Room targetRoom = roomRepo.findByRoomId(targetRoomId);
            if (targetRoom == null) continue;

            long retentionSeconds = targetRoom.getEffectiveRetentionSeconds();
            Date expiresAt = new Date(now.getTime() + retentionSeconds * 1000L);

            Message fwd = new Message();
            fwd.setRoomId(targetRoomId);
            fwd.setSender(senderUsername);
            fwd.setAvatar(senderAvatar);
            fwd.setContent(original.getContent());
            fwd.setCreatedAt(now);
            fwd.setTimeStamp(now);
            fwd.setExpiresAt(expiresAt);
            fwd.setMessageType("USER");
            fwd.setForwarded(true);
            fwd.setForwardedFromSender(original.getSender());

            Message saved = messageRepo.save(fwd);
            forwardedMessages.add(saved);

            // Update target room activity
            targetRoom.setLastActivityAt(now);
            roomRepo.save(targetRoom);
        }

        return forwardedMessages;
    }

    /**
     * Recalculates expiration timestamps of all existing messages in a room
     * using each message's original creation timestamp plus the new retention period.
     * Preserves original creation timestamp (timeStamp/createdAt).
     */
    public int recalculateExpirationForRoom(String roomId, long retentionSeconds) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room != null && room.isDirectChat()) {
            retentionSeconds = com.substring.chat.New_Chatapp_backend.config.RetentionConstants.DIRECT_CHAT_RETENTION_SECONDS;
        }

        List<Message> messages = messageRepo.findByRoomId(roomId);
        if (messages.isEmpty()) {
            return 0;
        }

        Date now = new Date();
        for (Message message : messages) {
            Date original = message.getTimeStamp() != null ? message.getTimeStamp() : new Date();
            // Do NOT modify message.getTimeStamp()
            Date newExpiresAt = new Date(original.getTime() + retentionSeconds * 1000L);
            message.setExpiresAt(newExpiresAt);
        }

        messageRepo.saveAll(messages);

        // Immediately purge messages whose recalculated expiresAt is already in the past to free Atlas storage
        messageRepo.deleteByExpiresAtBefore(now);

        return messages.size();
    }
}
