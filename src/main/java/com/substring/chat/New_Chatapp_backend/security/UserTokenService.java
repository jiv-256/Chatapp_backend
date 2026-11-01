package com.substring.chat.New_Chatapp_backend.security;

import com.substring.chat.New_Chatapp_backend.entities.User;
import org.bson.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;

@Service
public class UserTokenService {

    private static final long USER_TOKEN_VALIDITY_MILLIS = 7L * 24L * 60L * 60L * 1000L; // 7 days
    private final byte[] secretKeyBytes;

    public UserTokenService(@Value("${app.security.secret:ChatAppSecureKey_MongoAtlasFreeTier_2026_SecureKey}") String secret) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            this.secretKeyBytes = sha.digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize cryptographic secret", e);
        }
    }

    /**
     * Issues a signed, tamper-proof user authentication token (HMAC-SHA256).
     */
    public String generateToken(User user) {
        if (user == null) {
            throw new IllegalArgumentException("User cannot be null");
        }
        try {
            long now = System.currentTimeMillis();
            long exp = now + USER_TOKEN_VALIDITY_MILLIS;

            String headerJson = "{\"alg\":\"HS256\",\"typ\":\"JWT\"}";
            String payloadJson = String.format(
                    "{\"sub\":\"%s\",\"email\":\"%s\",\"name\":\"%s\",\"picture\":\"%s\",\"avatar\":\"%s\",\"googleId\":\"%s\",\"iat\":%d,\"exp\":%d}",
                    escapeJson(user.getId() != null ? user.getId() : ""),
                    escapeJson(user.getEmail() != null ? user.getEmail() : ""),
                    escapeJson(user.getName() != null ? user.getName() : ""),
                    escapeJson(user.getPicture() != null ? user.getPicture() : ""),
                    escapeJson(user.getAvatar() != null ? user.getAvatar() : ""),
                    escapeJson(user.getGoogleId() != null ? user.getGoogleId() : ""),
                    now,
                    exp
            );

            String encodedHeader = Base64.getUrlEncoder().withoutPadding().encodeToString(headerJson.getBytes(StandardCharsets.UTF_8));
            String encodedPayload = Base64.getUrlEncoder().withoutPadding().encodeToString(payloadJson.getBytes(StandardCharsets.UTF_8));

            String dataToSign = encodedHeader + "." + encodedPayload;
            String signature = signData(dataToSign);

            return dataToSign + "." + signature;
        } catch (Exception e) {
            throw new RuntimeException("Failed to generate user authentication token", e);
        }
    }

    /**
     * Validates a user authentication token, checks signature and expiration.
     */
    public UserTokenClaims validateToken(String token) {
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

            String userId = doc.getString("sub");
            String email = doc.getString("email");
            String name = doc.getString("name");
            String picture = doc.getString("picture");
            String avatar = doc.getString("avatar");
            String googleId = doc.getString("googleId");

            UserTokenClaims claims = new UserTokenClaims(userId, email, name, picture, googleId);
            if (avatar != null && !avatar.trim().isEmpty()) {
                claims.setAvatar(avatar);
            }
            return claims;
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
        return input.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "\\r");
    }
}
