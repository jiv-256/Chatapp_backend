package com.substring.chat.New_Chatapp_backend.payload;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class AuthResponse {
    private String roomId;
    private String roomName;
    private String userName;
    private boolean admin;
    private String token;
    private String inviteCode; // Null for non-admins to prevent exposure
    private String avatar;
    private String inviteToken;
    private String inviteLink;

    public AuthResponse(String roomId, String userName, boolean admin, String token, String inviteCode) {
        this(roomId, roomId, userName, admin, token, inviteCode, null, null, null);
    }

    public AuthResponse(String roomId, String roomName, String userName, boolean admin, String token, String inviteCode) {
        this(roomId, roomName, userName, admin, token, inviteCode, null, null, null);
    }

    public AuthResponse(String roomId, String roomName, String userName, boolean admin, String token, String inviteCode, String avatar) {
        this(roomId, roomName, userName, admin, token, inviteCode, avatar, null, null);
    }
}
