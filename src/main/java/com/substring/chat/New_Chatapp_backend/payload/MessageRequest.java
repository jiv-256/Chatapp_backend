package com.substring.chat.New_Chatapp_backend.payload;


import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Setter
@Getter
@AllArgsConstructor
@NoArgsConstructor

public class MessageRequest {
    private String content;
    private String sender;
    private String avatar;
    private String roomId;
    private String clientTempId;
    private String replyToMessageId;
    private String replyToSender;
    private String replyToText;
    private boolean encrypted = false;
    private String iv;

    public MessageRequest(String content, String sender, String roomId) {
        this.content = content;
        this.sender = sender;
        this.roomId = roomId;
    }

    public MessageRequest(String content, String sender, String roomId, String avatar) {
        this.content = content;
        this.sender = sender;
        this.roomId = roomId;
        this.avatar = avatar;
    }
}

