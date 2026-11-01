package com.substring.chat.New_Chatapp_backend.entities;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document(collection = "users")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class User {

    @Id
    private String id;

    @Indexed(unique = true)
    private String email;

    private String name;

    private String password;

    private String avatar;

    private String picture;

    @Indexed(unique = true, sparse = true)
    private String googleId;

    private Date createdAt;

    private Date lastLoginAt;

    // End-to-End Encryption (E2EE) Public Key Identity (JWK format)
    private String e2eePublicKey;

    // Human-verifiable public key fingerprint / safety number base
    private String e2eePublicKeyFingerprint;

    // Timestamp when public key was registered or rotated
    private Date e2eeKeyUpdatedAt;

    public String getAvatar() {
        if (avatar != null && !avatar.trim().isEmpty()) {
            return avatar;
        }
        return picture;
    }

    public void setAvatar(String avatar) {
        this.avatar = avatar;
        if (this.picture == null || this.picture.trim().isEmpty()) {
            this.picture = avatar;
        }
    }
}
