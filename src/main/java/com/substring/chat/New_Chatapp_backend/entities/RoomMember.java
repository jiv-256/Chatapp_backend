package com.substring.chat.New_Chatapp_backend.entities;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.Date;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RoomMember {
    private String userId;
    private String username;
    private String role; // "ADMIN" or "MEMBER"
    private String avatar;
    private Date joinedAt;
    private boolean connected;
    private Date lastSeenAt;

    public RoomMember(String username, String role) {
        this(null, username, role, null);
    }

    public RoomMember(String username, String role, String avatar) {
        this(null, username, role, avatar);
    }

    public RoomMember(String userId, String username, String role, String avatar) {
        this.userId = userId;
        this.username = username;
        this.role = role;
        this.avatar = avatar;
        this.joinedAt = new Date();
        this.connected = true;
        this.lastSeenAt = new Date();
    }

    public RoomMember(String username, String role, Date joinedAt, boolean connected, Date lastSeenAt) {
        this.userId = null;
        this.username = username;
        this.role = role;
        this.avatar = null;
        this.joinedAt = joinedAt;
        this.connected = connected;
        this.lastSeenAt = lastSeenAt;
    }
}
