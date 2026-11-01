package com.substring.chat.New_Chatapp_backend.security;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class UserTokenClaims {
    private String userId;
    private String email;
    private String name;
    private String picture;
    private String avatar;
    private String googleId;

    public UserTokenClaims(String userId, String email, String name, String picture, String googleId) {
        this.userId = userId;
        this.email = email;
        this.name = name;
        this.picture = picture;
        this.avatar = picture;
        this.googleId = googleId;
    }

    public String getAvatar() {
        if (avatar != null && !avatar.trim().isEmpty()) {
            return avatar;
        }
        return picture;
    }
}
