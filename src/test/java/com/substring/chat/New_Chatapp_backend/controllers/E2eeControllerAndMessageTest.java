package com.substring.chat.New_Chatapp_backend.controllers;

import com.substring.chat.New_Chatapp_backend.entities.Message;
import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.entities.RoomMember;
import com.substring.chat.New_Chatapp_backend.entities.User;
import com.substring.chat.New_Chatapp_backend.payload.MessageRequest;
import com.substring.chat.New_Chatapp_backend.repo.MessageRepo;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import com.substring.chat.New_Chatapp_backend.repo.UserRepo;
import com.substring.chat.New_Chatapp_backend.security.RoomSecurityService;
import com.substring.chat.New_Chatapp_backend.security.UserPrincipal;
import com.substring.chat.New_Chatapp_backend.security.UserTokenService;
import com.substring.chat.New_Chatapp_backend.services.MessageService;
import com.substring.chat.New_Chatapp_backend.services.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class E2eeControllerAndMessageTest {

    @Mock
    private UserRepo userRepo;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private UserTokenService userTokenService;

    @Mock
    private RoomRepo roomRepo;

    @Mock
    private MessageRepo messageRepo;

    private AuthController authController;
    private MessageService messageService;

    @BeforeEach
    void setUp() {
        authController = new AuthController(userRepo, passwordEncoder, userTokenService);
        messageService = new MessageService(messageRepo, roomRepo);
        SecurityContextHolder.clearContext();
    }

    @Test
    void testPublishPublicKeyUnauthorized() {
        Map<String, String> payload = Map.of("publicKey", "{\"kty\":\"EC\"}", "fingerprint", "fp123");
        ResponseEntity<?> resp = authController.publishPublicKey(payload);
        assertEquals(HttpStatus.UNAUTHORIZED, resp.getStatusCode());
    }

    @Test
    void testPublishPublicKeyAuthenticated() {
        User user = new User();
        user.setId("u-alice");
        user.setName("Alice");
        user.setEmail("alice@example.com");

        UserPrincipal principal = UserPrincipal.create(user);
        UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        when(userRepo.findById("u-alice")).thenReturn(Optional.of(user));
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        Map<String, String> payload = Map.of(
                "publicKey", "{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"abc\",\"y\":\"def\"}",
                "fingerprint", "12345 67890"
        );

        ResponseEntity<?> resp = authController.publishPublicKey(payload);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        assertEquals("{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"abc\",\"y\":\"def\"}", user.getE2eePublicKey());
        assertEquals("12345 67890", user.getE2eePublicKeyFingerprint());
        assertNotNull(user.getE2eeKeyUpdatedAt());
    }

    @Test
    void testGetPublicKey() {
        User bob = new User();
        bob.setId("u-bob");
        bob.setName("Bob");
        bob.setEmail("bob@example.com");
        bob.setE2eePublicKey("{\"kty\":\"EC\",\"crv\":\"P-256\"}");
        bob.setE2eePublicKeyFingerprint("99999 88888");
        bob.setE2eeKeyUpdatedAt(new Date());

        when(userRepo.findById("u-bob")).thenReturn(Optional.of(bob));
        when(userRepo.findById("unknown")).thenReturn(Optional.empty());
        when(userRepo.findByEmail("unknown")).thenReturn(Optional.empty());
        when(userRepo.findByNameIgnoreCase("unknown")).thenReturn(Collections.emptyList());

        ResponseEntity<?> notFound = authController.getPublicKey("unknown");
        assertEquals(HttpStatus.NOT_FOUND, notFound.getStatusCode());

        ResponseEntity<?> found = authController.getPublicKey("u-bob");
        assertEquals(HttpStatus.OK, found.getStatusCode());
        Map<?, ?> body = (Map<?, ?>) found.getBody();
        assertNotNull(body);
        assertEquals("u-bob", body.get("userId"));
        assertEquals("Bob", body.get("username"));
        assertEquals("{\"kty\":\"EC\",\"crv\":\"P-256\"}", body.get("publicKey"));
        assertEquals("99999 88888", body.get("fingerprint"));

        // Query parameter fallback test
        ResponseEntity<?> foundViaParam = authController.getPublicKey(null, "u-bob");
        assertEquals(HttpStatus.OK, foundViaParam.getStatusCode());

        // Email lookup test
        when(userRepo.findById("bob@example.com")).thenReturn(Optional.empty());
        when(userRepo.findByEmail("bob@example.com")).thenReturn(Optional.of(bob));
        ResponseEntity<?> foundViaEmail = authController.getPublicKey("bob@example.com");
        assertEquals(HttpStatus.OK, foundViaEmail.getStatusCode());
    }

    @Test
    void testRegisterWithPreGeneratedPublicKey() {
        when(userRepo.findByEmail("charlie@example.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("secret123")).thenReturn("hashed-pass");
        when(userRepo.save(any(User.class))).thenAnswer(i -> {
            User u = i.getArgument(0);
            u.setId("u-charlie");
            return u;
        });

        com.substring.chat.New_Chatapp_backend.payload.RegisterRequest req =
                new com.substring.chat.New_Chatapp_backend.payload.RegisterRequest();
        req.setName("Charlie");
        req.setEmail("charlie@example.com");
        req.setPassword("secret123");
        req.setConfirmPassword("secret123");
        req.setAvatar("Felix");
        req.setE2eePublicKey("{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"charlieX\",\"y\":\"charlieY\"}");
        req.setE2eePublicKeyFingerprint("55555 44444");

        ResponseEntity<?> resp = authController.register(req);
        assertEquals(HttpStatus.CREATED, resp.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertNotNull(body);
        Map<?, ?> userMap = (Map<?, ?>) body.get("user");
        assertNotNull(userMap);
        assertEquals("{\"kty\":\"EC\",\"crv\":\"P-256\",\"x\":\"charlieX\",\"y\":\"charlieY\"}", userMap.get("e2eePublicKey"));
        assertEquals("55555 44444", userMap.get("e2eePublicKeyFingerprint"));
    }

    @Test
    void testAutoProvisionKeypairWhenPublicKeyMissing() {
        User dave = new User();
        dave.setId("u-dave");
        dave.setName("Dave");
        dave.setEmail("dave@example.com");
        dave.setE2eePublicKey(null); // No public key in database!

        when(userRepo.findById("u-dave")).thenReturn(Optional.of(dave));
        when(userRepo.save(any(User.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<?> resp = authController.getPublicKey("u-dave");
        assertEquals(HttpStatus.OK, resp.getStatusCode());

        Map<?, ?> body = (Map<?, ?>) resp.getBody();
        assertNotNull(body);
        assertNotNull(body.get("publicKey"));
        String pubKey = (String) body.get("publicKey");
        assertTrue(pubKey.contains("\"crv\":\"P-256\""));
        assertTrue(pubKey.contains("\"kty\":\"EC\""));
        assertTrue(pubKey.contains("\"x\""));
        assertTrue(pubKey.contains("\"y\""));
        assertNotNull(body.get("fingerprint"));
    }

    @Test
    void testE2eeEnforcedOnlyForDirectRooms() {
        // Direct Room
        Room directRoom = new Room();
        directRoom.setRoomId("direct-alice-bob");
        directRoom.setRoomName("Private Chat");
        directRoom.setRoomType("DIRECT");
        directRoom.setMembers(new ArrayList<>(List.of(
                new RoomMember("u-alice", "Alice", "MEMBER", "Felix"),
                new RoomMember("u-bob", "Bob", "MEMBER", "Felix")
        )));

        // Group Room
        Room groupRoom = new Room();
        groupRoom.setRoomId("group-tech");
        groupRoom.setRoomName("Tech Group");
        groupRoom.setRoomType("GROUP");
        groupRoom.setMembers(new ArrayList<>(List.of(
                new RoomMember("u-alice", "Alice", "MEMBER", "Felix")
        )));

        when(roomRepo.findByRoomId("direct-alice-bob")).thenReturn(directRoom);
        when(roomRepo.findByRoomId("group-tech")).thenReturn(groupRoom);
        when(messageRepo.save(any(Message.class))).thenAnswer(i -> {
            Message m = i.getArgument(0);
            m.setId("m-" + System.currentTimeMillis());
            return m;
        });

        // Test sending encrypted message to DIRECT room
        Message directMsg = messageService.createMessage(
                "direct-alice-bob", "Alice", "ENCRYPTED_CIPHERTEXT_BASE64", "temp-1", "Felix",
                null, null, null, true, "IV_BASE64_96BIT"
        );
        assertTrue(directMsg.isEncrypted());
        assertEquals("IV_BASE64_96BIT", directMsg.getIv());
        assertEquals("ENCRYPTED_CIPHERTEXT_BASE64", directMsg.getContent());

        // Test sending encrypted flag to GROUP room -> MUST NOT be encrypted (strictly direct chats only)
        Message groupMsg = messageService.createMessage(
                "group-tech", "Alice", "Hello Group", "temp-2", "Felix",
                null, null, null, true, "IV_BASE64"
        );
        assertFalse(groupMsg.isEncrypted());
        assertNull(groupMsg.getIv());
        assertEquals("Hello Group", groupMsg.getContent());
    }

    @Test
    void testReplyToEncryptedMessageDoesNotLeakCiphertext() {
        Room directRoom = new Room();
        directRoom.setRoomId("direct-alice-bob");
        directRoom.setRoomType("DIRECT");
        directRoom.setMembers(new ArrayList<>(List.of(
                new RoomMember("u-alice", "Alice", "MEMBER", "Felix"),
                new RoomMember("u-bob", "Bob", "MEMBER", "Felix")
        )));
        when(roomRepo.findByRoomId("direct-alice-bob")).thenReturn(directRoom);

        Message originalEncrypted = new Message();
        originalEncrypted.setId("orig-enc-123");
        originalEncrypted.setRoomId("direct-alice-bob");
        originalEncrypted.setSender("Bob");
        originalEncrypted.setContent("RAW_CIPHERTEXT_BYTES_SECRET");
        originalEncrypted.setEncrypted(true);
        originalEncrypted.setIv("iv123");
        originalEncrypted.setCreatedAt(new Date());

        when(messageRepo.findById("orig-enc-123")).thenReturn(Optional.of(originalEncrypted));
        when(messageRepo.save(any(Message.class))).thenAnswer(i -> i.getArgument(0));

        Message replyMsg = messageService.createMessage(
                "direct-alice-bob", "Alice", "CIPHERTEXT_REPLY", "temp-3", "Felix",
                "orig-enc-123", "Bob", "Raw text fallback", true, "iv456"
        );

        assertEquals("orig-enc-123", replyMsg.getReplyToMessageId());
        assertEquals("Bob", replyMsg.getReplyToSender());
        // Reply preview text MUST NOT be raw ciphertext
        assertEquals("🔒 Encrypted message", replyMsg.getReplyToText());
    }
}

