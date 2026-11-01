package com.substring.chat.New_Chatapp_backend.security;

import com.substring.chat.New_Chatapp_backend.entities.User;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Date;

import static org.junit.jupiter.api.Assertions.*;

class UserTokenServiceTest {

    private UserTokenService userTokenService;

    @BeforeEach
    void setUp() {
        userTokenService = new UserTokenService("TestSecretKeyForUserAuthentication_2026_Mock");
    }

    @Test
    void testGenerateAndValidateTokenSuccess() {
        User user = new User();
        user.setId("usr-123");
        user.setEmail("alice@example.com");
        user.setName("Alice Cooper");
        user.setPicture("https://example.com/avatar.png");
        user.setGoogleId("google-sub-456");
        user.setCreatedAt(new Date());

        String token = userTokenService.generateToken(user);
        assertNotNull(token);
        assertTrue(token.split("\\.").length == 3);

        UserTokenClaims claims = userTokenService.validateToken(token);
        assertNotNull(claims);
        assertEquals("usr-123", claims.getUserId());
        assertEquals("alice@example.com", claims.getEmail());
        assertEquals("Alice Cooper", claims.getName());
        assertEquals("https://example.com/avatar.png", claims.getPicture());
        assertEquals("google-sub-456", claims.getGoogleId());
    }

    @Test
    void testValidateTokenWithBearerPrefix() {
        User user = new User();
        user.setId("usr-789");
        user.setEmail("bob@example.com");
        user.setName("Bob Dylan");

        String token = userTokenService.generateToken(user);
        UserTokenClaims claims = userTokenService.validateToken("Bearer " + token);
        assertNotNull(claims);
        assertEquals("bob@example.com", claims.getEmail());
    }

    @Test
    void testTamperedTokenFails() {
        User user = new User();
        user.setId("usr-123");
        user.setEmail("alice@example.com");
        user.setName("Alice");

        String token = userTokenService.generateToken(user);
        String[] parts = token.split("\\.");
        // Modify payload slightly
        String tamperedToken = parts[0] + ".eyJhIjoxfQ." + parts[2];

        UserTokenClaims claims = userTokenService.validateToken(tamperedToken);
        assertNull(claims, "Tampered token signature verification must return null");
    }

    @Test
    void testInvalidOrEmptyTokenReturnsNull() {
        assertNull(userTokenService.validateToken(null));
        assertNull(userTokenService.validateToken(""));
        assertNull(userTokenService.validateToken("   "));
        assertNull(userTokenService.validateToken("invalid.token"));
    }
}
