package com.substring.chat.New_Chatapp_backend.entities;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document(collection = "rooms")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class Room {

    @Id
    private String id;

    @Indexed(unique = true)
    private String roomId;

    // Optional human-readable room name (defaults to roomId if null)
    private String roomName;

    // Room creator's identity
    private String admin;

    // Room creator's stable user ID
    private String adminId;

    // Room type: "GROUP" (default) or "DIRECT" (1-on-1 private chat)
    private String roomType = "GROUP";

    // Participant user IDs for direct 1-on-1 private chats
    private java.util.List<String> participantUserIds = new java.util.ArrayList<>();

    // Custom deletion settings
    private boolean customDeletionTimeEnabled = false;

    // Custom deletion duration in seconds: 60, 300, 1800, 3600, 21600, 86400, 172800
    private Long customDeletionAfterSeconds;

    private Date createdAt;

    // Inactivity tracking timestamp
    @Indexed
    private Date lastActivityAt;

    // Cryptographic hash of current invitation code (SHA-256)
    private String inviteCodeHash;

    // AES-256-GCM encrypted version of invitation code (retrievable only by authenticated Admin)
    private String encryptedInviteCode;

    // Cryptographic SHA-256 hash of active opaque invitation token
    @Indexed
    private String inviteTokenHash;

    // AES-256-GCM encrypted version of active invitation token
    private String encryptedInviteToken;

    // Monotonically increasing invitation version rotated upon member removal
    private int invitationVersion = 1;

    // Whether guest invitation links require admin approval or allow direct joining
    private boolean adminApprovalRequired = true;

    // Persistent list of active room members
    private java.util.List<RoomMember> members = new java.util.ArrayList<>();

    // Usernames of removed members who are barred from rejoining
    private java.util.Set<String> removedMembers = new java.util.HashSet<>();

    // Stable user IDs of removed members who are barred from rejoining
    private java.util.Set<String> removedUserIds = new java.util.HashSet<>();

    public String getRoomName() {
        if (roomName == null || roomName.trim().isEmpty()) {
            return roomId;
        }
        return roomName;
    }

    public String getRoomType() {
        if (roomType == null || roomType.trim().isEmpty()) {
            return "GROUP";
        }
        return roomType;
    }

    public java.util.List<String> getParticipantUserIds() {
        if (participantUserIds == null) {
            participantUserIds = new java.util.ArrayList<>();
        }
        return participantUserIds;
    }

    public java.util.List<RoomMember> getMembers() {
        if (members == null) {
            members = new java.util.ArrayList<>();
        }
        return members;
    }

    public java.util.Set<String> getRemovedMembers() {
        if (removedMembers == null) {
            removedMembers = new java.util.HashSet<>();
        }
        return removedMembers;
    }

    public java.util.Set<String> getRemovedUserIds() {
        if (removedUserIds == null) {
            removedUserIds = new java.util.HashSet<>();
        }
        return removedUserIds;
    }

    public boolean isMember(String username) {
        if (username == null) return false;
        String trimmed = username.trim();
        if (admin != null && admin.trim().equalsIgnoreCase(trimmed)) {
            return true;
        }
        return getMembers().stream().anyMatch(m -> m.getUsername() != null && m.getUsername().equalsIgnoreCase(trimmed));
    }

    public boolean isRemoved(String username) {
        if (username == null) return false;
        String trimmed = username.trim().toLowerCase();
        return getRemovedMembers().stream().anyMatch(r -> r != null && r.trim().toLowerCase().equals(trimmed));
    }

    public boolean isRemovedUser(String userId, String username) {
        if (userId != null && !userId.trim().isEmpty()) {
            if (getRemovedUserIds().contains(userId.trim())) {
                return true;
            }
        }
        return isRemoved(username);
    }

    public boolean isDirectChat() {
        return "DIRECT".equalsIgnoreCase(getRoomType()) ||
                (roomId != null && roomId.startsWith("direct_"));
    }

    public long getEffectiveRetentionSeconds() {
        // Requirement: 24-hour auto-delete applies strictly to 1-to-1 private chats
        if (isDirectChat()) {
            return com.substring.chat.New_Chatapp_backend.config.RetentionConstants.DIRECT_CHAT_RETENTION_SECONDS;
        }
        if (customDeletionTimeEnabled && customDeletionAfterSeconds != null && customDeletionAfterSeconds > 0) {
            return customDeletionAfterSeconds;
        }
        return com.substring.chat.New_Chatapp_backend.config.RetentionConstants.DEFAULT_RETENTION_SECONDS; // Default 7 days for group rooms
    }

    public String getCreator() {
        return admin;
    }

    public void setCreator(String creator) {
        this.admin = creator;
    }
}
