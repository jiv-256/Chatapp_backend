package com.substring.chat.New_Chatapp_backend.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.BAD_REQUEST)
public class InvalidRetentionException extends RuntimeException {
    public InvalidRetentionException(String message) {
        super(message);
    }
}
