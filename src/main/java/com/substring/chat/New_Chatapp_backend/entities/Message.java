package com.substring.chat.New_Chatapp_backend.entities;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document(collection = "messages")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Message {

    @Id
    private String id;

    @Indexed
    private String roomId;
    private String sender;
    private String avatar;
    private String content;

    private Date timeStamp;

    // Original server-recorded creation timestamp in UTC
    private Date createdAt;

    // Dynamic message expiration via MongoDB TTL (expires when now >= expiresAt)
    @Indexed(expireAfter = "0s")
    private Date expiresAt;

    // Transient client temporary ID used for instant optimistic UI reconciliation
    @org.springframework.data.annotation.Transient
    private String clientTempId;

    // WhatsApp-style reply reference
    private String replyToMessageId;
    private String replyToSender;
    private String replyToText;

    // Message type: USER (default) or SYSTEM
    private String messageType = "USER";

    // Structured system event metadata
    private String eventType; // e.g. MEMBER_JOINED, MEMBER_LEFT, MEMBER_REMOVED
    private String actorName; // Admin or performer display name where applicable
    private String targetName; // Target member display name
    private String joinMethod; // e.g. LINK, CODE, ADMIN_APPROVAL

    // Per-user deletion tracking: list of user IDs or usernames who deleted this message for themselves
    private java.util.List<String> deletedForUsers = new java.util.ArrayList<>();

    // Global deletion flag: true if message was deleted for everyone by its sender
    private boolean deletedForEveryone = false;

    // Reactions: Map of username -> emoji (e.g. {"Alice": "❤️", "Bob": "👍"})
    private java.util.Map<String, String> reactions = new java.util.HashMap<>();

    // WhatsApp-style Forwarding metadata
    private boolean forwarded = false;
    private String forwardedFromSender;

    // End-to-End Encryption (E2EE) metadata
    private boolean encrypted = false;
    private String iv; // Base64-encoded 96-bit initialization vector

    public java.util.List<String> getDeletedForUsers() {
        if (deletedForUsers == null) {
            deletedForUsers = new java.util.ArrayList<>();
        }
        return deletedForUsers;
    }

    public java.util.Map<String, String> getReactions() {
        if (reactions == null) {
            reactions = new java.util.HashMap<>();
        }
        return reactions;
    }

    public boolean isDeletedFor(String userOrId) {
        if (userOrId == null || userOrId.trim().isEmpty()) return false;
        String cleaned = userOrId.trim().toLowerCase();
        return getDeletedForUsers().stream().anyMatch(u -> u != null && u.trim().toLowerCase().equals(cleaned));
    }

    public String getMessageType() {
        if (messageType == null || messageType.trim().isEmpty()) {
            return "USER";
        }
        return messageType;
    }

    public Date getCreatedAt() {
        if (createdAt != null) {
            return createdAt;
        }
        return timeStamp;
    }

    public void setCreatedAt(Date createdAt) {
        this.createdAt = createdAt;
        if (this.timeStamp == null) {
            this.timeStamp = createdAt;
        }
    }

    public Date getTimeStamp() {
        if (timeStamp != null) {
            return timeStamp;
        }
        return createdAt;
    }

    public void setTimeStamp(Date timeStamp) {
        this.timeStamp = timeStamp;
        if (this.createdAt == null) {
            this.createdAt = timeStamp;
        }
    }

    public boolean isExpired() {
        return isExpired(new Date());
    }

    public boolean isExpired(Date now) {
        return expiresAt != null && expiresAt.before(now != null ? now : new Date());
    }

    public Message(String id, String roomId, String sender, String content, Date timeStamp, Date expiresAt) {
        this.id = id;
        this.roomId = roomId;
        this.sender = sender;
        this.content = content;
        this.timeStamp = timeStamp;
        this.createdAt = timeStamp;
        this.expiresAt = expiresAt;
        this.messageType = "USER";
    }
}