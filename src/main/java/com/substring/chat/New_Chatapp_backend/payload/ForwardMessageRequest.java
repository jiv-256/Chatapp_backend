package com.substring.chat.New_Chatapp_backend.payload;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import java.util.List;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class ForwardMessageRequest {
    private String messageId;
    private List<String> targetRoomIds;
    private List<String> targetUserIds;
}
