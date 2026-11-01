package com.substring.chat.New_Chatapp_backend.services;

import com.substring.chat.New_Chatapp_backend.security.RoomSecurityService;
import com.substring.chat.New_Chatapp_backend.security.RoomTokenClaims;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RoomSecurityServiceTest {

    private RoomSecurityService securityService;

    @BeforeEach
    void setUp() {
        securityService = new RoomSecurityService("TestSecretKey_2026_RoomSecurityTestKey_SuperSecure12345");
    }

    @Test
    void testInviteCodeGenerationFormatAndEntropy() {
        String code1 = securityService.generateInviteCode();
        String code2 = securityService.generateInviteCode();

        assertThat(code1).isNotNull();
        assertThat(code2).isNotNull();
        assertThat(code1).isNotEqualTo(code2);
        // Formatted as XXXX-XXXX-XXXX
        assertThat(code1).matches("^[2-9A-Z]{4}-[2-9A-Z]{4}-[2-9A-Z]{4}$");
    }

    @Test
    void testHashingAndConstantTimeVerification() {
        String code = "K8P2-9F4A-M3X7";
        String hash = securityService.hashInviteCode(code);

        assertThat(hash).isNotNull().hasSize(64); // SHA-256 hex string

        // Valid code with or without formatting matches
        assertThat(securityService.verifyInviteCode("K8P2-9F4A-M3X7", hash)).isTrue();
        assertThat(securityService.verifyInviteCode("k8p29f4am3x7", hash)).isTrue();
        assertThat(securityService.verifyInviteCode("k8p2-9f4a-m3x7", hash)).isTrue();

        // Invalid codes rejected
        assertThat(securityService.verifyInviteCode("K8P2-9F4A-M3X8", hash)).isFalse();
        assertThat(securityService.verifyInviteCode("WRONG-CODE-1234", hash)).isFalse();
        assertThat(securityService.verifyInviteCode("", hash)).isFalse();
        assertThat(securityService.verifyInviteCode(null, hash)).isFalse();
    }

    @Test
    void testEncryptionAndDecryptionRoundtrip() {
        String originalCode = "A4B7-K92X-P3M8";
        String encrypted = securityService.encryptInviteCode(originalCode);

        // Plaintext is NOT in the encrypted string
        assertThat(encrypted).isNotNull().isNotEqualTo(originalCode);
        assertThat(encrypted).doesNotContain(originalCode);

        String decrypted = securityService.decryptInviteCode(encrypted);
        assertThat(decrypted).isEqualTo(originalCode);
    }

    @Test
    void testTokenGenerationAndValidation() {
        String token = securityService.generateToken("room-101", "Alice", true);
        assertThat(token).isNotNull().contains(".");

        RoomTokenClaims claims = securityService.validateToken(token, "room-101");
        assertThat(claims).isNotNull();
        assertThat(claims.getUsername()).isEqualTo("Alice");
        assertThat(claims.getRoomId()).isEqualTo("room-101");
        assertThat(claims.isAdmin()).isTrue();
        assertThat(claims.getRole()).isEqualTo("ADMIN");

        // Wrong room returns null
        assertThat(securityService.validateToken(token, "wrong-room")).isNull();

        // Tampered token returns null
        String tampered = token + "xyz";
        assertThat(securityService.validateToken(tampered, "room-101")).isNull();

        // Random string returns null
        assertThat(securityService.validateToken("not-a-real-token", "room-101")).isNull();
        assertThat(securityService.validateToken(null, "room-101")).isNull();
    }

    @Test
    void testCodeTicketGenerationAndValidationRoundtrip() {
        String code = "K8P2-9F4A-M3X7";
        String ticket = securityService.generateCodeTicket("design-room", "Bob", code);

        assertThat(ticket).isNotNull().contains(".");

        String verifiedCode = securityService.validateCodeTicket(ticket, "design-room", "Bob");
        assertThat(verifiedCode).isEqualTo(code);
    }

    @Test
    void testCodeTicketRejectsTamperedOrMismatchedTickets() {
        String code = "K8P2-9F4A-M3X7";
        String ticket = securityService.generateCodeTicket("design-room", "Bob", code);

        // Mismatched room ID
        assertThat(securityService.validateCodeTicket(ticket, "different-room", "Bob")).isNull();

        // Mismatched creator
        assertThat(securityService.validateCodeTicket(ticket, "design-room", "Charlie")).isNull();

        // Tampered ticket payload or signature
        assertThat(securityService.validateCodeTicket(ticket + "tampered", "design-room", "Bob")).isNull();
        assertThat(securityService.validateCodeTicket("invalid.ticket", "design-room", "Bob")).isNull();
        assertThat(securityService.validateCodeTicket(null, "design-room", "Bob")).isNull();
    }
}
