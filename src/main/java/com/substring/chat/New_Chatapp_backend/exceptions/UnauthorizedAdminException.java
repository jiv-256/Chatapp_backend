package com.substring.chat.New_Chatapp_backend.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.FORBIDDEN)
public class UnauthorizedAdminException extends RuntimeException {
    public UnauthorizedAdminException(String message) {
        super(message);
    }
}
