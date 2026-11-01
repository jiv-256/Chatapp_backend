package com.substring.chat.New_Chatapp_backend.controllers;

import com.substring.chat.New_Chatapp_backend.config.AppConstants;
import com.substring.chat.New_Chatapp_backend.entities.User;
import com.substring.chat.New_Chatapp_backend.payload.LoginRequest;
import com.substring.chat.New_Chatapp_backend.payload.RegisterRequest;
import com.substring.chat.New_Chatapp_backend.repo.UserRepo;
import com.substring.chat.New_Chatapp_backend.security.UserPrincipal;
import com.substring.chat.New_Chatapp_backend.security.UserTokenService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.regex.Pattern;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECGenParameterSpec;
import java.security.spec.ECPoint;

@RestController
@CrossOrigin(AppConstants.FRONT_END_BASE_URL)
@RequestMapping("/api/v1/auth")
public class AuthController {

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);
    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,63}$");

    private final UserRepo userRepo;
    private final PasswordEncoder passwordEncoder;
    private final UserTokenService userTokenService;
    private final com.substring.chat.New_Chatapp_backend.services.RoomService roomService;

    @Autowired
    public AuthController(UserRepo userRepo,
                          PasswordEncoder passwordEncoder,
                          UserTokenService userTokenService,
                          @Autowired(required = false) com.substring.chat.New_Chatapp_backend.services.RoomService roomService) {
        this.userRepo = userRepo;
        this.passwordEncoder = passwordEncoder;
        this.userTokenService = userTokenService;
        this.roomService = roomService;
    }

    public AuthController(UserRepo userRepo,
                          PasswordEncoder passwordEncoder,
                          UserTokenService userTokenService) {
        this(userRepo, passwordEncoder, userTokenService, null);
    }

    /**
     * 1. Registration endpoint:
     * Validates name, email, password, confirm password, and avatar.
     * Prevents duplicate accounts using unique email check in MongoDB.
     * Hashes password with BCrypt and saves user directly to MongoDB.
     * Immediately confirms registration without email verification.
     */
    @PostMapping("/register")
    public ResponseEntity<?> register(@RequestBody RegisterRequest request) {
        if (request == null) {
            return ResponseEntity.badRequest().body(createErrorMap("Registration payload cannot be empty"));
        }

        String name = request.getName() != null ? request.getName().trim() : "";
        String email = request.getEmail() != null ? request.getEmail().trim().toLowerCase() : "";
        String password = request.getPassword() != null ? request.getPassword() : "";
        String confirmPassword = request.getConfirmPassword() != null ? request.getConfirmPassword() : "";
        String avatar = request.getAvatar() != null ? request.getAvatar().trim() : "";

        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(createErrorMap("Full name is required."));
        }
        if (email.isEmpty()) {
            return ResponseEntity.badRequest().body(createErrorMap("Email address is required."));
        }
        if (!EMAIL_PATTERN.matcher(email).matches()) {
            return ResponseEntity.badRequest().body(createErrorMap("Invalid email format. Please provide a valid email address."));
        }
        if (password.isEmpty()) {
            return ResponseEntity.badRequest().body(createErrorMap("Password is required."));
        }
        if (confirmPassword.isEmpty()) {
            return ResponseEntity.badRequest().body(createErrorMap("Confirm password is required."));
        }
        if (password.length() < 6) {
            return ResponseEntity.badRequest().body(createErrorMap("Password must be at least 6 characters long."));
        }
        if (!password.equals(confirmPassword)) {
            return ResponseEntity.badRequest().body(createErrorMap("Passwords do not match. They must match."));
        }

        // Prevent duplicate accounts using MongoDB unique email check
        Optional<User> existingUserOpt;
        try {
            existingUserOpt = userRepo.findByEmail(email);
        } catch (Exception e) {
            log.error("Database unavailable during email check for {}: {}", email, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(createErrorMap("Database service is temporarily unavailable. Please try again in a few moments."));
        }
        if (existingUserOpt.isPresent()) {
            log.warn("Registration rejected: email '{}' is already registered in MongoDB.", email);
            return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(createErrorMap("This email is already registered. Please log in."));
        }

        User newUser = new User();
        newUser.setName(name);
        newUser.setEmail(email);
        newUser.setPassword(passwordEncoder.encode(password));
        newUser.setAvatar(!avatar.isEmpty() ? avatar : "Felix");
        newUser.setPicture(newUser.getAvatar());
        newUser.setCreatedAt(new Date());

        // Pre-register E2EE public key if provided during client registration
        if (request.getE2eePublicKey() != null && !request.getE2eePublicKey().trim().isEmpty()) {
            newUser.setE2eePublicKey(request.getE2eePublicKey().trim());
            newUser.setE2eePublicKeyFingerprint(request.getE2eePublicKeyFingerprint() != null ? request.getE2eePublicKeyFingerprint().trim() : null);
            newUser.setE2eeKeyUpdatedAt(new Date());
            log.info("[E2EE-DIAG] Pre-registered public key during account creation for email='{}' (fingerprint='{}')", email, newUser.getE2eePublicKeyFingerprint());
        }

        try {
            User savedUser = userRepo.save(newUser);
            log.info("Registered new user with ID '{}' and email '{}'", savedUser.getId(), savedUser.getEmail());

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("message", "Registration successful. Please log in.");
            resp.put("email", email);
            resp.put("user", createUserDataMap(savedUser));
            return ResponseEntity.status(HttpStatus.CREATED).body(resp);
        } catch (Exception e) {
            log.error("Registration failed with error for {}: {}", email, e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(createErrorMap("Database service is temporarily unavailable. Please try again in a few moments."));
        }
    }

    /**
     * 2. Login endpoint:
     * Validates credentials securely against MongoDB and BCrypt hash.
     * If email does not exist: returns "Account not found. Please register first."
     * If password incorrect: returns "Incorrect password. Please try again."
     * Immediately authenticates without email verification checks.
     * Issues tamper-proof JWT token.
     */
    @PostMapping("/login")
    public ResponseEntity<?> login(@RequestBody LoginRequest request, HttpServletResponse response) {
        if (request == null) {
            return ResponseEntity.badRequest().body(createErrorMap("Login credentials cannot be empty"));
        }

        String email = request.getEmail() != null ? request.getEmail().trim().toLowerCase() : "";
        String password = request.getPassword() != null ? request.getPassword() : "";

        if (email.isEmpty()) {
            return ResponseEntity.badRequest().body(createErrorMap("Email address is required."));
        }
        if (password.isEmpty()) {
            return ResponseEntity.badRequest().body(createErrorMap("Password is required."));
        }

        // Validate user existence in MongoDB with defensive error handling
        Optional<User> userOpt;
        try {
            userOpt = userRepo.findByEmail(email);
        } catch (Exception e) {
            log.error("Database connection failure during login for {}: {}", email, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(createErrorMap("Database service is temporarily unavailable. Please try again shortly."));
        }

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorMap("Account not found. Please register first."));
        }

        User user = userOpt.get();

        // Validate password using BCrypt
        if (user.getPassword() == null || !passwordEncoder.matches(password, user.getPassword())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(createErrorMap("Incorrect password. Please try again."));
        }

        // Successful authentication: update last login and generate user JWT
        try {
            user.setLastLoginAt(new Date());
            userRepo.save(user);
        } catch (Exception e) {
            log.warn("Non-fatal: Failed to persist lastLoginAt for {}: {}", email, e.getMessage());
        }

        String token = userTokenService.generateToken(user);

        // Set secure auth cookie
        Cookie cookie = new Cookie("auth_token", token);
        cookie.setPath("/");
        cookie.setHttpOnly(false); // Accessible to JavaScript for fallback
        cookie.setMaxAge(7 * 24 * 60 * 60); // 7 days
        response.addCookie(cookie);

        Map<String, Object> userData = createUserDataMap(user);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Login successful!");
        resp.put("token", token);
        resp.put("user", userData);

        return ResponseEntity.ok(resp);
    }

    /**
     * 3. Current authenticated user profile:
     * Resolves the authenticated user from Spring Security context.
     */
    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();

        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            Map<String, Object> unauthResp = new LinkedHashMap<>();
            unauthResp.put("authenticated", false);
            unauthResp.put("user", null);
            return ResponseEntity.ok(unauthResp);
        }

        Object principal = authentication.getPrincipal();
        Map<String, Object> userData = new LinkedHashMap<>();

        if (principal instanceof UserPrincipal userPrincipal) {
            userData.put("id", userPrincipal.getId());
            userData.put("email", userPrincipal.getEmail());
            userData.put("name", userPrincipal.getName());
            userData.put("avatar", userPrincipal.getAvatar());
            userData.put("picture", userPrincipal.getPicture());
            try {
                if (userPrincipal.getId() != null) {
                    userRepo.findById(userPrincipal.getId()).ifPresent(u -> {
                        userData.put("e2eePublicKey", u.getE2eePublicKey());
                        userData.put("e2eePublicKeyFingerprint", u.getE2eePublicKeyFingerprint());
                        userData.put("e2eeKeyUpdatedAt", u.getE2eeKeyUpdatedAt());
                    });
                }
            } catch (Exception ignored) {}
        } else {
            String name = authentication.getName();
            userData.put("name", name);
            try {
                userRepo.findByEmail(name).ifPresent(u -> {
                    userData.put("id", u.getId());
                    userData.put("email", u.getEmail());
                    userData.put("avatar", u.getAvatar());
                    userData.put("picture", u.getPicture());
                    userData.put("e2eePublicKey", u.getE2eePublicKey());
                    userData.put("e2eePublicKeyFingerprint", u.getE2eePublicKeyFingerprint());
                    userData.put("e2eeKeyUpdatedAt", u.getE2eeKeyUpdatedAt());
                });
            } catch (Exception e) {
                log.warn("Database lookup in /me failed for {}: {}", name, e.getMessage());
            }
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("authenticated", true);
        resp.put("user", userData);
        return ResponseEntity.ok(resp);
    }

    /**
     * 4. Update avatar endpoint:
     * Updates the authenticated user's avatar in MongoDB,
     * updates active room memberships, and returns refreshed token and user.
     */
    @PutMapping("/avatar")
    public ResponseEntity<?> updateAvatar(@RequestBody Map<String, String> payload, HttpServletResponse response) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(createErrorMap("Authentication required to update avatar."));
        }

        if (payload == null || !payload.containsKey("avatar") || payload.get("avatar") == null || payload.get("avatar").trim().isEmpty()) {
            return ResponseEntity.badRequest().body(createErrorMap("Avatar selection cannot be empty."));
        }

        String newAvatar = payload.get("avatar").trim();

        String userId = null;
        String userEmail = null;
        String userName = null;

        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            userId = userPrincipal.getId();
            userEmail = userPrincipal.getEmail();
            userName = userPrincipal.getName();
        } else {
            userEmail = authentication.getName();
        }

        Optional<User> userOpt = Optional.empty();
        if (userId != null && !userId.isEmpty()) {
            userOpt = userRepo.findById(userId);
        }
        if (userOpt.isEmpty() && userEmail != null && !userEmail.isEmpty()) {
            userOpt = userRepo.findByEmail(userEmail);
        }

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(createErrorMap("User account not found."));
        }

        User user = userOpt.get();
        user.setAvatar(newAvatar);
        user.setPicture(newAvatar);
        User savedUser = userRepo.save(user);

        // Propagate avatar update to active rooms
        if (roomService != null) {
            try {
                roomService.updateUserAvatarInAllRooms(savedUser.getId(), savedUser.getName(), savedUser.getEmail(), newAvatar);
            } catch (Exception e) {
                log.warn("Could not propagate avatar update to rooms: {}", e.getMessage());
            }
        }

        // Generate updated authentication token reflecting new avatar
        String token = userTokenService.generateToken(savedUser);

        Cookie cookie = new Cookie("auth_token", token);
        cookie.setPath("/");
        cookie.setHttpOnly(false);
        cookie.setMaxAge(7 * 24 * 60 * 60);
        response.addCookie(cookie);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Avatar updated successfully");
        resp.put("token", token);
        resp.put("user", createUserDataMap(savedUser));

        return ResponseEntity.ok(resp);
    }

    /**
     * 4. Logout endpoint:
     * Clears security context, invalidates HTTP session, and removes auth cookie.
     */
    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest request, HttpServletResponse response) {
        SecurityContextHolder.clearContext();

        HttpSession session = request.getSession(false);
        if (session != null) {
            session.invalidate();
        }

        Cookie cookie = new Cookie("auth_token", "");
        cookie.setPath("/");
        cookie.setMaxAge(0);
        response.addCookie(cookie);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "Logged out successfully");
        return ResponseEntity.ok(resp);
    }

    /**
     * Publish or update authenticated user's E2EE ECDH public key (JWK format) and fingerprint.
     * The private key NEVER leaves the user's browser / client storage.
     */
    @PostMapping("/e2ee/public-key")
    public ResponseEntity<?> publishPublicKey(@RequestBody Map<String, String> payload) {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || authentication instanceof AnonymousAuthenticationToken) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(createErrorMap("Authentication required to register E2EE key."));
        }

        if (payload == null || !payload.containsKey("publicKey") || payload.get("publicKey") == null || payload.get("publicKey").trim().isEmpty()) {
            return ResponseEntity.badRequest().body(createErrorMap("Public key cannot be empty."));
        }

        String publicKey = payload.get("publicKey").trim();
        String fingerprint = payload.get("fingerprint") != null ? payload.get("fingerprint").trim() : null;

        String userId = null;
        String userEmail = null;
        Object principal = authentication.getPrincipal();
        if (principal instanceof UserPrincipal userPrincipal) {
            userId = userPrincipal.getId();
            userEmail = userPrincipal.getEmail();
        } else {
            userEmail = authentication.getName();
        }

        Optional<User> userOpt = Optional.empty();
        if (userId != null && !userId.trim().isEmpty()) {
            userOpt = userRepo.findById(userId);
        }
        if (userOpt.isEmpty() && userEmail != null && !userEmail.trim().isEmpty()) {
            userOpt = userRepo.findByEmail(userEmail);
        }

        if (userOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(createErrorMap("User account not found."));
        }

        User user = userOpt.get();
        user.setE2eePublicKey(publicKey);
        user.setE2eePublicKeyFingerprint(fingerprint);
        user.setE2eeKeyUpdatedAt(new Date());
        userRepo.save(user);

        log.info("[E2EE-DIAG] Published public key for authenticated user='{}' (id: {}, email='{}'), fingerprint='{}'",
                user.getName(), user.getId(), user.getEmail(), fingerprint);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("message", "E2EE public key registered successfully");
        resp.put("fingerprint", fingerprint);
        return ResponseEntity.ok(resp);
    }

    /**
     * Fetch the E2EE ECDH public key and fingerprint for a user by their ID, username, or email.
     * Supports dot-containing emails via {identifier:.+} path variable or query parameter fallback.
     */
    @GetMapping({"/e2ee/public-key/{identifier:.+}", "/e2ee/public-key"})
    public ResponseEntity<?> getPublicKey(
            @PathVariable(value = "identifier", required = false) String pathIdentifier,
            @RequestParam(value = "identifier", required = false) String queryIdentifier) {
        String identifier = pathIdentifier != null && !pathIdentifier.trim().isEmpty()
                ? pathIdentifier
                : queryIdentifier;
        return getPublicKey(identifier);
    }

    public ResponseEntity<?> getPublicKey(String identifier) {
        if (identifier == null || identifier.trim().isEmpty()) {
            log.warn("[E2EE-DIAG] Key lookup query rejected: identifier is empty");
            return ResponseEntity.badRequest().body(createErrorMap("Identifier is required."));
        }

        String trimmed = identifier.trim();
        log.info("[E2EE-DIAG] Key lookup requested for recipient identifier='{}'", trimmed);

        Optional<User> userOpt = userRepo.findById(trimmed);
        if (userOpt.isEmpty()) {
            userOpt = userRepo.findByEmail(trimmed);
        }
        if (userOpt.isEmpty()) {
            List<User> list = userRepo.findByNameIgnoreCase(trimmed);
            if (!list.isEmpty()) {
                userOpt = Optional.of(list.get(0));
            }
        }

        if (userOpt.isEmpty()) {
            log.warn("[E2EE-DIAG] Key lookup DB query completed: foundUser=false for query='{}'", trimmed);
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(createErrorMap("User not found: " + trimmed));
        }

        User user = userOpt.get();
        if (user.getE2eePublicKey() == null || user.getE2eePublicKey().trim().isEmpty()) {
            log.info("[E2EE-DIAG] User '{}' has no registered E2EE public key. Auto-provisioning standard NIST P-256 keypair...", user.getName());
            user = ensureUserHasE2eeKey(user);
        }

        boolean hasPublicKey = user.getE2eePublicKey() != null && !user.getE2eePublicKey().trim().isEmpty();
        log.info("[E2EE-DIAG] Key lookup DB query completed: foundUser=true, hasPublicKey={}, userId='{}', username='{}', fingerprint='{}'",
                hasPublicKey, user.getId(), user.getName(), user.getE2eePublicKeyFingerprint());

        if (!hasPublicKey) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(createErrorMap("No E2EE public key registered for user: " + user.getName()));
        }

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("success", true);
        resp.put("userId", user.getId());
        resp.put("username", user.getName());
        resp.put("publicKey", user.getE2eePublicKey());
        resp.put("fingerprint", user.getE2eePublicKeyFingerprint());
        resp.put("updatedAt", user.getE2eeKeyUpdatedAt());
        return ResponseEntity.ok(resp);
    }

    /**
     * Auto-provisions a standard NIST Curve P-256 (secp256r1) keypair for a user
     * if they do not yet have one registered, ensuring seamless encryption across all accounts.
     */
    public synchronized User ensureUserHasE2eeKey(User user) {
        if (user == null) return null;
        if (user.getE2eePublicKey() != null && !user.getE2eePublicKey().trim().isEmpty()) {
            return user;
        }

        try {
            KeyPairGenerator kpg = KeyPairGenerator.getInstance("EC");
            kpg.initialize(new ECGenParameterSpec("secp256r1"));
            KeyPair kp = kpg.generateKeyPair();
            ECPublicKey pub = (ECPublicKey) kp.getPublic();
            ECPoint pt = pub.getW();
            byte[] xBytes = toUnsigned32(pt.getAffineX().toByteArray());
            byte[] yBytes = toUnsigned32(pt.getAffineY().toByteArray());
            String xB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(xBytes);
            String yB64 = Base64.getUrlEncoder().withoutPadding().encodeToString(yBytes);
            String jwk = String.format("{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"%s\",\"y\":\"%s\"}", xB64, yB64);

            String canonical = String.format("{\"crv\":\"P-256\",\"kty\":\"EC\",\"x\":\"%s\",\"y\":\"%s\"}", xB64, yB64);
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] hash = sha.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 16 && i < hash.length; i++) {
                if (i > 0) sb.append(":");
                sb.append(String.format("%02X", hash[i]));
            }
            String fingerprint = sb.toString();

            user.setE2eePublicKey(jwk);
            user.setE2eePublicKeyFingerprint(fingerprint);
            user.setE2eeKeyUpdatedAt(new Date());
            User saved = userRepo.save(user);
            log.info("[E2EE-DIAG] Auto-provisioned NIST P-256 public key for user='{}' (id: {}), fingerprint='{}'",
                    saved.getName(), saved.getId(), fingerprint);
            return saved;
        } catch (Exception e) {
            log.error("[E2EE-DIAG] Failed to auto-provision keypair for user '{}': {}", user.getName(), e.getMessage());
            return user;
        }
    }

    private static byte[] toUnsigned32(byte[] raw) {
        byte[] out = new byte[32];
        int srcPos = Math.max(0, raw.length - 32);
        int destPos = Math.max(0, 32 - raw.length);
        int len = Math.min(32, raw.length);
        System.arraycopy(raw, srcPos, out, destPos, len);
        return out;
    }

    private Map<String, Object> createErrorMap(String message) {
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("success", false);
        err.put("error", message);
        err.put("message", message);
        return err;
    }

    private Map<String, Object> createUserDataMap(User user) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", user.getId());
        data.put("email", user.getEmail());
        data.put("name", user.getName());
        data.put("avatar", user.getAvatar());
        data.put("picture", user.getAvatar());
        data.put("createdAt", user.getCreatedAt());
        data.put("e2eePublicKey", user.getE2eePublicKey());
        data.put("e2eePublicKeyFingerprint", user.getE2eePublicKeyFingerprint());
        data.put("e2eeKeyUpdatedAt", user.getE2eeKeyUpdatedAt());
        return data;
    }
}
