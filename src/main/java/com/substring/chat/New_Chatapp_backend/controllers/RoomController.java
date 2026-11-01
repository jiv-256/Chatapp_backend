package com.substring.chat.New_Chatapp_backend.controllers;

import com.substring.chat.New_Chatapp_backend.config.AppConstants;
import com.substring.chat.New_Chatapp_backend.entities.JoinRequest;
import com.substring.chat.New_Chatapp_backend.entities.Message;
import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.entities.RoomMember;
import com.substring.chat.New_Chatapp_backend.exceptions.InvalidInviteCodeException;
import com.substring.chat.New_Chatapp_backend.exceptions.InvalidRetentionException;
import com.substring.chat.New_Chatapp_backend.exceptions.RemovedMemberException;
import com.substring.chat.New_Chatapp_backend.exceptions.UnauthorizedAdminException;
import com.substring.chat.New_Chatapp_backend.payload.AuthResponse;
import com.substring.chat.New_Chatapp_backend.payload.JoinInviteRequest;
import com.substring.chat.New_Chatapp_backend.payload.JoinRoomRequest;
import com.substring.chat.New_Chatapp_backend.payload.MessageRequest;
import com.substring.chat.New_Chatapp_backend.payload.RetentionSettingsRequest;
import com.substring.chat.New_Chatapp_backend.payload.DeleteMessagesRequest;
import com.substring.chat.New_Chatapp_backend.payload.ReactionRequest;
import com.substring.chat.New_Chatapp_backend.payload.DirectChatRequest;
import com.substring.chat.New_Chatapp_backend.payload.ForwardMessageRequest;
import com.substring.chat.New_Chatapp_backend.security.RoomSecurityService;
import com.substring.chat.New_Chatapp_backend.security.RoomTokenClaims;
import com.substring.chat.New_Chatapp_backend.security.WebSocketSessionRegistry;
import com.substring.chat.New_Chatapp_backend.services.InactiveRoomCleanupService;
import com.substring.chat.New_Chatapp_backend.services.MessageService;
import com.substring.chat.New_Chatapp_backend.services.RoomService;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import com.substring.chat.New_Chatapp_backend.entities.User;
import com.substring.chat.New_Chatapp_backend.repo.UserRepo;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@CrossOrigin(AppConstants.FRONT_END_BASE_URL)
@RequestMapping("/api/v1/rooms")
public class RoomController {

    private static final Logger log = LoggerFactory.getLogger(RoomController.class);

    private final RoomService roomService;
    private final MessageService messageService;
    private final InactiveRoomCleanupService inactiveRoomCleanupService;
    private final RoomSecurityService roomSecurityService;

    @Autowired(required = false)
    private SimpMessagingTemplate messagingTemplate;

    @Autowired(required = false)
    private UserRepo userRepo;

    @Autowired(required = false)
    private com.substring.chat.New_Chatapp_backend.repo.RoomRepo roomRepo;

    @Autowired(required = false)
    private com.substring.chat.New_Chatapp_backend.repo.MessageRepo messageRepo;

    private final WebSocketSessionRegistry webSocketSessionRegistry;

    @Autowired
    public RoomController(RoomService roomService,
                          MessageService messageService,
                          InactiveRoomCleanupService inactiveRoomCleanupService,
                          RoomSecurityService roomSecurityService,
                          @Autowired(required = false) WebSocketSessionRegistry webSocketSessionRegistry) {
        this.roomService = roomService;
        this.messageService = messageService;
        this.inactiveRoomCleanupService = inactiveRoomCleanupService;
        this.roomSecurityService = roomSecurityService;
        this.webSocketSessionRegistry = webSocketSessionRegistry;
    }

    public RoomController(RoomService roomService,
                          MessageService messageService,
                          InactiveRoomCleanupService inactiveRoomCleanupService,
                          RoomSecurityService roomSecurityService) {
        this(roomService, messageService, inactiveRoomCleanupService, roomSecurityService, null);
    }

