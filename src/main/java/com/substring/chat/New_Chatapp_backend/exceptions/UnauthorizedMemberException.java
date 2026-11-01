package com.substring.chat.New_Chatapp_backend.exceptions;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.ResponseStatus;

@ResponseStatus(HttpStatus.UNAUTHORIZED)
public class UnauthorizedMemberException extends RuntimeException {
    public UnauthorizedMemberException(String message) {
        super(message);
    }
}
