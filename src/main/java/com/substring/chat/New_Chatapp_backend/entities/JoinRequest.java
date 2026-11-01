package com.substring.chat.New_Chatapp_backend.entities;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Document(collection = "join_requests")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class JoinRequest {

    @Id
    private String id;

    @Indexed
    private String roomId;

    private String userId;

    private String userName;

    private String avatar;

    private int invitationVersion;

    private String inviteTokenHash;

    @Indexed
    private String status = "PENDING"; // "PENDING", "APPROVED", "REJECTED", "EXPIRED"

    private Date createdAt = new Date();

    private Date reviewedAt;

    private String approvedToken;

    // Transient or persisted flag indicating if this was a new join (vs existing member reconnect)
    private boolean isNewJoin = true;

    public JoinRequest(String roomId, String userName, String avatar, int invitationVersion, String inviteTokenHash) {
        this.roomId = roomId;
        this.userName = userName;
        this.avatar = avatar;
        this.invitationVersion = invitationVersion;
        this.inviteTokenHash = inviteTokenHash;
        this.status = "PENDING";
        this.createdAt = new Date();
    }
}