    // ✅ CREATE ROOM (supports JSON body {roomId, roomName, creator}, raw text, params, headers)
    // ✅ GENERATE SECURE INVITATION CODE FOR ROOM CREATION
    @PostMapping(value = {"/generate-invite-code", "/generate-code"})
    public ResponseEntity<?> generateInviteCodeForCreation(
            @RequestBody(required = false) String rawBody,
            @RequestParam(value = "roomId", required = false) String roomIdParam,
            @RequestParam(value = "creator", required = false) String creatorParam,
            @RequestHeader(value = "X-User-Name", required = false) String headerUser) {

        String roomId = null;
        String creator = null;

        if (rawBody != null && !rawBody.trim().isEmpty()) {
            String trimmed = rawBody.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    Document doc = Document.parse(trimmed);
                    if (doc.containsKey("roomId")) {
                        roomId = doc.getString("roomId");
                    }
                    if (doc.containsKey("creator")) {
                        creator = doc.getString("creator");
                    } else if (doc.containsKey("userName")) {
                        creator = doc.getString("userName");
                    } else if (doc.containsKey("admin")) {
                        creator = doc.getString("admin");
                    }
                } catch (Exception ignored) {
                }
            } else {
                roomId = trimmed;
            }
        }

        if (roomIdParam != null && !roomIdParam.trim().isEmpty()) {
            roomId = roomIdParam.trim();
        }
        if (creatorParam != null && !creatorParam.trim().isEmpty()) {
            creator = creatorParam.trim();
        } else if (headerUser != null && !headerUser.trim().isEmpty()) {
            creator = headerUser.trim();
        }
        creator = resolveAuthenticatedUser(creator);

        if (roomId == null || roomId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Room ID cannot be empty!");
        }

        String cleanedRoomId = roomId.trim();
        if (roomService.getRoom(cleanedRoomId) != null) {
            return ResponseEntity.badRequest().body("Room already exists! Please choose another Room ID.");
        }

        String cleanedCreator = creator != null ? creator.trim() : "";
        String inviteCode = roomSecurityService.generateInviteCode();
        String codeTicket = roomSecurityService.generateCodeTicket(cleanedRoomId, cleanedCreator, inviteCode);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("roomId", cleanedRoomId);
        resp.put("inviteCode", inviteCode);
        resp.put("codeTicket", codeTicket);

        return ResponseEntity.ok(resp);
    }

    // ✅ CREATE ROOM (supports JSON body {roomId, creator, codeTicket, avatar}, raw text, params, headers)
    @PostMapping
    public ResponseEntity<?> createRoom(
            @RequestBody(required = false) String rawBody,
            @RequestParam(value = "roomId", required = false) String roomIdParam,
            @RequestParam(value = "roomName", required = false) String roomNameParam,
            @RequestParam(value = "creator", required = false) String creatorParam,
            @RequestParam(value = "avatar", required = false) String avatarParam,
            @RequestParam(value = "codeTicket", required = false) String ticketParam,
            @RequestHeader(value = "X-User-Name", required = false) String headerUser,
            @RequestHeader(value = "X-User-Avatar", required = false) String headerAvatar) {

        String roomId = null;
        String roomName = null;
        String creator = null;
        String avatar = null;
        String codeTicket = null;

        if (rawBody != null && !rawBody.trim().isEmpty()) {
            String trimmed = rawBody.trim();
            if (trimmed.startsWith("{") && trimmed.endsWith("}")) {
                try {
                    Document doc = Document.parse(trimmed);
                    if (doc.containsKey("roomId")) {
                        roomId = doc.getString("roomId");
                    }
                    if (doc.containsKey("roomName")) {
                        roomName = doc.getString("roomName");
                    }
                    if (doc.containsKey("creator")) {
                        creator = doc.getString("creator");
                    } else if (doc.containsKey("admin")) {
                        creator = doc.getString("admin");
                    }
                    if (doc.containsKey("avatar")) {
                        avatar = doc.getString("avatar");
                    }
                    if (doc.containsKey("codeTicket")) {
                        codeTicket = doc.getString("codeTicket");
                    }
                } catch (Exception e) {
                    roomId = trimmed;
                }
            } else {
                if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
                    (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
                    if (trimmed.length() >= 2) {
                        trimmed = trimmed.substring(1, trimmed.length() - 1);
                    }
                }
                roomId = trimmed;
            }
        }

        if (roomIdParam != null && !roomIdParam.trim().isEmpty()) {
            roomId = roomIdParam.trim();
        }
        if (roomNameParam != null && !roomNameParam.trim().isEmpty()) {
            roomName = roomNameParam.trim();
        }
        if (creatorParam != null && !creatorParam.trim().isEmpty()) {
            creator = creatorParam.trim();
        } else if (headerUser != null && !headerUser.trim().isEmpty()) {
            creator = headerUser.trim();
        }
        creator = resolveAuthenticatedUser(creator);
        String creatorId = resolveAuthenticatedUserId();
        if (avatarParam != null && !avatarParam.trim().isEmpty()) {
            avatar = avatarParam.trim();
        } else if (headerAvatar != null && !headerAvatar.trim().isEmpty()) {
            avatar = headerAvatar.trim();
        }
        if (avatar == null || avatar.trim().isEmpty()) {
            avatar = resolveAuthenticatedAvatar(avatar);
        }
        if (ticketParam != null && !ticketParam.trim().isEmpty()) {
            codeTicket = ticketParam.trim();
        }

        if (roomId == null || roomId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Room ID cannot be empty!");
        }

        String verifiedInviteCode = null;
        if (codeTicket != null && !codeTicket.trim().isEmpty()) {
            verifiedInviteCode = roomSecurityService.validateCodeTicket(codeTicket, roomId, creator);
            if (verifiedInviteCode == null) {
                return ResponseEntity.badRequest().body("Invalid or expired invitation code ticket!");
            }
        }

        try {
            Room room;
            if (creatorId != null && !creatorId.trim().isEmpty()) {
                room = roomService.createRoom(roomId, creator, roomName, verifiedInviteCode, avatar, creatorId);
            } else if (avatar != null && !avatar.trim().isEmpty()) {
                if (verifiedInviteCode != null && !verifiedInviteCode.trim().isEmpty()) {
                    room = roomService.createRoom(roomId, creator, roomName, verifiedInviteCode, avatar);
                } else if (roomName != null && !roomName.trim().isEmpty()) {
                    room = roomService.createRoom(roomId, creator, roomName, null, avatar);
                } else {
                    room = roomService.createRoom(roomId, creator, null, null, avatar);
                }
            } else {
                if (verifiedInviteCode != null && !verifiedInviteCode.trim().isEmpty()) {
                    room = roomService.createRoom(roomId, creator, roomName, verifiedInviteCode);
                } else if (roomName != null && !roomName.trim().isEmpty()) {
                    room = roomService.createRoom(roomId, creator, roomName);
                } else {
                    room = roomService.createRoom(roomId, creator);
                }
            }
            String cleanedCreator = creator != null ? creator.trim() : "";
            String token = roomSecurityService.generateToken(room.getRoomId(), cleanedCreator, true);
            String plaintextInviteCode = roomSecurityService.decryptInviteCode(room.getEncryptedInviteCode());
            String plaintextInviteToken = null;
            try {
                plaintextInviteToken = roomService.getInviteToken(room.getRoomId(), cleanedCreator);
            } catch (Exception ignored) {}

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("id", room.getId());
            response.put("roomId", room.getRoomId());
            response.put("roomName", room.getRoomId());
            response.put("admin", room.getAdmin());
            response.put("creator", room.getCreator());
            response.put("avatar", avatar);
            response.put("customDeletionTimeEnabled", room.isCustomDeletionTimeEnabled());
            response.put("customDeletionAfterSeconds", room.getCustomDeletionAfterSeconds());
            response.put("createdAt", room.getCreatedAt());
            response.put("lastActivityAt", room.getLastActivityAt());
            response.put("members", room.getMembers());
            response.put("token", token);
            response.put("inviteCode", plaintextInviteCode);
            response.put("inviteToken", plaintextInviteToken);
            response.put("invitationVersion", room.getInvitationVersion());

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ✅ JOIN ROOM WITH INVITATION CODE (POST /api/v1/rooms/{roomId}/join or POST /api/v1/rooms/join)
    @PostMapping(value = {"/{roomId}/join", "/join"})
    public ResponseEntity<?> joinRoomWithCode(
            @PathVariable(required = false) String roomId,
            @RequestBody(required = false) JoinRoomRequest request,
            @RequestParam(value = "roomId", required = false) String paramRoomId,
            @RequestParam(value = "userName", required = false) String paramUser,
            @RequestParam(value = "inviteCode", required = false) String paramCode,
            @RequestParam(value = "avatar", required = false) String paramAvatar,
            @RequestHeader(value = "X-User-Name", required = false) String headerUser,
            @RequestHeader(value = "X-User-Avatar", required = false) String headerAvatar) {

        String targetRoomId = (roomId != null && !roomId.isEmpty()) ? roomId : (request != null ? request.getRoomId() : paramRoomId);
        String username = (request != null && request.getUserName() != null) ? request.getUserName() : (headerUser != null ? headerUser : paramUser);
        username = resolveAuthenticatedUser(username);
        String userId = resolveAuthenticatedUserId();
        String code = (request != null && request.getInviteCode() != null) ? request.getInviteCode() : paramCode;
        String avatar = (request != null && request.getAvatar() != null) ? request.getAvatar() : (headerAvatar != null ? headerAvatar : paramAvatar);
        if (avatar == null || avatar.trim().isEmpty()) {
            avatar = resolveAuthenticatedAvatar(avatar);
        }

        if (targetRoomId == null || targetRoomId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Room ID cannot be empty!");
        }
        if (username == null || username.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Username cannot be empty!");
        }
        if (code == null || code.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Invitation code is required to join this room!");
        }

        log.info("[JOIN ROOM REQUEST] Target room: '{}', username: '{}', resolved userId: '{}'", targetRoomId, username, userId);

        try {
            Room preRoom = roomService.getRoom(targetRoomId);
            boolean isNewJoin = (preRoom != null && !preRoom.isMember(username) && (preRoom.getAdmin() == null || !preRoom.getAdmin().equalsIgnoreCase(username)));

            AuthResponse auth;
            if (userId != null && !userId.trim().isEmpty()) {
                auth = roomService.joinRoom(targetRoomId, username, code, avatar, userId);
            } else if (avatar != null && !avatar.trim().isEmpty()) {
                auth = roomService.joinRoom(targetRoomId, username, code, avatar);
            } else {
                auth = roomService.joinRoom(targetRoomId, username, code);
            }

            // Broadcast member list update to room
            broadcastMemberUpdate(targetRoomId, "MEMBER_JOINED", username);

            if (isNewJoin) {
                try {
                    Message sysMsg = messageService.createSystemMessage(
                            targetRoomId,
                            "MEMBER_JOINED",
                            username + " joined the chat via code",
                            null,
                            username,
                            "CODE"
                    );
                    if (messagingTemplate != null) {
                        messagingTemplate.convertAndSend("/topic/room/" + targetRoomId, (Object) sysMsg);
                    }
                } catch (Exception e) {
                    log.warn("Could not dispatch join via code system message: {}", e.getMessage());
                }
            }

            return ResponseEntity.ok(auth);
        } catch (RemovedMemberException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (InvalidInviteCodeException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // ✅ GET ROOM METADATA (Sanitizes sensitive invite code hashes from public responses)
    @GetMapping("/{roomId}")
    public ResponseEntity<?> joinroom(
            @PathVariable String roomId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader) {
        Room room = roomService.getRoom(roomId);

        if (room == null) {
            return ResponseEntity.badRequest().body("Room Doesn't exist!");
        }

        // Verify room has not expired due to 30 consecutive days of inactivity
        if (room.getLastActivityAt() != null) {
            long inactiveMillis = System.currentTimeMillis() - room.getLastActivityAt().getTime();
            if (inactiveMillis > 30L * 24L * 60L * 60L * 1000L) {
                return ResponseEntity.badRequest().body("Room has expired due to inactivity!");
            }
        }

        // Verify that removed members are rejected
        String token = tokenHeader != null ? tokenHeader : authHeader;
        if (token != null && !token.trim().isEmpty()) {
            RoomTokenClaims claims = roomSecurityService.validateToken(token, roomId);
            if (claims != null && room.isRemoved(claims.getUsername())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You have been removed from this room.");
            }
        }

        // Clone/sanitize room so encryptedInviteCode and inviteCodeHash are never exposed in public room view
        Room sanitized = new Room();
        sanitized.setId(room.getId());
        sanitized.setRoomId(room.getRoomId());
        if ("DIRECT".equalsIgnoreCase(room.getRoomType())) {
            sanitized.setRoomName(room.getRoomName());
        } else {
            sanitized.setRoomName(room.getRoomId());
        }
        sanitized.setRoomType(room.getRoomType());
        sanitized.setParticipantUserIds(room.getParticipantUserIds());
        sanitized.setAdmin(room.getAdmin());
        sanitized.setAdminId(room.getAdminId());
        sanitized.setCustomDeletionTimeEnabled(room.isCustomDeletionTimeEnabled());
        sanitized.setCustomDeletionAfterSeconds(room.getCustomDeletionAfterSeconds());
        sanitized.setCreatedAt(room.getCreatedAt());
        sanitized.setLastActivityAt(room.getLastActivityAt());
        sanitized.setMembers(room.getMembers());

        return ResponseEntity.ok(sanitized);
    }

    // ✅ GET ROOM MEMBERS (Requires membership validation)
    @GetMapping("/{roomId}/members")
    public ResponseEntity<?> getRoomMembers(
            @PathVariable String roomId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader) {

        Room room = roomService.getRoom(roomId);
        if (room == null) {
            return ResponseEntity.badRequest().body("Room Doesn't exist!");
        }

        String token = tokenHeader != null ? tokenHeader : authHeader;
        if (token == null || token.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing authentication token");
        }

        RoomTokenClaims claims = roomSecurityService.validateToken(token, roomId);
        if (claims == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or expired authentication token");
        }

        if (room.isRemoved(claims.getUsername())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You have been removed from this room.");
        }

        if (!room.isMember(claims.getUsername())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You are not an active member of this room.");
        }

        List<RoomMember> members = roomService.getMembers(roomId);
        return ResponseEntity.ok(members);
    }

    // ✅ ADMIN REMOVE MEMBER (Revokes access, rotates invitation code, rotates invitation link, targeted notification and disconnects)
    @DeleteMapping("/{roomId}/members/{targetUser}")
    public ResponseEntity<?> removeMember(
            @PathVariable String roomId,
            @PathVariable String targetUser,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader) {

        String token = tokenHeader != null ? tokenHeader : authHeader;
        String adminUser = resolveAdminUser(token, roomId);
        if (adminUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing authentication token");
        }

        try {
            String newInviteCode = roomService.removeMember(roomId, adminUser, targetUser);

            // Create and persist system message for member removal
            try {
                Message sysMsg = messageService.createSystemMessage(
                        roomId,
                        "MEMBER_REMOVED",
                        adminUser + " (Admin) removed " + targetUser,
                        adminUser,
                        targetUser,
                        null
                );
                if (messagingTemplate != null) {
                    messagingTemplate.convertAndSend("/topic/room/" + roomId, (Object) sysMsg);
                }
            } catch (Exception e) {
                log.warn("Could not dispatch member removal system message: {}", e.getMessage());
            }

            // 1. Dispatch targeted notification to removed member and general member update to the room
            sendMemberRemovalNotifications(roomId, targetUser);

            // 2. Invalidate and close active WebSocket session for the removed member
            if (webSocketSessionRegistry != null) {
                webSocketSessionRegistry.closeSessionsForUser(roomId, targetUser);
            }

            String newInviteToken = null;
            int invitationVersion = 1;
            try {
                newInviteToken = roomService.getInviteToken(roomId, adminUser);
                Room r = roomService.getRoom(roomId);
                if (r != null) {
                    invitationVersion = r.getInvitationVersion();
                }
            } catch (Exception ignored) {}

            // 3. Dispatch rotation notification to room admin channel
            if (messagingTemplate != null && newInviteToken != null) {
                try {
                    Map<String, Object> adminPayload = new LinkedHashMap<>();
                    adminPayload.put("type", "INVITE_LINK_ROTATED");
                    adminPayload.put("roomId", roomId);
                    adminPayload.put("newInviteToken", newInviteToken);
                    adminPayload.put("invitationVersion", invitationVersion);
                    adminPayload.put("message", "Invitation link has been rotated after member removal.");
                    messagingTemplate.convertAndSend("/topic/room/" + roomId + "/admin", (Object) adminPayload);
                } catch (Exception e) {
                    log.warn("Could not dispatch invite link rotation to admin: {}", e.getMessage());
                }
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("success", true);
            result.put("message", "Member '" + targetUser + "' removed successfully.");
            result.put("newInviteCode", newInviteCode); // Returned ONLY to the Admin
            result.put("newInviteToken", newInviteToken); // Returned ONLY to the Admin
            result.put("invitationVersion", invitationVersion);
            result.put("members", roomService.getMembers(roomId));

            return ResponseEntity.ok(result);
        } catch (UnauthorizedAdminException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // ✅ VOLUNTARILY LEAVE ROOM (POST /api/v1/rooms/{roomId}/leave)
    @PostMapping("/{roomId}/leave")
    public ResponseEntity<?> leaveRoom(
            @PathVariable String roomId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader,
            @RequestHeader(value = "X-User-Name", required = false) String headerUser) {

        String token = tokenHeader != null ? tokenHeader : authHeader;
        if (token == null || token.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing authentication token");
        }

        RoomTokenClaims claims = roomSecurityService.validateToken(token, roomId);
        if (claims == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or expired authentication token");
        }

        String username = claims.getUsername();
        if (username == null || username.trim().isEmpty()) {
            username = headerUser;
        }

        try {
            boolean wasMember = roomService.leaveRoom(roomId, username);

            if (wasMember) {
                // Invalidate active WebSocket sessions for leaving user
                if (webSocketSessionRegistry != null) {
                    webSocketSessionRegistry.closeSessionsForUser(roomId, username);
                }

                // Create and persist system message
                try {
                    Message sysMsg = messageService.createSystemMessage(
                            roomId,
                            "MEMBER_LEFT",
                            username + " left the chat",
                            null,
                            username,
                            null
                    );
                    if (messagingTemplate != null) {
                        messagingTemplate.convertAndSend("/topic/room/" + roomId, (Object) sysMsg);
                    }
                } catch (Exception e) {
                    log.warn("Could not dispatch leave system message: {}", e.getMessage());
                }

                // Broadcast updated member list to room
                broadcastMemberUpdate(roomId, "MEMBER_LEFT", username);
            }

            return ResponseEntity.ok(Map.of("success", true, "message", "Left room successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", e.getMessage()));
        }
    }

    // ✅ ADMIN GET INVITATION CODE (Decrypted plaintext code, strictly Admin-only)
    @GetMapping("/{roomId}/invite-code")
    public ResponseEntity<?> getInviteCode(
            @PathVariable String roomId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader) {

        String token = tokenHeader != null ? tokenHeader : authHeader;
        String adminUser = resolveAdminUser(token, roomId);
        if (adminUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing authentication token");
        }

        try {
            String code = roomService.getInviteCode(roomId, adminUser);
            Map<String, String> response = new LinkedHashMap<>();
            response.put("roomId", roomId);
            response.put("inviteCode", code);
            return ResponseEntity.ok(response);
        } catch (UnauthorizedAdminException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // ✅ ADMIN REGENERATE INVITATION CODE (Manually rotate code, strictly Admin-only)
    @PostMapping("/{roomId}/regenerate-code")
    public ResponseEntity<?> regenerateInviteCode(
            @PathVariable String roomId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader) {

        String token = tokenHeader != null ? tokenHeader : authHeader;
        String adminUser = resolveAdminUser(token, roomId);
        if (adminUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing authentication token");
        }

        try {
            String newCode = roomService.regenerateInviteCode(roomId, adminUser);
            Map<String, String> response = new LinkedHashMap<>();
            response.put("roomId", roomId);
            response.put("inviteCode", newCode);
            return ResponseEntity.ok(response);
        } catch (UnauthorizedAdminException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // ✅ VALIDATE INVITATION TOKEN (Public GET /api/v1/rooms/invitations/{token}/validate)
    @GetMapping("/invitations/{token}/validate")
    public ResponseEntity<?> validateInvitationToken(@PathVariable String token) {
        try {
            Room room = roomService.validateInviteToken(token);
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("valid", true);
            response.put("roomId", room.getRoomId());
            response.put("roomName", room.getRoomName() != null ? room.getRoomName() : room.getRoomId());
            response.put("invitationVersion", room.getInvitationVersion());
            response.put("admin", room.getAdmin());
            response.put("memberCount", room.getMembers() != null ? room.getMembers().size() : 0);
            response.put("adminApprovalRequired", room.isAdminApprovalRequired());
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            Map<String, Object> errorResp = new LinkedHashMap<>();
            errorResp.put("valid", false);
            errorResp.put("message", "Invalid or expired invitation link. Please ask the room admin for a new invitation.");
            return ResponseEntity.status(HttpStatus.GONE).body(errorResp);
        } catch (Exception e) {
            Map<String, Object> errorResp = new LinkedHashMap<>();
            errorResp.put("valid", false);
            errorResp.put("message", "Error validating invitation link.");
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResp);
        }
    }

    // ✅ SUBMIT JOIN REQUEST (POST /api/v1/rooms/{roomId}/join-requests or POST /api/v1/rooms/join-requests)
    @PostMapping(value = {"/{roomId}/join-requests", "/join-requests"})
    public ResponseEntity<?> submitJoinRequest(
            @PathVariable(required = false) String roomId,
            @RequestBody(required = false) JoinInviteRequest request,
            @RequestParam(value = "token", required = false) String paramToken,
            @RequestParam(value = "userName", required = false) String paramUser,
            @RequestParam(value = "avatar", required = false) String paramAvatar) {

        String token = request != null && request.getToken() != null ? request.getToken() : paramToken;
        String userName = request != null && request.getUserName() != null ? request.getUserName() : paramUser;
        userName = resolveAuthenticatedUser(userName);
        String userId = resolveAuthenticatedUserId();
        String avatar = request != null && request.getAvatar() != null ? request.getAvatar() : paramAvatar;
        if (avatar == null || avatar.trim().isEmpty()) {
            avatar = resolveAuthenticatedAvatar(avatar);
        }

        if (token == null || token.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invitation token is required"));
        }
        if (userName == null || userName.trim().isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Display name cannot be empty"));
        }

        try {
            JoinRequest joinRequest = (userId != null && !userId.trim().isEmpty())
                    ? roomService.createJoinRequest(token, userName, avatar, userId)
                    : roomService.createJoinRequest(token, userName, avatar);
            String targetRoomId = joinRequest.getRoomId();

            if ("APPROVED".equals(joinRequest.getStatus())) {
                // Member was approved directly or reconnecting; broadcast member join
                broadcastMemberUpdate(targetRoomId, "JOIN", joinRequest.getUserName());

                if (joinRequest.isNewJoin()) {
                    try {
                        Message sysMsg = messageService.createSystemMessage(
                                targetRoomId,
                                "MEMBER_JOINED",
                                joinRequest.getUserName() + " joined the chat via link",
                                null,
                                joinRequest.getUserName(),
                                "LINK"
                        );
                        if (messagingTemplate != null) {
                            messagingTemplate.convertAndSend("/topic/room/" + targetRoomId, (Object) sysMsg);
                        }
                    } catch (Exception e) {
                        log.warn("Could not dispatch join via link system message: {}", e.getMessage());
                    }
                }

                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("requestId", joinRequest.getId());
                resp.put("status", "APPROVED");
                resp.put("approvedToken", joinRequest.getApprovedToken());
                resp.put("roomId", targetRoomId);
                resp.put("userName", joinRequest.getUserName());
                resp.put("avatar", joinRequest.getAvatar());
                return ResponseEntity.ok(resp);
            }

            // Real-time notification to the room admin channel
            if (messagingTemplate != null) {
                try {
                    Map<String, Object> adminNotice = new LinkedHashMap<>();
                    adminNotice.put("type", "NEW_JOIN_REQUEST");
                    adminNotice.put("requestId", joinRequest.getId());
                    adminNotice.put("roomId", targetRoomId);
                    adminNotice.put("userName", joinRequest.getUserName());
                    adminNotice.put("avatar", joinRequest.getAvatar());
                    adminNotice.put("createdAt", joinRequest.getCreatedAt());
                    messagingTemplate.convertAndSend("/topic/room/" + targetRoomId + "/admin", (Object) adminNotice);
                } catch (Exception e) {
                    log.warn("Could not notify admin of new join request: {}", e.getMessage());
                }
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("requestId", joinRequest.getId());
            resp.put("status", joinRequest.getStatus());
            resp.put("roomId", targetRoomId);
            resp.put("userName", joinRequest.getUserName());
            resp.put("avatar", joinRequest.getAvatar());
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(resp);
        } catch (RemovedMemberException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("message", e.getMessage()));
        }
    }

    // ✅ CHECK JOIN REQUEST STATUS (Public / guest polling GET /api/v1/rooms/{roomId}/join-requests/{requestId}/status)
    @GetMapping(value = {"/{roomId}/join-requests/{requestId}/status", "/join-requests/{requestId}/status"})
    public ResponseEntity<?> getJoinRequestStatus(
            @PathVariable(required = false) String roomId,
            @PathVariable String requestId) {
        JoinRequest req = roomService.getJoinRequestStatus(requestId);
        if (req == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", "Join request not found"));
        }
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("requestId", req.getId());
        resp.put("status", req.getStatus());
        resp.put("approvedToken", req.getApprovedToken());
        resp.put("roomId", req.getRoomId());
        resp.put("userName", req.getUserName());
        resp.put("avatar", req.getAvatar());
        return ResponseEntity.ok(resp);
    }

    // ✅ GET PENDING JOIN REQUESTS (Admin only GET /api/v1/rooms/{roomId}/join-requests)
    @GetMapping("/{roomId}/join-requests")
    public ResponseEntity<?> getPendingJoinRequests(
            @PathVariable String roomId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader,
            @RequestHeader(value = "X-User-Name", required = false) String headerUser) {

        String token = tokenHeader != null ? tokenHeader : authHeader;
        String adminUser = resolveAdminUser(token, headerUser, roomId);
        if (adminUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing authentication token");
        }

        try {
            List<JoinRequest> list = roomService.getPendingJoinRequests(roomId, adminUser);
            return ResponseEntity.ok(list);
        } catch (UnauthorizedAdminException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // ✅ APPROVE JOIN REQUEST (Admin only POST /api/v1/rooms/{roomId}/join-requests/{requestId}/approve)
    @PostMapping("/{roomId}/join-requests/{requestId}/approve")
    public ResponseEntity<?> approveJoinRequest(
            @PathVariable String roomId,
            @PathVariable String requestId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader,
            @RequestHeader(value = "X-User-Name", required = false) String headerUser) {

        String token = tokenHeader != null ? tokenHeader : authHeader;
        String adminUser = resolveAdminUser(token, headerUser, roomId);
        if (adminUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing authentication token");
        }

        try {
            JoinRequest req = roomService.approveJoinRequest(roomId, requestId, adminUser);

            // Notify requester via WebSocket on /topic/room/{roomId}/join-request/{requestId}
            if (messagingTemplate != null) {
                try {
                    Map<String, Object> guestNotice = new LinkedHashMap<>();
                    guestNotice.put("type", "JOIN_REQUEST_APPROVED");
                    guestNotice.put("status", "APPROVED");
                    guestNotice.put("approvedToken", req.getApprovedToken());
                    guestNotice.put("roomId", roomId);
                    guestNotice.put("userName", req.getUserName());
                    guestNotice.put("avatar", req.getAvatar());
                    messagingTemplate.convertAndSend("/topic/room/" + roomId + "/join-request/" + requestId, (Object) guestNotice);
                } catch (Exception e) {
                    log.warn("Could not dispatch join request approval to guest: {}", e.getMessage());
                }
            }

            // Broadcast member list update to room
            broadcastMemberUpdate(roomId, "MEMBER_JOINED", req.getUserName());

            // Create and persist system message for admin-approved join
            try {
                Message sysMsg = messageService.createSystemMessage(
                        roomId,
                        "MEMBER_JOINED",
                        req.getUserName() + " joined the chat",
                        adminUser,
                        req.getUserName(),
                        "ADMIN_APPROVAL"
                );
                if (messagingTemplate != null) {
                    messagingTemplate.convertAndSend("/topic/room/" + roomId, (Object) sysMsg);
                }
            } catch (Exception e) {
                log.warn("Could not dispatch approved join system message: {}", e.getMessage());
            }

            return ResponseEntity.ok(req);
        } catch (UnauthorizedAdminException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (RemovedMemberException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // ✅ REJECT JOIN REQUEST (Admin only POST /api/v1/rooms/{roomId}/join-requests/{requestId}/reject)
    @PostMapping("/{roomId}/join-requests/{requestId}/reject")
    public ResponseEntity<?> rejectJoinRequest(
            @PathVariable String roomId,
            @PathVariable String requestId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader,
            @RequestHeader(value = "X-User-Name", required = false) String headerUser) {

        String token = tokenHeader != null ? tokenHeader : authHeader;
        String adminUser = resolveAdminUser(token, headerUser, roomId);
        if (adminUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing authentication token");
        }

        try {
            JoinRequest req = roomService.rejectJoinRequest(roomId, requestId, adminUser);

            // Notify requester via WebSocket on /topic/room/{roomId}/join-request/{requestId}
            if (messagingTemplate != null) {
                try {
                    Map<String, Object> guestNotice = new LinkedHashMap<>();
                    guestNotice.put("type", "JOIN_REQUEST_REJECTED");
                    guestNotice.put("status", "REJECTED");
                    guestNotice.put("message", "Your request to join the room was declined by the admin.");
                    messagingTemplate.convertAndSend("/topic/room/" + roomId + "/join-request/" + requestId, (Object) guestNotice);
                } catch (Exception e) {
                    log.warn("Could not dispatch join request rejection to guest: {}", e.getMessage());
                }
            }

            return ResponseEntity.ok(req);
        } catch (UnauthorizedAdminException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // ✅ GET INVITATION LINK / TOKEN (Admin only GET /api/v1/rooms/{roomId}/invite-link)
    @GetMapping("/{roomId}/invite-link")
    public ResponseEntity<?> getInviteLink(
            @PathVariable String roomId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader,
            @RequestHeader(value = "X-User-Name", required = false) String headerUser) {

        String token = tokenHeader != null ? tokenHeader : authHeader;
        String adminUser = resolveAdminUser(token, headerUser, roomId);
        if (adminUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing authentication token");
        }

        try {
            String inviteToken = roomService.getInviteToken(roomId, adminUser);
            Room room = roomService.getRoom(roomId);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("roomId", roomId);
            resp.put("inviteToken", inviteToken);
            resp.put("invitationVersion", room != null ? room.getInvitationVersion() : 1);
            resp.put("adminApprovalRequired", room != null && room.isAdminApprovalRequired());
            return ResponseEntity.ok(resp);
        } catch (UnauthorizedAdminException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // ✅ ROTATE INVITATION LINK (Admin only POST /api/v1/rooms/{roomId}/rotate-invite-link)
    @PostMapping("/{roomId}/rotate-invite-link")
    public ResponseEntity<?> rotateInviteLink(
            @PathVariable String roomId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader,
            @RequestHeader(value = "X-User-Name", required = false) String headerUser) {

        String token = tokenHeader != null ? tokenHeader : authHeader;
        String adminUser = resolveAdminUser(token, headerUser, roomId);
        if (adminUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing authentication token");
        }

        try {
            String newInviteToken = roomService.rotateInviteToken(roomId, adminUser);
            Room room = roomService.getRoom(roomId);
            int version = room != null ? room.getInvitationVersion() : 1;

            if (messagingTemplate != null) {
                try {
                    Map<String, Object> adminPayload = new LinkedHashMap<>();
                    adminPayload.put("type", "INVITE_LINK_ROTATED");
                    adminPayload.put("roomId", roomId);
                    adminPayload.put("newInviteToken", newInviteToken);
                    adminPayload.put("invitationVersion", version);
                    messagingTemplate.convertAndSend("/topic/room/" + roomId + "/admin", (Object) adminPayload);
                } catch (Exception e) {
                    log.warn("Could not dispatch invite link rotation to admin: {}", e.getMessage());
                }
            }

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("roomId", roomId);
            resp.put("inviteToken", newInviteToken);
            resp.put("invitationVersion", version);
            resp.put("adminApprovalRequired", room != null && room.isAdminApprovalRequired());
            return ResponseEntity.ok(resp);
        } catch (UnauthorizedAdminException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // ✅ UPDATE INVITATION APPROVAL MODE (Admin only PUT/POST /api/v1/rooms/{roomId}/invitation-mode)
    @RequestMapping(value = "/{roomId}/invitation-mode", method = {RequestMethod.PUT, RequestMethod.POST})
    public ResponseEntity<?> updateInvitationMode(
            @PathVariable String roomId,
            @RequestBody(required = false) Map<String, Object> body,
            @RequestParam(value = "adminApprovalRequired", required = false) Boolean paramApproval,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader,
            @RequestHeader(value = "X-User-Name", required = false) String headerUser) {

        String token = tokenHeader != null ? tokenHeader : authHeader;
        String adminUser = resolveAdminUser(token, headerUser, roomId);
        if (adminUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing authentication token");
        }

        boolean requireApproval = true;
        if (body != null) {
            if (body.containsKey("adminApprovalRequired")) {
                Object val = body.get("adminApprovalRequired");
                if (val instanceof Boolean) {
                    requireApproval = (Boolean) val;
                } else if (val != null) {
                    requireApproval = Boolean.parseBoolean(val.toString());
                }
            } else if (body.containsKey("approvalMode")) {
                requireApproval = !"DIRECT".equalsIgnoreCase(String.valueOf(body.get("approvalMode")));
            }
        } else if (paramApproval != null) {
            requireApproval = paramApproval;
        }

        try {
            Room updated = roomService.updateInvitationMode(roomId, adminUser, requireApproval);
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("success", true);
            resp.put("roomId", roomId);
            resp.put("adminApprovalRequired", updated.isAdminApprovalRequired());
            resp.put("message", requireApproval ? "Admin approval required for invitation links." : "Direct join via link enabled.");
            return ResponseEntity.ok(resp);
        } catch (UnauthorizedAdminException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // ✅ GET MESSAGES (returns active messages, filtering out expired messages and blocking removed users)
    @GetMapping("/{roomId}/messages")
    public ResponseEntity<?> getMessages(
            @PathVariable String roomId,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader) {

        Room room = roomService.getRoom(roomId);
        if (room == null) {
            return ResponseEntity.badRequest().build();
        }

        // Verify room has not expired due to 30 consecutive days of inactivity
        if (room.getLastActivityAt() != null) {
            long inactiveMillis = System.currentTimeMillis() - room.getLastActivityAt().getTime();
            if (inactiveMillis > 30L * 24L * 60L * 60L * 1000L) {
                return ResponseEntity.badRequest().body("Room has expired due to inactivity!");
            }
        }

        String token = tokenHeader != null ? tokenHeader : authHeader;
        if (token == null || token.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing authentication token");
        }

        RoomTokenClaims claims = roomSecurityService.validateToken(token, roomId);
        if (claims == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or expired authentication token");
        }

        if (room.isRemoved(claims.getUsername())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You have been removed from this room.");
        }

        if (!room.isMember(claims.getUsername())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You are not an active member of this room.");
        }

        List<Message> messages = messageService.getValidMessagesForRoom(roomId);
        String userId = resolveAuthenticatedUserId();
        String username = resolveAuthenticatedUser(claims.getUsername());
        if (userId != null || username != null) {
            final String fUserId = userId;
            final String fUsername = username;
            messages = messages.stream()
                    .filter(msg -> !msg.isDeletedFor(fUserId) && !msg.isDeletedFor(fUsername))
                    .map(msg -> {
                        if (msg.isDeletedForEveryone()) {
                            msg.setContent("This message was deleted");
                        }
                        return msg;
                    })
                    .collect(Collectors.toList());
        }
        return ResponseEntity.ok(messages);
    }

    // ✅ POST MESSAGE via REST (applies room retention, verifies token and verified sender)
    @PostMapping("/{roomId}/messages")
    public ResponseEntity<?> createMessageRest(
            @PathVariable String roomId,
            @RequestBody MessageRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader) {

        Room room = roomService.getRoom(roomId);
        if (room == null) {
            return ResponseEntity.badRequest().body("Room Doesn't exist!");
        }

        String token = tokenHeader != null ? tokenHeader : authHeader;
        if (token == null || token.trim().isEmpty()) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Missing authentication token");
        }

        RoomTokenClaims claims = roomSecurityService.validateToken(token, roomId);
        if (claims == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Invalid or expired authentication token");
        }

        if (room.isRemoved(claims.getUsername())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You have been removed from this room.");
        }

        if (!room.isMember(claims.getUsername())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You are not an active member of this room.");
        }

        // Verified sender identity enforced from cryptographic token
        String sender = claims.getUsername();
        String clientTempId = request != null ? request.getClientTempId() : null;
        String avatar = request != null ? request.getAvatar() : null;
        String replyToMessageId = request != null ? request.getReplyToMessageId() : null;
        String replyToSender = request != null ? request.getReplyToSender() : null;
        String replyToText = request != null ? request.getReplyToText() : null;
        boolean encrypted = request != null && request.isEncrypted();
        String iv = request != null ? request.getIv() : null;
        try {
            Message message = messageService.createMessage(
                    roomId, sender,
                    request != null ? request.getContent() : "",
                    clientTempId, avatar,
                    replyToMessageId, replyToSender, replyToText,
                    encrypted, iv
            );
            if (messagingTemplate != null) {
                try {
                    messagingTemplate.convertAndSend("/topic/room/" + roomId, (Object) message);
                } catch (Exception e) {
                    log.warn("Could not broadcast REST message to WebSocket: {}", e.getMessage());
                }
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(message);
        } catch (RemovedMemberException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        }
    }

    // ✅ UPDATE RETENTION SETTINGS (Admin-only, HTTP 403 on unauthorized, HTTP 400 on invalid duration)
    @RequestMapping(value = "/{roomId}/retention", method = {RequestMethod.PUT, RequestMethod.POST})
    public ResponseEntity<?> updateRetention(
            @PathVariable String roomId,
            @RequestBody(required = false) RetentionSettingsRequest request,
            @RequestParam(value = "userName", required = false) String paramUser,
            @RequestHeader(value = "X-User-Name", required = false) String headerUser,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader) {
        try {
            String token = tokenHeader != null ? tokenHeader : authHeader;
            String requesterUser = null;

            if (token != null) {
                RoomTokenClaims claims = roomSecurityService.validateToken(token, roomId);
                if (claims != null) {
                    requesterUser = claims.getUsername();
                }
            }

            if (requesterUser == null) {
                if (request != null && request.getUserName() != null && !request.getUserName().trim().isEmpty()) {
                    requesterUser = request.getUserName().trim();
                } else if (headerUser != null && !headerUser.trim().isEmpty()) {
                    requesterUser = headerUser.trim();
                } else if (paramUser != null && !paramUser.trim().isEmpty()) {
                    requesterUser = paramUser.trim();
                }
            }

            Boolean enabled = request != null ? request.getCustomDeletionTimeEnabled() : false;
            Long seconds = request != null ? request.getCustomDeletionAfterSeconds() : null;

            Room updatedRoom = roomService.updateRetentionSettings(roomId, requesterUser, enabled, seconds);
            return ResponseEntity.ok(updatedRoom);
        } catch (UnauthorizedAdminException e) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(e.getMessage());
        } catch (InvalidRetentionException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(e.getMessage());
        }
    }

    // ✅ CLEANUP INACTIVE ROOMS & EXPIRED MESSAGES (Manual / test trigger)
    @PostMapping("/cleanup-inactive")
    public ResponseEntity<?> triggerCleanup(
            @RequestParam(value = "days", required = false, defaultValue = "30") long days) {
        Date cutoff = new Date(System.currentTimeMillis() - (days * 24L * 60L * 60L * 1000L));
        long expiredMessages = inactiveRoomCleanupService.cleanExpiredMessages(new Date());
        InactiveRoomCleanupService.CleanupResult roomResult = inactiveRoomCleanupService.cleanInactiveRooms(cutoff);
        return ResponseEntity.ok(new InactiveRoomCleanupService.CleanupResult(
                roomResult.deletedRoomsCount(),
                roomResult.failedRoomsCount(),
                cutoff,
                expiredMessages
        ));
    }

    private String resolveAdminUser(String token, String roomId) {
        return resolveAdminUser(token, null, roomId);
    }

    private String resolveAdminUser(String token, String headerUser, String roomId) {
        if (token != null && !token.trim().isEmpty()) {
            RoomTokenClaims claims = roomSecurityService.validateToken(token, roomId);
            if (claims != null) {
                return claims.getUsername();
            }
        }
        if (headerUser != null && !headerUser.trim().isEmpty()) {
            return headerUser.trim();
        }
        String authUser = resolveAuthenticatedUser(null);
        String authUserId = resolveAuthenticatedUserId();
        String authEmail = resolveAuthenticatedEmail();
        if (roomId != null && (authUser != null || authUserId != null || authEmail != null)) {
            Room room = roomService.getRoom(roomId);
            if (room != null) {
                boolean isRoomAdmin = (room.getAdminId() != null && authUserId != null && room.getAdminId().equals(authUserId))
                        || (room.getAdmin() != null && authUser != null && room.getAdmin().equalsIgnoreCase(authUser))
                        || (room.getAdmin() != null && authEmail != null && room.getAdmin().equalsIgnoreCase(authEmail));
                if (isRoomAdmin) {
                    return room.getAdmin() != null ? room.getAdmin() : authUser;
                }
            }
        }
        return null;
    }

    private void broadcastMemberUpdate(String roomId, String type, String targetUser) {
        if (messagingTemplate != null) {
            try {
                Map<String, Object> payload = new LinkedHashMap<>();
                payload.put("type", type);
                payload.put("user", targetUser);
                payload.put("members", roomService.getMembers(roomId));
                messagingTemplate.convertAndSend("/topic/room/" + roomId + "/members", (Object) payload);
            } catch (Exception e) {
                log.warn("Could not broadcast member update: {}", e.getMessage());
            }
        }
    }

    private void sendMemberRemovalNotifications(String roomId, String targetUser) {
        if (messagingTemplate != null) {
            try {
                // 1. Send targeted notification ONLY to the affected member
                Map<String, Object> privatePayload = new LinkedHashMap<>();
                privatePayload.put("type", "REMOVED_FROM_ROOM");
                privatePayload.put("roomId", roomId);
                privatePayload.put("user", targetUser);
                privatePayload.put("message", "You have been removed from this room by the Admin.");
                messagingTemplate.convertAndSend("/topic/room/" + roomId + "/user/" + targetUser.trim().toLowerCase(), (Object) privatePayload);

                // 2. Broadcast updated member list to room without removal toast alert
                Map<String, Object> membersPayload = new LinkedHashMap<>();
                membersPayload.put("type", "MEMBERS_UPDATED");
                membersPayload.put("members", roomService.getMembers(roomId));
                messagingTemplate.convertAndSend("/topic/room/" + roomId + "/members", (Object) membersPayload);
            } catch (Exception e) {
                log.warn("Could not dispatch member removal notifications: {}", e.getMessage());
            }
        }
    }

    // ================= GROUP CHAT HISTORY ENDPOINTS =================

    /**
     * A. Rooms Created:
     * Display the rooms created by the currently authenticated user.
     */
    @GetMapping("/history/created")
    public ResponseEntity<?> getRoomsCreatedHistory() {
        String userId = resolveAuthenticatedUserId();
        String username = resolveAuthenticatedUser(null);
        String email = resolveAuthenticatedEmail();

        log.info("[ROOM HISTORY: CREATED API] Authenticated request for userId='{}', username='{}', email='{}'", userId, username, email);

        if ((userId == null || userId.isEmpty()) && (username == null || username.isEmpty()) && (email == null || email.isEmpty())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Authentication required to view room history");
        }

        List<Room> rooms = roomService.getRoomsCreatedByUser(userId, username, email);
        List<Map<String, Object>> response = new ArrayList<>();

        for (Room r : rooms) {
            Map<String, Object> roomData = new LinkedHashMap<>();
            roomData.put("id", r.getId());
            roomData.put("roomId", r.getRoomId());
            roomData.put("roomName", r.getRoomName() != null && !r.getRoomName().isEmpty() ? r.getRoomName() : r.getRoomId());
            roomData.put("createdAt", r.getCreatedAt());
            roomData.put("admin", r.getAdmin());
            roomData.put("adminId", r.getAdminId());
            roomData.put("memberCount", r.getMembers() != null ? r.getMembers().size() : 1);
            roomData.put("adminApprovalRequired", r.isAdminApprovalRequired());
            response.add(roomData);
        }

        log.info("[ROOM HISTORY: CREATED API] Returning {} created rooms in response for userId='{}', username='{}'", response.size(), userId, username);
        return ResponseEntity.ok(response);
    }

    /**
     * B. Rooms Joined:
     * Display the rooms that the currently authenticated user has joined.
     * Crucial: If room admin removes a user, that room must NO longer appear in that user's history!
     */
    @GetMapping("/history/joined")
    public ResponseEntity<?> getRoomsJoinedHistory() {
        String userId = resolveAuthenticatedUserId();
        String username = resolveAuthenticatedUser(null);
        String email = resolveAuthenticatedEmail();

        log.info("[ROOM HISTORY: JOINED API] Authenticated request for userId='{}', username='{}', email='{}'", userId, username, email);

        if ((userId == null || userId.isEmpty()) && (username == null || username.isEmpty()) && (email == null || email.isEmpty())) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Authentication required to view room history");
        }

        List<Room> rooms = roomService.getRoomsJoinedByUser(userId, username, email);
        List<Map<String, Object>> response = new ArrayList<>();

        for (Room r : rooms) {
            boolean isCreator = (r.getAdminId() != null && userId != null && r.getAdminId().equals(userId))
                    || (r.getAdmin() != null && username != null && r.getAdmin().equalsIgnoreCase(username))
                    || (r.getAdmin() != null && email != null && r.getAdmin().equalsIgnoreCase(email));

            if (isCreator) {
                continue;
            }

            RoomMember userMember = r.getMembers().stream()
                    .filter(m -> (userId != null && userId.equals(m.getUserId())) ||
                                 (username != null && m.getUsername() != null && m.getUsername().equalsIgnoreCase(username)) ||
                                 (email != null && m.getUsername() != null && m.getUsername().equalsIgnoreCase(email)))
                    .findFirst()
                    .orElse(null);

            Map<String, Object> roomData = new LinkedHashMap<>();
            roomData.put("id", r.getId());
            roomData.put("roomId", r.getRoomId());
            roomData.put("roomName", r.getRoomName() != null && !r.getRoomName().isEmpty() ? r.getRoomName() : r.getRoomId());
            roomData.put("createdAt", r.getCreatedAt());
            roomData.put("joinedAt", userMember != null && userMember.getJoinedAt() != null ? userMember.getJoinedAt() : r.getCreatedAt());
            roomData.put("role", userMember != null && userMember.getRole() != null ? userMember.getRole() : "MEMBER");
            roomData.put("admin", r.getAdmin());
            roomData.put("memberCount", r.getMembers() != null ? r.getMembers().size() : 1);
            response.add(roomData);
        }

        log.info("[ROOM HISTORY: JOINED API] Returning {} joined rooms in response for userId='{}', username='{}'", response.size(), userId, username);
        return ResponseEntity.ok(response);
    }

    /**
     * Quick rejoin for authenticated members or creators from History:
     */
    @PostMapping("/{roomId}/rejoin")
    public ResponseEntity<?> rejoinRoom(@PathVariable String roomId) {
        if (roomId == null || roomId.trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Room ID cannot be empty");
        }

        String cleanedRoomId = roomId.trim();
        Room room = roomService.getRoom(cleanedRoomId);
        if (room == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Room has been deleted or no longer exists.");
        }

        String userId = resolveAuthenticatedUserId();
        String username = resolveAuthenticatedUser(null);
        String email = resolveAuthenticatedEmail();
        String avatar = resolveAuthenticatedAvatar(null);

        if (room.isRemovedUser(userId, username) || (email != null && room.isRemoved(email))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You have been removed from this room and cannot rejoin.");
        }

        boolean isCreatorAdmin = (room.getAdminId() != null && userId != null && room.getAdminId().equals(userId))
                || (room.getAdmin() != null && username != null && room.getAdmin().equalsIgnoreCase(username))
                || (room.getAdmin() != null && email != null && room.getAdmin().equalsIgnoreCase(email));

        RoomMember userMember = room.getMembers().stream()
                .filter(m -> (userId != null && userId.equals(m.getUserId())) ||
                             (username != null && m.getUsername() != null && m.getUsername().equalsIgnoreCase(username)) ||
                             (email != null && m.getUsername() != null && m.getUsername().equalsIgnoreCase(email)))
                .findFirst()
                .orElse(null);

        boolean isMember = isCreatorAdmin || userMember != null;

        if (!isMember) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body("You are not an active member of this room. Please enter the invitation code.");
        }

        boolean roomModified = false;
        String effectiveUsername = username != null ? username : (email != null ? email : "User");

        if (isCreatorAdmin) {
            if ((room.getAdminId() == null || room.getAdminId().isEmpty()) && userId != null) {
                room.setAdminId(userId);
                roomModified = true;
            }
            if (userMember == null) {
                userMember = new RoomMember(userId, effectiveUsername, "ADMIN", avatar != null ? avatar : "avatar1.png");
                userMember.setJoinedAt(room.getCreatedAt() != null ? room.getCreatedAt() : new Date());
                room.getMembers().add(userMember);
                roomModified = true;
            } else {
                if (userMember.getUserId() == null && userId != null) {
                    userMember.setUserId(userId);
                    roomModified = true;
                }
                if (!"ADMIN".equalsIgnoreCase(userMember.getRole())) {
                    userMember.setRole("ADMIN");
                    roomModified = true;
                }
            }
        } else if (userMember != null) {
            if (userMember.getUserId() == null && userId != null) {
                userMember.setUserId(userId);
                roomModified = true;
            }
            if (avatar != null && !avatar.isEmpty() && !avatar.equals(userMember.getAvatar())) {
                userMember.setAvatar(avatar);
                roomModified = true;
            }
        }

        if (userMember != null) {
            userMember.setConnected(true);
            userMember.setLastSeenAt(new Date());
            roomModified = true;
        }

        if (roomModified) {
            roomService.saveRoom(room);
        } else {
            roomService.updateMemberConnection(cleanedRoomId, effectiveUsername, true);
        }

        String displayName = (userMember != null && userMember.getUsername() != null) ? userMember.getUsername() : effectiveUsername;
        String userAvatar = (userMember != null && userMember.getAvatar() != null) ? userMember.getAvatar() : (avatar != null ? avatar : "avatar1.png");

        String token = roomSecurityService.generateToken(cleanedRoomId, displayName, isCreatorAdmin);
        String inviteCode = isCreatorAdmin ? roomSecurityService.decryptInviteCode(room.getEncryptedInviteCode()) : null;
        String inviteToken = isCreatorAdmin && room.getEncryptedInviteToken() != null
                ? roomSecurityService.decryptInviteCode(room.getEncryptedInviteToken())
                : null;

        String effectiveRoomName = (room.getRoomName() != null && !room.getRoomName().isEmpty()) ? room.getRoomName() : room.getRoomId();

        AuthResponse auth = new AuthResponse(cleanedRoomId, effectiveRoomName, displayName, isCreatorAdmin, token, inviteCode, userAvatar, inviteToken, null);
        return ResponseEntity.ok(auth);
    }

    private String resolveAuthenticatedUser(String fallbackUser) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            Object principal = auth.getPrincipal();
            if (principal instanceof com.substring.chat.New_Chatapp_backend.security.UserPrincipal userPrincipal) {
                if (userPrincipal.getName() != null && !userPrincipal.getName().trim().isEmpty()) {
                    return userPrincipal.getName().trim();
                } else if (userPrincipal.getEmail() != null && !userPrincipal.getEmail().trim().isEmpty()) {
                    return userPrincipal.getEmail().trim();
                }
            }
            if (auth.getName() != null && !auth.getName().trim().isEmpty()) {
                return auth.getName().trim();
            }
        }
        return fallbackUser;
    }

    private String resolveAuthenticatedEmail() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            Object principal = auth.getPrincipal();
            if (principal instanceof com.substring.chat.New_Chatapp_backend.security.UserPrincipal userPrincipal) {
                if (userPrincipal.getEmail() != null && !userPrincipal.getEmail().trim().isEmpty()) {
                    return userPrincipal.getEmail().trim();
                }
            }
        }
        return null;
    }

    private String resolveAuthenticatedAvatar(String fallbackAvatar) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            Object principal = auth.getPrincipal();
            if (principal instanceof com.substring.chat.New_Chatapp_backend.security.UserPrincipal userPrincipal) {
                if (userPrincipal.getAvatar() != null && !userPrincipal.getAvatar().trim().isEmpty()) {
                    return userPrincipal.getAvatar().trim();
                } else if (userPrincipal.getPicture() != null && !userPrincipal.getPicture().trim().isEmpty()) {
                    return userPrincipal.getPicture().trim();
                }
            }
        }
        return fallbackAvatar;
    }

    private String resolveAuthenticatedUserId() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !(auth instanceof AnonymousAuthenticationToken)) {
            Object principal = auth.getPrincipal();
            if (principal instanceof com.substring.chat.New_Chatapp_backend.security.UserPrincipal userPrincipal) {
                if (userPrincipal.getId() != null && !userPrincipal.getId().trim().isEmpty()) {
                    return userPrincipal.getId().trim();
                }
            }
            if (userRepo != null) {
                String name = auth.getName();
                if (name != null && !name.trim().isEmpty()) {
                    Optional<User> uOpt = userRepo.findByEmail(name.trim());
                    if (uOpt.isPresent()) {
                        return uOpt.get().getId();
                    }
                    List<User> byName = userRepo.findByNameIgnoreCase(name.trim());
                    if (byName != null && !byName.isEmpty()) {
                        return byName.get(0).getId();
                    }
                }
            }
        }
        return null;
    }

    // ================= WHATSAPP-STYLE MESSAGE MANAGEMENT ENDPOINTS =================

    /**
     * Delete selected messages for the authenticated user only (Delete for me).
     * Persisted in MongoDB per-user hidden/deleted-for field so messages do not reappear after refresh.
     */
    @PostMapping("/{roomId}/messages/delete-for-me")
    public ResponseEntity<?> deleteMessagesForMe(
            @PathVariable String roomId,
            @RequestBody DeleteMessagesRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader) {

        Room room = roomService.getRoom(roomId);
        if (room == null) {
            return ResponseEntity.badRequest().body("Room not found: " + roomId);
        }

        String token = tokenHeader != null ? tokenHeader : authHeader;
        RoomTokenClaims claims = roomSecurityService.validateToken(token, roomId);
        String username = claims != null ? claims.getUsername() : resolveAuthenticatedUser(null);
        String userId = resolveAuthenticatedUserId();

        if (username == null && userId == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized to delete messages in this room.");
        }

        if (request == null || request.getMessageIds() == null || request.getMessageIds().isEmpty()) {
            return ResponseEntity.badRequest().body("No message IDs provided for deletion.");
        }

        int count = messageService.deleteMessagesForMe(roomId, request.getMessageIds(), userId, username);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "deletedCount", count,
                "message", "Selected messages deleted for you."
        ));
    }

    /**
     * Delete selected messages for everyone.
     * Enforces STRICT ownership validation on the backend using authenticated user identity.
     * Rejects request if ANY selected message belongs to another user.
     */
    @PostMapping("/{roomId}/messages/delete-for-everyone")
    public ResponseEntity<?> deleteMessagesForEveryone(
            @PathVariable String roomId,
            @RequestBody DeleteMessagesRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader) {

        Room room = roomService.getRoom(roomId);
        if (room == null) {
            return ResponseEntity.badRequest().body("Room not found: " + roomId);
        }

        String token = tokenHeader != null ? tokenHeader : authHeader;
        RoomTokenClaims claims = roomSecurityService.validateToken(token, roomId);
        String username = claims != null ? claims.getUsername() : resolveAuthenticatedUser(null);
        String userId = resolveAuthenticatedUserId();

        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized to delete messages.");
        }

        if (request == null || request.getMessageIds() == null || request.getMessageIds().isEmpty()) {
            return ResponseEntity.badRequest().body("No message IDs provided for deletion.");
        }

        try {
            List<Message> deletedMsgs = messageService.deleteMessagesForEveryone(roomId, request.getMessageIds(), userId, username);

            // Broadcast real-time deletion event to all connected clients in the room
            if (messagingTemplate != null && !deletedMsgs.isEmpty()) {
                Map<String, Object> event = new HashMap<>();
                event.put("eventType", "MESSAGES_DELETED_FOR_EVERYONE");
                event.put("messageIds", request.getMessageIds());
                messagingTemplate.convertAndSend("/topic/room/" + roomId, (Object) event);
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "deletedCount", deletedMsgs.size(),
                    "message", "Selected messages deleted for everyone."
            ));
        } catch (SecurityException se) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(se.getMessage());
        } catch (IllegalArgumentException iae) {
            return ResponseEntity.badRequest().body(iae.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Failed to delete messages: " + e.getMessage());
        }
    }

    /**
     * Toggles or updates emoji reaction on a message.
     * Broadcasts updated message with reactions in real time to all room participants.
     */
    @PostMapping("/{roomId}/messages/{messageId}/reactions")
    public ResponseEntity<?> toggleReaction(
            @PathVariable String roomId,
            @PathVariable String messageId,
            @RequestBody ReactionRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader,
            @RequestHeader(value = "X-Room-Token", required = false) String tokenHeader) {

        Room room = roomService.getRoom(roomId);
        if (room == null) {
            return ResponseEntity.badRequest().body("Room not found: " + roomId);
        }

        String token = tokenHeader != null ? tokenHeader : authHeader;
        RoomTokenClaims claims = roomSecurityService.validateToken(token, roomId);
        String username = claims != null ? claims.getUsername() : resolveAuthenticatedUser(null);

        if (username == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        if (request == null || request.getEmoji() == null || request.getEmoji().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Emoji is required.");
        }

        try {
            Message updated = messageService.toggleReaction(roomId, messageId, username, request.getEmoji());

            if (messagingTemplate != null) {
                messagingTemplate.convertAndSend("/topic/room/" + roomId, (Object) updated);
            }

            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body("Error updating reaction: " + e.getMessage());
        }
    }

    /**
     * Retrieve or create 1-to-1 direct private chat between current user and target recipient.
     */
    @PostMapping("/direct")
    public ResponseEntity<?> getOrCreateDirectChat(
            @RequestBody DirectChatRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String currentUserId = resolveAuthenticatedUserId();
        String currentUsername = resolveAuthenticatedUser(null);
        String currentEmail = resolveAuthenticatedEmail();
        String currentAvatar = resolveAuthenticatedAvatar("Felix");

        if (currentUserId == null && currentUsername == null && currentEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        if (request == null || request.getRecipient() == null || request.getRecipient().trim().isEmpty()) {
            return ResponseEntity.badRequest().body("Recipient is required");
        }

        String recipientQuery = request.getRecipient().trim();

        // Resolve target user from database
        User targetUser = null;
        if (userRepo != null) {
            targetUser = userRepo.findById(recipientQuery).orElse(null);
            if (targetUser == null) {
                targetUser = userRepo.findByEmail(recipientQuery).orElse(null);
            }
            if (targetUser == null) {
                List<User> byName = userRepo.findByNameIgnoreCase(recipientQuery);
                if (byName != null && !byName.isEmpty()) {
                    targetUser = byName.get(0);
                }
            }
        }

        if (targetUser == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body("Recipient not found: " + recipientQuery);
        }

        // Prevent direct chat with self
        if ((currentUserId != null && currentUserId.equals(targetUser.getId())) ||
            (currentEmail != null && currentEmail.equalsIgnoreCase(targetUser.getEmail())) ||
            (currentUsername != null && currentUsername.equalsIgnoreCase(targetUser.getName()))) {
            return ResponseEntity.badRequest().body("Cannot start private chat with yourself");
        }

        String idA = currentUserId != null ? currentUserId : (currentUsername != null ? currentUsername : "userA");
        String idB = targetUser.getId() != null ? targetUser.getId() : targetUser.getName();

        // Deterministic roomId for 1-on-1 direct pair
        String directRoomId = "direct_" + (idA.compareTo(idB) < 0 ? idA + "_" + idB : idB + "_" + idA);

        Room directRoom = roomService.getRoom(directRoomId);
        Date now = new Date();

        // Reuse an existing direct conversation between the same two users instead of creating duplicate rooms
        if (directRoom == null && roomRepo != null) {
            List<Room> allDirectRooms = roomRepo.findAll();
            for (Room r : allDirectRooms) {
                if (!"DIRECT".equalsIgnoreCase(r.getRoomType()) && (r.getRoomId() == null || !r.getRoomId().startsWith("direct_"))) {
                    continue;
                }
                boolean matchesA = (currentUserId != null && r.getParticipantUserIds().contains(currentUserId))
                        || (currentUsername != null && r.isMember(currentUsername));
                boolean matchesB = (targetUser.getId() != null && r.getParticipantUserIds().contains(targetUser.getId()))
                        || (targetUser.getName() != null && r.isMember(targetUser.getName()));
                if (matchesA && matchesB) {
                    directRoom = r;
                    directRoomId = r.getRoomId();
                    break;
                }
            }
        }
        if (directRoom == null) {
            directRoom = new Room();
            directRoom.setRoomId(directRoomId);
            directRoom.setRoomName(targetUser.getName());
            directRoom.setRoomType("DIRECT");
            directRoom.setAdmin(currentUsername != null ? currentUsername : "User");
            directRoom.setAdminId(currentUserId);
            directRoom.setCreatedAt(now);
            directRoom.setLastActivityAt(now);
            directRoom.setParticipantUserIds(new ArrayList<>(List.of(idA, idB)));

            RoomMember memberA = new RoomMember(currentUserId, currentUsername != null ? currentUsername : "User", "MEMBER", currentAvatar);
            memberA.setJoinedAt(now);
            memberA.setConnected(true);

            RoomMember memberB = new RoomMember(targetUser.getId(), targetUser.getName(), "MEMBER", targetUser.getAvatar() != null ? targetUser.getAvatar() : "Felix");
            memberB.setJoinedAt(now);
            memberB.setConnected(false);

            directRoom.getMembers().add(memberA);
            directRoom.getMembers().add(memberB);

            roomService.saveRoom(directRoom);
        } else {
            // Ensure both members are present in member list
            String effUser = currentUsername != null ? currentUsername : "User";
            boolean hasA = directRoom.getMembers().stream().anyMatch(m -> m.getUsername() != null && m.getUsername().equalsIgnoreCase(effUser));
            if (!hasA) {
                RoomMember memberA = new RoomMember(currentUserId, effUser, "MEMBER", currentAvatar);
                memberA.setJoinedAt(now);
                directRoom.getMembers().add(memberA);
                roomService.saveRoom(directRoom);
            }
        }

        String effectiveUserName = currentUsername != null ? currentUsername : "User";
        String roomToken = roomSecurityService.generateToken(directRoomId, effectiveUserName, false);

        Map<String, Object> resp = new HashMap<>();
        resp.put("roomId", directRoomId);
        resp.put("roomName", targetUser.getName());
        resp.put("roomType", "DIRECT");
        resp.put("token", roomToken);
        resp.put("recipient", Map.of(
                "id", targetUser.getId() != null ? targetUser.getId() : "",
                "name", targetUser.getName() != null ? targetUser.getName() : "",
                "avatar", targetUser.getAvatar() != null ? targetUser.getAvatar() : "Felix"
        ));

        return ResponseEntity.ok(resp);
    }

    /**
     * List active direct private conversations for the current user.
     */
    @GetMapping("/direct/conversations")
    public ResponseEntity<?> getDirectConversations() {
        String currentUserId = resolveAuthenticatedUserId();
        String currentUsername = resolveAuthenticatedUser(null);
        String currentEmail = resolveAuthenticatedEmail();

        if (currentUserId == null && currentUsername == null && currentEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        List<Room> allRooms = roomRepo != null ? roomRepo.findAll() : Collections.emptyList();
        List<Map<String, Object>> directConversations = new ArrayList<>();

        for (Room r : allRooms) {
            if (!"DIRECT".equalsIgnoreCase(r.getRoomType())) continue;
            boolean isParticipant = (currentUserId != null && r.getParticipantUserIds().contains(currentUserId))
                    || r.isMember(currentUsername);
            if (!isParticipant) continue;

            // Find recipient/other participant
            RoomMember otherMember = r.getMembers().stream()
                    .filter(m -> (currentUsername == null || !currentUsername.equalsIgnoreCase(m.getUsername())) &&
                                 (currentUserId == null || !currentUserId.equals(m.getUserId())))
                    .findFirst()
                    .orElse(null);

            String otherName = otherMember != null ? otherMember.getUsername() : "Contact";
            String otherAvatar = otherMember != null ? otherMember.getAvatar() : "Felix";
            String otherId = otherMember != null ? otherMember.getUserId() : "";

            List<Message> msgs = messageService.getValidMessagesForRoom(r.getRoomId(), currentUserId, currentUsername);
            Message lastMsg = msgs.isEmpty() ? null : msgs.get(msgs.size() - 1);

            Map<String, Object> conv = new HashMap<>();
            conv.put("roomId", r.getRoomId());
            conv.put("roomName", otherName);
            conv.put("recipient", Map.of(
                    "id", otherId != null ? otherId : "",
                    "name", otherName,
                    "avatar", otherAvatar != null ? otherAvatar : "Felix"
            ));
            conv.put("lastMessage", lastMsg != null ? (lastMsg.isDeletedForEveryone() ? "This message was deleted" : lastMsg.getContent()) : "No messages yet");
            conv.put("lastMessageEncrypted", lastMsg != null && lastMsg.isEncrypted());
            conv.put("lastMessageIv", lastMsg != null ? lastMsg.getIv() : null);
            conv.put("lastMessageSender", lastMsg != null ? lastMsg.getSender() : null);
            conv.put("lastActivityAt", r.getLastActivityAt() != null ? r.getLastActivityAt() : r.getCreatedAt());
            conv.put("createdAt", r.getCreatedAt());

            directConversations.add(conv);
        }

        directConversations.sort((a, b) -> {
            Date da = (Date) a.get("lastActivityAt");
            Date db = (Date) b.get("lastActivityAt");
            if (da == null && db == null) return 0;
            if (da == null) return 1;
            if (db == null) return -1;
            return db.compareTo(da);
        });

        return ResponseEntity.ok(directConversations);
    }

    /**
     * Resolve actual direct messaging contacts for the authenticated user.
     * A user qualifies as a contact IF AND ONLY IF there is an existing DIRECT room
     * between the authenticated user and that contact where at least one direct message
     * has been sent or received.
     * Excludes the current user, does not include group members, and does not return
     * the entire user collection.
     */
    private List<Map<String, Object>> resolveDirectMessagingContacts(String currentUserId, String currentUsername, String currentEmail) {
        if (roomRepo == null) return Collections.emptyList();

        List<Room> allRooms = roomRepo.findAll();
        Map<String, Map<String, Object>> contactsMap = new LinkedHashMap<>();

        for (Room r : allRooms) {
            if (r == null) continue;
            boolean isDirect = "DIRECT".equalsIgnoreCase(r.getRoomType()) ||
                    (r.getRoomId() != null && r.getRoomId().startsWith("direct_"));
            if (!isDirect) continue;

            // Verify the authenticated user is a participant in this direct room
            boolean isCurrentParticipant = false;
            if (currentUserId != null && r.getParticipantUserIds() != null && r.getParticipantUserIds().contains(currentUserId)) {
                isCurrentParticipant = true;
            } else if (currentUsername != null && r.isMember(currentUsername)) {
                isCurrentParticipant = true;
            } else if (currentEmail != null && r.isMember(currentEmail)) {
                isCurrentParticipant = true;
            } else if (currentUserId != null && currentUserId.equals(r.getAdminId())) {
                isCurrentParticipant = true;
            } else if (currentUsername != null && currentUsername.equalsIgnoreCase(r.getAdmin())) {
                isCurrentParticipant = true;
            }

            if (!isCurrentParticipant) continue;

            // Verify that at least one direct message was sent or received in this room
            List<Message> msgs = messageRepo != null ? messageRepo.findByRoomId(r.getRoomId()) : Collections.emptyList();
            if (msgs == null || msgs.isEmpty()) {
                // No direct message relationship established yet
                continue;
            }

            // Find the other participant in this direct room
            RoomMember otherMember = null;
            if (r.getMembers() != null) {
                for (RoomMember m : r.getMembers()) {
                    if (m == null) continue;
                    boolean isSelf = (currentUserId != null && currentUserId.equals(m.getUserId()))
                            || (currentUsername != null && currentUsername.equalsIgnoreCase(m.getUsername()))
                            || (currentEmail != null && currentEmail.equalsIgnoreCase(m.getUsername()));
                    if (!isSelf) {
                        otherMember = m;
                        break;
                    }
                }
            }

            String otherUserId = otherMember != null ? otherMember.getUserId() : null;
            String otherUsername = otherMember != null ? otherMember.getUsername() : null;
            String otherAvatar = (otherMember != null && otherMember.getAvatar() != null) ? otherMember.getAvatar() : "Felix";
            String otherEmail = "";

            if (otherUserId == null && r.getParticipantUserIds() != null) {
                for (String pid : r.getParticipantUserIds()) {
                    if (pid != null && !pid.equals(currentUserId) && !pid.equalsIgnoreCase(currentUsername)) {
                        otherUserId = pid;
                        break;
                    }
                }
            }

            // Enrich contact details from User database if available
            if (userRepo != null) {
                User u = null;
                if (otherUserId != null) {
                    u = userRepo.findById(otherUserId).orElse(null);
                }
                if (u == null && otherUsername != null) {
                    u = userRepo.findByEmail(otherUsername).orElse(null);
                    if (u == null) {
                        List<User> byName = userRepo.findByNameIgnoreCase(otherUsername);
                        if (byName != null && !byName.isEmpty()) {
                            u = byName.get(0);
                        }
                    }
                }
                if (u != null) {
                    if (u.getId() != null) otherUserId = u.getId();
                    if (u.getName() != null) otherUsername = u.getName();
                    if (u.getAvatar() != null) otherAvatar = u.getAvatar();
                    if (u.getEmail() != null) otherEmail = u.getEmail();
                }
            }

            // Exclude self under all circumstances
            if (otherUserId != null && otherUserId.equals(currentUserId)) continue;
            if (otherUsername != null && otherUsername.equalsIgnoreCase(currentUsername)) continue;
            if (!otherEmail.isEmpty() && otherEmail.equalsIgnoreCase(currentEmail)) continue;

            if (otherUsername == null && otherUserId == null) continue;

            String contactKey = otherUserId != null ? otherUserId : otherUsername.toLowerCase();
            if (!contactsMap.containsKey(contactKey)) {
                Map<String, Object> contact = new HashMap<>();
                contact.put("id", otherUserId != null ? otherUserId : "");
                contact.put("name", otherUsername != null ? otherUsername : "User");
                contact.put("avatar", otherAvatar);
                contact.put("email", otherEmail);
                contact.put("roomId", r.getRoomId());
                contactsMap.put(contactKey, contact);
            }
        }

        return new ArrayList<>(contactsMap.values());
    }

    /**
     * Get list of direct messaging contacts for the authenticated user.
     */
    @GetMapping("/direct/contacts")
    public ResponseEntity<?> getDirectContacts() {
        String currentUserId = resolveAuthenticatedUserId();
        String currentUsername = resolveAuthenticatedUser(null);
        String currentEmail = resolveAuthenticatedEmail();

        if (currentUserId == null && currentUsername == null && currentEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        List<Map<String, Object>> contacts = resolveDirectMessagingContacts(currentUserId, currentUsername, currentEmail);
        return ResponseEntity.ok(contacts);
    }

    /**
     * Get list of eligible forward targets (contacts and group rooms).
     */
    @GetMapping("/forward/targets")
    public ResponseEntity<?> getForwardTargets() {
        String currentUserId = resolveAuthenticatedUserId();
        String currentUsername = resolveAuthenticatedUser(null);
        String currentEmail = resolveAuthenticatedEmail();

        if (currentUserId == null && currentUsername == null && currentEmail == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        Map<String, Object> result = new HashMap<>();

        // 1. Direct Messaging Contacts (only users with actual direct message history)
        List<Map<String, Object>> directContacts = resolveDirectMessagingContacts(currentUserId, currentUsername, currentEmail);
        result.put("contacts", directContacts);

        // 2. Group rooms (created or joined)
        List<Map<String, Object>> groupRooms = new ArrayList<>();
        List<Room> created = roomService.getRoomsCreatedByUser(currentUserId, currentUsername, currentEmail);
        List<Room> joined = roomService.getRoomsJoinedByUser(currentUserId, currentUsername, currentEmail);

        Set<String> seenRoomIds = new HashSet<>();
        for (Room r : created) {
            if ("DIRECT".equalsIgnoreCase(r.getRoomType())) continue;
            if (seenRoomIds.add(r.getRoomId())) {
                groupRooms.add(Map.of(
                        "roomId", r.getRoomId(),
                        "roomName", r.getRoomName(),
                        "role", "ADMIN",
                        "memberCount", r.getMembers().size()
                ));
            }
        }
        for (Room r : joined) {
            if ("DIRECT".equalsIgnoreCase(r.getRoomType())) continue;
            if (seenRoomIds.add(r.getRoomId())) {
                groupRooms.add(Map.of(
                        "roomId", r.getRoomId(),
                        "roomName", r.getRoomName(),
                        "role", "MEMBER",
                        "memberCount", r.getMembers().size()
                ));
            }
        }
        result.put("groups", groupRooms);

        return ResponseEntity.ok(result);
    }

    /**
     * Forwards a selected message to one or more recipients / rooms.
     */
    @PostMapping("/messages/forward")
    public ResponseEntity<?> forwardMessage(
            @RequestBody ForwardMessageRequest request,
            @RequestHeader(value = "Authorization", required = false) String authHeader) {

        String currentUserId = resolveAuthenticatedUserId();
        String currentUsername = resolveAuthenticatedUser(null);
        String currentAvatar = resolveAuthenticatedAvatar("Felix");

        if (currentUsername == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("Unauthorized");
        }

        if (request == null || request.getMessageId() == null) {
            return ResponseEntity.badRequest().body("Message ID is required");
        }

        List<String> targetRoomIds = new ArrayList<>();
        if (request.getTargetRoomIds() != null) {
            targetRoomIds.addAll(request.getTargetRoomIds());
        }

        // Target users: resolve or create direct rooms
        if (request.getTargetUserIds() != null) {
            for (String targetUserId : request.getTargetUserIds()) {
                if (targetUserId == null || targetUserId.trim().isEmpty()) continue;
                String idA = currentUserId != null ? currentUserId : currentUsername;
                String idB = targetUserId.trim();
                if (idA.equals(idB)) continue;

                String directRoomId = "direct_" + (idA.compareTo(idB) < 0 ? idA + "_" + idB : idB + "_" + idA);
                Room r = roomService.getRoom(directRoomId);
                if (r == null && userRepo != null) {
                    User uB = userRepo.findById(idB).orElse(null);
                    if (uB != null) {
                        r = new Room();
                        r.setRoomId(directRoomId);
                        r.setRoomName(uB.getName());
                        r.setRoomType("DIRECT");
                        r.setAdmin(currentUsername);
                        r.setAdminId(currentUserId);
                        r.setCreatedAt(new Date());
                        r.setLastActivityAt(new Date());
                        r.setParticipantUserIds(new ArrayList<>(List.of(idA, idB)));
                        r.getMembers().add(new RoomMember(currentUserId, currentUsername, "MEMBER", currentAvatar));
                        r.getMembers().add(new RoomMember(uB.getId(), uB.getName(), "MEMBER", uB.getAvatar() != null ? uB.getAvatar() : "Felix"));
                        roomService.saveRoom(r);
                    }
                }
                if (r != null) {
                    targetRoomIds.add(directRoomId);
                }
            }
        }

        if (targetRoomIds.isEmpty()) {
            return ResponseEntity.badRequest().body("No valid targets selected for forwarding");
        }

        try {
            List<Message> forwarded = messageService.forwardMessage(request.getMessageId(), targetRoomIds, currentUsername, currentAvatar);
            if (messagingTemplate != null) {
                for (Message fwdMsg : forwarded) {
                    messagingTemplate.convertAndSend("/topic/room/" + fwdMsg.getRoomId(), (Object) fwdMsg);
                }
            }
            return ResponseEntity.ok(Map.of("success", true, "forwardedCount", forwarded.size()));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}
