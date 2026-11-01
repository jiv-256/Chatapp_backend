package com.substring.chat.New_Chatapp_backend.payload;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CallSignalMessage {
    /**
     * Signal type:
     * INITIATE, RINGING, ACCEPT, DECLINE, LEAVE, END,
     * OFFER, ANSWER, ICE_CANDIDATE, PING
     */
    private String type;

    private String callId;
    private String roomId;
    private String roomName;
    private boolean isGroup;

    /**
     * Call medium: "voice" or "video"
     */
    private String callType;

    /**
     * Initiator or sender identity
     */
    private String sender;
    private String senderName;
    private String senderAvatar;

    /**
     * Targeted peer recipient (null/empty for room-wide broadcast)
     */
    private String target;

    /**
     * WebRTC payload (SDP offer/answer or ICE candidate)
     */
    private Object data;

    private long timestamp;
}

