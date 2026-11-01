package com.substring.chat.New_Chatapp_backend.security;

import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

@Service
public class RoomSecurityService {

    private static final String CODE_CHARS = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final int CODE_LENGTH = 12;
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH = 128;
    private static final long TOKEN_VALIDITY_MILLIS = 30L * 24L * 60L * 60L * 1000L; // 30 days

    private final SecureRandom secureRandom = new SecureRandom();
    private final byte[] secretKeyBytes;

    public RoomSecurityService(@Value("${app.security.secret:ChatAppSecureKey_MongoAtlasFreeTier_2026_SecureKey}") String secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            this.secretKeyBytes = sha.digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize cryptographic secret", e);
        }
    }

    /**
     * Generates a high-entropy, unpredictable invitation code (e.g. K8P2-9F4A-M3X7).
     */
    public String generateInviteCode() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < CODE_LENGTH; i++) {
            if (i > 0 && i % 4 == 0) {
                sb.append('-');
            }
            int index = secureRandom.nextInt(CODE_CHARS.length());
            sb.append(CODE_CHARS.charAt(index));
        }
        return sb.toString();
    }

    /**
     * Generates a cryptographically strong, opaque 256-bit URL-safe invitation token.
     */
    public String generateInviteToken() {
        byte[] tokenBytes = new byte[32];
        secureRandom.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    /**
     * Computes the SHA-256 cryptographic hash of an opaque invitation token.
     */
    public String hashInviteToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            return "";
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(token.trim().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Error computing invite token hash", e);
        }
    }

    /**
     * Normalizes the invite code (removes whitespace/hyphens and converts to uppercase).
     */
    public String normalizeCode(String code) {
        if (code == null) return "";
        return code.replaceAll("[^A-Za-z0-9]", "").toUpperCase();
    }

    /**
     * Computes the SHA-256 cryptographic hash of a normalized invitation code.
     */
    public String hashInviteCode(String code) {
        try {
            String normalized = normalizeCode(code);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalized.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (Exception e) {
            throw new RuntimeException("Error computing invite code hash", e);
        }
    }

    /**
     * Validates an incoming invite code against a stored SHA-256 hash in constant time.
     */
    public boolean verifyInviteCode(String incomingCode, String storedHash) {
        if (incomingCode == null || storedHash == null) {
            return false;
        }
        String incomingHash = hashInviteCode(incomingCode);
        return MessageDigest.isEqual(
                incomingHash.getBytes(StandardCharsets.UTF_8),
                storedHash.getBytes(StandardCharsets.UTF_8)
        );
    }

    /**
     * Encrypts the invitation code using AES-256-GCM.
     * Stored in MongoDB so the plaintext is never stored in Atlas, while allowing Admin decryption.
     */
    public String encryptInviteCode(String plainCode) {
        try {
            byte[] iv = new byte[GCM_IV_LENGTH];
            secureRandom.nextBytes(iv);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(secretKeyBytes, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.ENCRYPT_MODE, keySpec, gcmSpec);

            byte[] cipherText = cipher.doFinal(plainCode.getBytes(StandardCharsets.UTF_8));

            byte[] combined = new byte[iv.length + cipherText.length];
            System.arraycopy(iv, 0, combined, 0, iv.length);
            System.arraycopy(cipherText, 0, combined, iv.length, cipherText.length);

            return Base64.getUrlEncoder().withoutPadding().encodeToString(combined);
        } catch (Exception e) {
            throw new RuntimeException("Failed to encrypt invite code", e);
        }
    }

    /**
     * Decrypts the AES-256-GCM encrypted invitation code for the room Admin.
     */
    public String decryptInviteCode(String encryptedData) {
        try {
            byte[] combined = Base64.getUrlDecoder().decode(encryptedData);
            if (combined.length < GCM_IV_LENGTH) {
                throw new IllegalArgumentException("Invalid encrypted invite code format");
            }

            byte[] iv = new byte[GCM_IV_LENGTH];
            System.arraycopy(combined, 0, iv, 0, GCM_IV_LENGTH);

            int cipherTextLen = combined.length - GCM_IV_LENGTH;
            byte[] cipherText = new byte[cipherTextLen];
            System.arraycopy(combined, GCM_IV_LENGTH, cipherText, 0, cipherTextLen);

            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            SecretKeySpec keySpec = new SecretKeySpec(secretKeyBytes, "AES");
            GCMParameterSpec gcmSpec = new GCMParameterSpec(GCM_TAG_LENGTH, iv);
            cipher.init(Cipher.DECRYPT_MODE, keySpec, gcmSpec);

            byte[] decrypted = cipher.doFinal(cipherText);
            return new String(decrypted, StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new RuntimeException("Failed to decrypt invite code", e);
        }
    }

    /**
     * Issues a signed, tamper-proof room membership token (HMAC-SHA256).
     */
    public String generateToken(String roomId, String username, boolean isAdmin) {
        try {
            long now = System.currentTimeMillis();
            long exp = now + TOKEN_VALIDITY_MILLIS;

            String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
            String payloadJson = String.format(
                    "{\"sub\":\"%s\",\"roomId\":\"%s\",\"role\":\"%s\",\"admin\":%b,\"iat\":%d,\"exp\":%d}",
                    escapeJson(username),
                    escapeJson(roomId),
                    isAdmin ? "ADMIN" : "MEMBER",
                    isAdmin,
                    now,
                    exp
            );

            String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
            String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            String dataToSign = encodedHeader + "." + encodedPayload;
            String signature = signData(dataToSign);

            return dataToSign + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate authentication token", e);
        }
    }

    /**
     * Validates a room membership token, checks signature, expiration, and roomId match.
     */
    public RoomTokenClaims validateToken(String token, String expectedRoomId) {
        if (token == null || token.trim().isEmpty()) {
            return null;
        }

        String cleanedToken = token.trim();
        if (cleanedToken.startsWith("Bearer ") || cleanedToken.startsWith("bearer ")) {
            cleanedToken = cleanedToken.substring(7).trim();
        }

        String[] parts = cleanedToken.split("\\.");
        if (parts.length != 3) {
            return null;
        }

        String dataToVerify = parts[0] + "." + parts[1];
        String expectedSignature = signData(dataToVerify);

        if (!MessageDigest.isEqual(expectedSignature.getBytes(StandardCharsets.UTF_8), parts[2].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }

        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[1]);
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
            Document doc = Document.parse(payloadJson);

            long exp = doc.getLong("exp");
            if (System.currentTimeMillis() > exp) {
                return null;
            }

            String tokenRoomId = doc.getString("roomId");
            if (tokenRoomId == null || tokenRoomId.trim().isEmpty()) {
                return null;
            }
            if (expectedRoomId != null && !expectedRoomId.trim().equalsIgnoreCase(tokenRoomId.trim())) {
                return null;
            }

            String username = doc.getString("sub");
            String role = doc.getString("role");
            Boolean admin = doc.getBoolean("admin");

            return new RoomTokenClaims(username, tokenRoomId, role, Boolean.TRUE.equals(admin));
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Generates a signed, tamper-proof creation ticket binding roomId, creator, and inviteCode.
     * Prevents forgery of invitation codes and ensures backend validation during room creation.
     */
    public String generateCodeTicket(String roomId, String creator, String inviteCode) {
        try {
            long now = System.currentTimeMillis();
            long exp = now + 15L * 60L * 1000L; // 15 minutes validity for room creation

            String payloadJson = String.format(
                    "{\"roomId\":\"%s\",\"creator\":\"%s\",\"code\":\"%s\",\"iat\":%d,\"exp\":%d}",
                    escapeJson(roomId != null ? roomId.trim() : ""),
                    escapeJson(creator != null ? creator.trim() : ""),
                    escapeJson(inviteCode != null ? inviteCode.trim() : ""),
                    now,
                    exp
            );

            String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));
            String signature = signData(encodedPayload);

            return encodedPayload + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate invitation code ticket", e);
        }
    }

    /**
     * Validates a creation ticket and extracts the verified invitation code.
     * Returns null if ticket is invalid, expired, or doesn't match roomId/creator.
     */
    public String validateCodeTicket(String ticket, String expectedRoomId, String expectedCreator) {
        if (ticket == null || ticket.trim().isEmpty()) {
            return null;
        }

        String[] parts = ticket.trim().split("\\.");
        if (parts.length != 2) {
            return null;
        }

        String expectedSig = signData(parts[0]);
        if (!MessageDigest.isEqual(expectedSig.getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }

        try {
            byte[] payloadBytes = Base64.getUrlDecoder().decode(parts[0]);
            String payloadJson = new String(payloadBytes, StandardCharsets.UTF_8);
            Document doc = Document.parse(payloadJson);

            long exp = doc.getLong("exp");
            if (System.currentTimeMillis() > exp) {
                return null;
            }

            String ticketRoomId = doc.getString("roomId");
            if (expectedRoomId != null && !expectedRoomId.trim().equalsIgnoreCase(ticketRoomId != null ? ticketRoomId.trim() : "")) {
                return null;
            }

            String ticketCreator = doc.getString("creator");
            if (expectedCreator != null && !expectedCreator.trim().isEmpty() && ticketCreator != null && !ticketCreator.trim().isEmpty()) {
                if (!expectedCreator.trim().equalsIgnoreCase(ticketCreator.trim())) {
                    return null;
                }
            }

            return doc.getString("code");
        } catch (Exception e) {
            return null;
        }
    }

    private String signData(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec secretKey = new SecretKeySpec(secretKeyBytes, "HmacSHA256");
            mac.init(secretKey);
            byte[] signatureBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signatureBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC signature", e);
        }
    }

    private String escapeJson(String input) {
        if (input == null) return "";
        return input.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
