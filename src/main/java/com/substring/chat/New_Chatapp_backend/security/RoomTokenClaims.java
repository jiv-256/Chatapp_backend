package com.substring.chat.New_Chatapp_backend.security;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class RoomTokenClaims {
    private String username;
    private String roomId;
    private String role;
    private boolean admin;
}
