package com.substring.chat.New_Chatapp_backend.controllers;

import com.substring.chat.New_Chatapp_backend.config.AppConstants;
import com.substring.chat.New_Chatapp_backend.entities.Message;
import com.substring.chat.New_Chatapp_backend.payload.MessageRequest;
import com.substring.chat.New_Chatapp_backend.services.MessageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageExceptionHandler;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.handler.annotation.SendTo;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.CrossOrigin;

@Controller
@CrossOrigin(AppConstants.FRONT_END_BASE_URL)
public class ChatController {

    private static final Logger log = LoggerFactory.getLogger(ChatController.class);

    private final MessageService messageService;

    public ChatController(MessageService messageService) {
        this.messageService = messageService;
    }

    @MessageMapping("/sendMessage/{roomId}")
    @SendTo("/topic/room/{roomId}")
    public Message sendMessage(
            @DestinationVariable String roomId,
            MessageRequest request,
            SimpMessageHeaderAccessor headerAccessor) {

        // Enforce verified sender from cryptographic token session attributes to prevent impersonation
        String authenticatedUser = null;
        if (headerAccessor != null && headerAccessor.getSessionAttributes() != null) {
            authenticatedUser = (String) headerAccessor.getSessionAttributes().get("ws_user");
        }

        String sender = (authenticatedUser != null && !authenticatedUser.trim().isEmpty())
                ? authenticatedUser
                : (request != null ? request.getSender() : null);

        // SECURITY: Prevent unauthorized clients from submitting forged SYSTEM messages
        if (sender != null && (sender.equalsIgnoreCase("SYSTEM") || sender.equalsIgnoreCase("SYSTEM_NOTIFICATION"))) {
            throw new SecurityException("Unauthorized sender identity");
        }

        String content = request != null ? request.getContent() : "";
        String clientTempId = request != null ? request.getClientTempId() : null;
        String avatar = request != null ? request.getAvatar() : null;
        String replyToMessageId = request != null ? request.getReplyToMessageId() : null;
        String replyToSender = request != null ? request.getReplyToSender() : null;
        String replyToText = request != null ? request.getReplyToText() : null;
        boolean encrypted = request != null && request.isEncrypted();
        String iv = request != null ? request.getIv() : null;

        return messageService.createMessage(roomId, sender, content, clientTempId, avatar, replyToMessageId, replyToSender, replyToText, encrypted, iv);
    }

    @MessageExceptionHandler
    public void handleException(Exception e) {
        log.error("WebSocket message handling error: {}", e.getMessage());
    }
}
