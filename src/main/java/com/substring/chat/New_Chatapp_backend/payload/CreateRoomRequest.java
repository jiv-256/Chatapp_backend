package com.substring.chat.New_Chatapp_backend.payload;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CreateRoomRequest {
    private String roomId;
    private String roomName;
    private String creator;
    private String admin;
    private String avatar;
    private String codeTicket;

    public CreateRoomRequest(String roomId, String creator) {
        this.roomId = roomId;
        this.creator = creator;
    }

    public CreateRoomRequest(String roomId, String creator, String codeTicket) {
        this.roomId = roomId;
        this.creator = creator;
        this.codeTicket = codeTicket;
    }

    public String getEffectiveCreator() {
        if (creator != null && !creator.trim().isEmpty()) {
            return creator.trim();
        }
        if (admin != null && !admin.trim().isEmpty()) {
            return admin.trim();
        }
        return null;
    }
}
