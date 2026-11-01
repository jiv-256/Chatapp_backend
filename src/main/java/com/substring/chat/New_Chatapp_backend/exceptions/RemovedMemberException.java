package com.substring.chat.New_Chatapp_backend.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class RemovedMemberException extends RuntimeException {
    public RemovedMemberException(String message) {
        super(message);
    }
}
