package com.substring.chat.New_Chatapp_backend.services;

import com.substring.chat.New_Chatapp_backend.config.RetentionConstants;
import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.entities.RoomMember;
import com.substring.chat.New_Chatapp_backend.exceptions.InvalidInviteCodeException;
import com.substring.chat.New_Chatapp_backend.exceptions.InvalidRetentionException;
import com.substring.chat.New_Chatapp_backend.exceptions.RemovedMemberException;
import com.substring.chat.New_Chatapp_backend.exceptions.UnauthorizedAdminException;
import com.substring.chat.New_Chatapp_backend.payload.AuthResponse;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import com.substring.chat.New_Chatapp_backend.security.RoomSecurityService;
import org.springframework.stereotype.Service;

import com.substring.chat.New_Chatapp_backend.entities.JoinRequest;
import com.substring.chat.New_Chatapp_backend.repo.JoinRequestRepo;
import org.springframework.beans.factory.annotation.Autowired;

import com.substring.chat.New_Chatapp_backend.repo.UserRepo;
import com.substring.chat.New_Chatapp_backend.entities.User;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
public class RoomService {

    private static final Logger log = LoggerFactory.getLogger(RoomService.class);

    private final RoomRepo roomRepo;
    private final MessageService messageService;
    private final RoomSecurityService roomSecurityService;
    private final JoinRequestRepo joinRequestRepo;
    private final org.springframework.data.mongodb.core.MongoTemplate mongoTemplate;
    private final UserRepo userRepo;

    @Autowired
    public RoomService(RoomRepo roomRepo,
                       MessageService messageService,
                       RoomSecurityService roomSecurityService,
                       @Autowired(required = false) JoinRequestRepo joinRequestRepo,
                       @Autowired(required = false) org.springframework.data.mongodb.core.MongoTemplate mongoTemplate,
                       @Autowired(required = false) UserRepo userRepo) {
        this.roomRepo = roomRepo;
        this.messageService = messageService;
        this.roomSecurityService = roomSecurityService;
        this.joinRequestRepo = joinRequestRepo;
        this.mongoTemplate = mongoTemplate;
        this.userRepo = userRepo;
    }

    public RoomService(RoomRepo roomRepo,
                       MessageService messageService,
                       RoomSecurityService roomSecurityService,
                       JoinRequestRepo joinRequestRepo) {
        this(roomRepo, messageService, roomSecurityService, joinRequestRepo, null, null);
    }

    public RoomService(RoomRepo roomRepo, MessageService messageService, RoomSecurityService roomSecurityService) {
        this(roomRepo, messageService, roomSecurityService, null, null, null);
    }

    /**
     * Creates a new room and sets the creator as Admin.
     * Generates secure cryptographic invitation code and opaque invitation token.
     * Initializes activity tracking, default 7-day retention, and adds creator as first member with selected avatar.
     */
    public Room createRoom(String roomId, String creator, String roomName, String customInviteCode, String avatar, String creatorId) {
        if (roomId == null || roomId.trim().isEmpty()) {
            throw new IllegalArgumentException("Room ID cannot be empty!");
        }

        String cleanedRoomId = roomId.trim();
        if (roomRepo.findByRoomId(cleanedRoomId) != null) {
            throw new IllegalArgumentException("Room already exist!");
        }

        Room room = new Room();
        room.setRoomId(cleanedRoomId);
        room.setRoomName((roomName != null && !roomName.trim().isEmpty()) ? roomName.trim() : cleanedRoomId);

        String cleanedCreator = (creator != null && !creator.trim().isEmpty()) ? creator.trim() : null;
        room.setAdmin(cleanedCreator);
        if (creatorId != null && !creatorId.trim().isEmpty()) {
            room.setAdminId(creatorId.trim());
        }

        Date now = new Date();
        room.setCreatedAt(now);
        room.setLastActivityAt(now);

        // Use pre-validated customInviteCode if provided, otherwise generate secure unpredictable code
        String inviteCode = (customInviteCode != null && !customInviteCode.trim().isEmpty())
                ? customInviteCode.trim()
                : roomSecurityService.generateInviteCode();

        room.setInviteCodeHash(roomSecurityService.hashInviteCode(inviteCode));
        room.setEncryptedInviteCode(roomSecurityService.encryptInviteCode(inviteCode));

        // Generate opaque cryptographic invitation token
        String inviteToken = roomSecurityService.generateInviteToken();
        room.setInviteTokenHash(roomSecurityService.hashInviteToken(inviteToken));
        room.setEncryptedInviteToken(roomSecurityService.encryptInviteCode(inviteToken));
        room.setInvitationVersion(1);

        // Register creator as first room member with ADMIN role and selected avatar
        if (cleanedCreator != null && !cleanedCreator.isEmpty()) {
            room.getMembers().add(new RoomMember(creatorId, cleanedCreator, "ADMIN", avatar));
        }

        // Default: custom deletion unchecked, default retention is 7 days (604800s)
        room.setCustomDeletionTimeEnabled(false);
        room.setCustomDeletionAfterSeconds(RetentionConstants.DEFAULT_RETENTION_SECONDS);

        return roomRepo.save(room);
    }

    public Room createRoom(String roomId, String creator, String roomName, String customInviteCode, String avatar) {
        return createRoom(roomId, creator, roomName, customInviteCode, avatar, null);
    }

    public Room createRoom(String roomId, String creator, String roomName, String customInviteCode) {
        return createRoom(roomId, creator, roomName, customInviteCode, null, null);
    }

    public Room createRoom(String roomId, String creator, String roomName) {
        return createRoom(roomId, creator, roomName, null, null, null);
    }

    public Room createRoom(String roomId, String creator) {
        return createRoom(roomId, creator, null, null, null, null);
    }

    /**
     * Creates a room and returns an authenticated response with session token, plaintext code, and invite token for Admin.
     */
    public AuthResponse createRoomWithAuth(String roomId, String creator, String roomName, String avatar, String creatorId) {
        Room room = createRoom(roomId, creator, roomName, null, avatar, creatorId);
        String cleanedCreator = (creator != null && !creator.trim().isEmpty()) ? creator.trim() : "";
        String token = roomSecurityService.generateToken(room.getRoomId(), cleanedCreator, true);
        String plaintextCode = roomSecurityService.decryptInviteCode(room.getEncryptedInviteCode());
        String plaintextToken = room.getEncryptedInviteToken() != null
                ? roomSecurityService.decryptInviteCode(room.getEncryptedInviteToken())
                : null;
        return new AuthResponse(room.getRoomId(), room.getRoomId(), cleanedCreator, true, token, plaintextCode, avatar, plaintextToken, null);
    }

    public AuthResponse createRoomWithAuth(String roomId, String creator, String roomName, String avatar) {
        return createRoomWithAuth(roomId, creator, roomName, avatar, null);
    }

    public AuthResponse createRoomWithAuth(String roomId, String creator, String roomName) {
        return createRoomWithAuth(roomId, creator, roomName, null, null);
    }

    public AuthResponse createRoomWithAuth(String roomId, String creator) {
        return createRoomWithAuth(roomId, creator, null, null, null);
    }

    /**
     * Authenticates a user joining a room using room ID, username, and invitation code.
     * Validates code against stored hash, verifies user is not removed/banned,
     * checks that room has not expired, updates member list with selected avatar, and issues a tamper-proof session token.
     */
    public AuthResponse joinRoom(String roomId, String username, String inviteCode, String avatar, String userId) {
        if (roomId == null || roomId.trim().isEmpty()) {
            throw new IllegalArgumentException("Room ID cannot be empty!");
        }
        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be empty!");
        }

        String cleanedRoomId = roomId.trim();
        String cleanedUsername = username.trim();
        String cleanedUserId = userId != null ? userId.trim() : null;

        // Auto-resolve userId from userRepo if missing
        if (cleanedUserId == null && userRepo != null && cleanedUsername != null) {
            Optional<User> uOpt = userRepo.findByEmail(cleanedUsername);
            if (uOpt.isPresent()) {
                cleanedUserId = uOpt.get().getId();
            } else {
                List<User> byName = userRepo.findByNameIgnoreCase(cleanedUsername);
                if (byName != null && !byName.isEmpty()) {
                    cleanedUserId = byName.get(0).getId();
                }
            }
        }

        Room room = roomRepo.findByRoomId(cleanedRoomId);
        if (room == null) {
            throw new IllegalArgumentException("Room Doesn't exist!");
        }

        // Check if room has expired due to 30 consecutive days of inactivity
        if (room.getLastActivityAt() != null) {
            long inactiveMillis = System.currentTimeMillis() - room.getLastActivityAt().getTime();
            if (inactiveMillis > 30L * 24L * 60L * 60L * 1000L) {
                throw new IllegalArgumentException("Room has expired due to inactivity!");
            }
        }

        // Verify user is not removed or banned from this room
        if (room.isRemovedUser(cleanedUserId, cleanedUsername)) {
            throw new RemovedMemberException("You have been removed from this room and cannot rejoin.");
        }

        // Verify invitation code
        if (inviteCode == null || inviteCode.trim().isEmpty()) {
            throw new InvalidInviteCodeException("Invitation code is required to join this room.");
        }

        if (room.getInviteCodeHash() == null) {
            // Self-heal legacy room missing invite code
            String newCode = roomSecurityService.generateInviteCode();
            room.setInviteCodeHash(roomSecurityService.hashInviteCode(newCode));
            room.setEncryptedInviteCode(roomSecurityService.encryptInviteCode(newCode));
            roomRepo.save(room);
        }

        if (!roomSecurityService.verifyInviteCode(inviteCode.trim(), room.getInviteCodeHash())) {
            throw new InvalidInviteCodeException("Invalid room ID or invitation code.");
        }

        // Self-heal legacy room missing invite token
        if (room.getInviteTokenHash() == null) {
            String newToken = roomSecurityService.generateInviteToken();
            room.setInviteTokenHash(roomSecurityService.hashInviteToken(newToken));
            room.setEncryptedInviteToken(roomSecurityService.encryptInviteCode(newToken));
            if (room.getInvitationVersion() <= 0) {
                room.setInvitationVersion(1);
            }
            roomRepo.save(room);
        }

        // Add or update member presence
        boolean isCreatorAdmin = (room.getAdminId() != null && cleanedUserId != null && room.getAdminId().equals(cleanedUserId))
                || (room.getAdmin() != null && room.getAdmin().equalsIgnoreCase(cleanedUsername));
        String role = isCreatorAdmin ? "ADMIN" : "MEMBER";

        final String fUserId = cleanedUserId;
        final String fUsername = cleanedUsername;

        RoomMember existing = room.getMembers().stream()
                .filter(m -> (fUserId != null && fUserId.equals(m.getUserId())) ||
                        (m.getUsername() != null && m.getUsername().equalsIgnoreCase(fUsername)))
                .findFirst()
                .orElse(null);

        Date joinTimestamp;
        if (existing != null) {
            if (cleanedUserId != null) {
                existing.setUserId(cleanedUserId);
            }
            if (existing.getJoinedAt() == null) {
                existing.setJoinedAt(new Date());
            }
            joinTimestamp = existing.getJoinedAt();
            existing.setConnected(true);
            existing.setLastSeenAt(new Date());
            if (avatar != null && !avatar.trim().isEmpty()) {
                existing.setAvatar(avatar.trim());
            }
            if (isCreatorAdmin) {
                existing.setRole("ADMIN");
            }
        } else {
            RoomMember newMember = new RoomMember(cleanedUserId, cleanedUsername, role, avatar);
            if (newMember.getJoinedAt() == null) {
                newMember.setJoinedAt(new Date());
            }
            joinTimestamp = newMember.getJoinedAt();
            room.getMembers().add(newMember);
        }

        roomRepo.save(room);
        log.info("[JOIN ROOM SUCCESS] User '{}' (userId: '{}') joined room '{}' (role: '{}'). Persisted in MongoDB with joinedAt: {}. Total room members: {}",
                cleanedUsername, cleanedUserId, cleanedRoomId, role, joinTimestamp, room.getMembers().size());

        // Issue signed authentication token
        String token = roomSecurityService.generateToken(cleanedRoomId, cleanedUsername, isCreatorAdmin);
        String codeToReturn = isCreatorAdmin ? roomSecurityService.decryptInviteCode(room.getEncryptedInviteCode()) : null;
        String tokenToReturn = isCreatorAdmin && room.getEncryptedInviteToken() != null
                ? roomSecurityService.decryptInviteCode(room.getEncryptedInviteToken())
                : null;

        String memberAvatar = avatar;
        if ((memberAvatar == null || memberAvatar.trim().isEmpty()) && existing != null) {
            memberAvatar = existing.getAvatar();
        }
        return new AuthResponse(cleanedRoomId, room.getRoomId(), cleanedUsername, isCreatorAdmin, token, codeToReturn, memberAvatar, tokenToReturn, null);
    }

    public AuthResponse joinRoom(String roomId, String username, String inviteCode, String avatar) {
        return joinRoom(roomId, username, inviteCode, avatar, null);
    }

    public AuthResponse joinRoom(String roomId, String username, String inviteCode) {
        return joinRoom(roomId, username, inviteCode, null, null);
    }

    /**
     * Loads room by roomId.
     */
    public Room getRoom(String roomId) {
        return roomRepo.findByRoomId(roomId);
    }

    /**
     * Returns list of members for a room.
     */
    public List<RoomMember> getMembers(String roomId) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            throw new IllegalArgumentException("Room Doesn't exist!");
        }
        return room.getMembers();
    }

    /**
     * Admin removes a member from the room.
     * Revokes room access, adds to removedMembers list, AUTOMATICALLY rotates invitation code AND invitation link/token,
     * and invalidates all pending requests from prior invitation versions.
     * Returns the newly generated invitation code for the Admin.
     */
    public String removeMember(String roomId, String requesterAdmin, String targetUsername) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            throw new IllegalArgumentException("Room Doesn't exist!");
        }

        if (room.getAdmin() == null || requesterAdmin == null || !room.getAdmin().equalsIgnoreCase(requesterAdmin.trim())) {
            throw new UnauthorizedAdminException("Only the room Admin can remove members");
        }

        if (targetUsername == null || targetUsername.trim().isEmpty()) {
            throw new IllegalArgumentException("Target username cannot be empty");
        }

        String cleanedTarget = targetUsername.trim();
        if (room.getAdmin().equalsIgnoreCase(cleanedTarget)) {
            throw new IllegalArgumentException("The Admin cannot remove themselves from the room");
        }

        // 1. Find member to capture their userId if present
        RoomMember targetMember = room.getMembers().stream()
                .filter(m -> m.getUsername() != null && m.getUsername().equalsIgnoreCase(cleanedTarget))
                .findFirst().orElse(null);
        if (targetMember != null && targetMember.getUserId() != null) {
            room.getRemovedUserIds().add(targetMember.getUserId());
        }

        // 2. Remove from active member list
        room.getMembers().removeIf(m -> m.getUsername() != null && m.getUsername().equalsIgnoreCase(cleanedTarget));

        // 3. Add to removed members set so old code / rejoining is blocked
        room.getRemovedMembers().add(cleanedTarget.toLowerCase());

        // 3. Automatically regenerate the room invitation code
        String newCode = roomSecurityService.generateInviteCode();
        room.setInviteCodeHash(roomSecurityService.hashInviteCode(newCode));
        room.setEncryptedInviteCode(roomSecurityService.encryptInviteCode(newCode));

        // 4. Automatically rotate the opaque invitation token and increment invitation version
        int newVersion = room.getInvitationVersion() + 1;
        room.setInvitationVersion(newVersion);
        String newInviteToken = roomSecurityService.generateInviteToken();
        room.setInviteTokenHash(roomSecurityService.hashInviteToken(newInviteToken));
        room.setEncryptedInviteToken(roomSecurityService.encryptInviteCode(newInviteToken));

        roomRepo.save(room);

        // 5. Invalidate all pending join requests from older invitation versions
        if (joinRequestRepo != null) {
            try {
                List<JoinRequest> pending = joinRequestRepo.findByRoomIdAndStatus(roomId, "PENDING");
                for (JoinRequest req : pending) {
                    if (req.getInvitationVersion() < newVersion) {
                        req.setStatus("EXPIRED");
                    }
                }
                joinRequestRepo.saveAll(pending);
            } catch (Exception ignored) {
            }
        }

        return newCode;
    }

    /**
     * Member voluntarily leaves the room chat session.
     * Updates active presence (connected = false), refreshes lastSeenAt and lastActivityAt.
     * Preserves membership and join date in MongoDB history so the user can rejoin.
     */
    public boolean leaveRoom(String roomId, String username) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            throw new IllegalArgumentException("Room Doesn't exist!");
        }

        if (username == null || username.trim().isEmpty()) {
            throw new IllegalArgumentException("Username cannot be empty");
        }

        String cleanedUser = username.trim();
        RoomMember member = room.getMembers().stream()
                .filter(m -> m.getUsername() != null && m.getUsername().equalsIgnoreCase(cleanedUser))
                .findFirst()
                .orElse(null);

        if (member != null) {
            member.setConnected(false);
            member.setLastSeenAt(new Date());
            room.setLastActivityAt(new Date());
            roomRepo.save(room);
            log.info("[LEAVE ROOM] Member '{}' exited active room '{}'. Preserved in membership history (connected=false).", cleanedUser, roomId);
            return true;
        }
        return false;
    }

    /**
     * Admin views the current invitation code (decrypted from AES-256-GCM storage).
     */
    public String getInviteCode(String roomId, String requesterAdmin) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            throw new IllegalArgumentException("Room Doesn't exist!");
        }

        if (room.getAdmin() == null || requesterAdmin == null || !room.getAdmin().equalsIgnoreCase(requesterAdmin.trim())) {
            throw new UnauthorizedAdminException("Only the room Admin can view the invitation code");
        }

        if (room.getEncryptedInviteCode() == null) {
            String newCode = roomSecurityService.generateInviteCode();
            room.setInviteCodeHash(roomSecurityService.hashInviteCode(newCode));
            room.setEncryptedInviteCode(roomSecurityService.encryptInviteCode(newCode));
            roomRepo.save(room);
            return newCode;
        }

        return roomSecurityService.decryptInviteCode(room.getEncryptedInviteCode());
    }

    /**
     * Admin views the current invitation token (decrypted from AES-256-GCM storage).
     */
    public String getInviteToken(String roomId, String requesterAdmin) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            throw new IllegalArgumentException("Room Doesn't exist!");
        }

        if (room.getAdmin() == null || requesterAdmin == null || !room.getAdmin().equalsIgnoreCase(requesterAdmin.trim())) {
            throw new UnauthorizedAdminException("Only the room Admin can view the invitation link");
        }

        if (room.getEncryptedInviteToken() == null) {
            String newToken = roomSecurityService.generateInviteToken();
            room.setInviteTokenHash(roomSecurityService.hashInviteToken(newToken));
            room.setEncryptedInviteToken(roomSecurityService.encryptInviteCode(newToken));
            if (room.getInvitationVersion() <= 0) {
                room.setInvitationVersion(1);
            }
            roomRepo.save(room);
            return newToken;
        }

        return roomSecurityService.decryptInviteCode(room.getEncryptedInviteToken());
    }

    /**
     * Admin manually regenerates the invitation code.
     */
    public String regenerateInviteCode(String roomId, String requesterAdmin) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            throw new IllegalArgumentException("Room Doesn't exist!");
        }

        if (room.getAdmin() == null || requesterAdmin == null || !room.getAdmin().equalsIgnoreCase(requesterAdmin.trim())) {
            throw new UnauthorizedAdminException("Only the room Admin can regenerate the invitation code");
        }

        String newCode = roomSecurityService.generateInviteCode();
        room.setInviteCodeHash(roomSecurityService.hashInviteCode(newCode));
        room.setEncryptedInviteCode(roomSecurityService.encryptInviteCode(newCode));

        roomRepo.save(room);
        return newCode;
    }

    /**
     * Admin manually rotates the invitation token and link.
     */
    public String rotateInviteToken(String roomId, String requesterAdmin) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            throw new IllegalArgumentException("Room Doesn't exist!");
        }

        if (room.getAdmin() == null || requesterAdmin == null || !room.getAdmin().equalsIgnoreCase(requesterAdmin.trim())) {
            throw new UnauthorizedAdminException("Only the room Admin can rotate the invitation link");
        }

        int newVersion = room.getInvitationVersion() + 1;
        room.setInvitationVersion(newVersion);
        String newToken = roomSecurityService.generateInviteToken();
        room.setInviteTokenHash(roomSecurityService.hashInviteToken(newToken));
        room.setEncryptedInviteToken(roomSecurityService.encryptInviteCode(newToken));

        roomRepo.save(room);

        if (joinRequestRepo != null) {
            try {
                List<JoinRequest> pending = joinRequestRepo.findByRoomIdAndStatus(roomId, "PENDING");
                for (JoinRequest req : pending) {
                    if (req.getInvitationVersion() < newVersion) {
                        req.setStatus("EXPIRED");
                    }
                }
                joinRequestRepo.saveAll(pending);
            } catch (Exception ignored) {
            }
        }

        return newToken;
    }

    /**
     * Validates an opaque invitation token against stored cryptographic hashes.
     * Returns matching Room if valid and not expired.
     */
    public Room validateInviteToken(String token) {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalArgumentException("Invalid or expired invitation link. Please ask the room admin for a new invitation.");
        }

        String tokenHash = roomSecurityService.hashInviteToken(token.trim());
        Room room = roomRepo.findByInviteTokenHash(tokenHash);
        if (room == null) {
            throw new IllegalArgumentException("Invalid or expired invitation link. Please ask the room admin for a new invitation.");
        }

        if (room.getLastActivityAt() != null) {
            long inactiveMillis = System.currentTimeMillis() - room.getLastActivityAt().getTime();
            if (inactiveMillis > 30L * 24L * 60L * 60L * 1000L) {
                throw new IllegalArgumentException("Room has expired due to inactivity!");
            }
        }

        return room;
    }

    /**
     * Guest submits a join request via invitation link.
     */
    public JoinRequest createJoinRequest(String token, String userName, String avatar, String userId) {
        if (userName == null || userName.trim().isEmpty()) {
            throw new IllegalArgumentException("Display name cannot be empty!");
        }

        Room room = validateInviteToken(token);
        String cleanedUsername = userName.trim();
        String cleanedUserId = userId != null ? userId.trim() : null;

        if (room.isRemovedUser(cleanedUserId, cleanedUsername)) {
            throw new RemovedMemberException("You have been removed from this room and cannot rejoin.");
        }

        // If already an active member, issue token directly
        boolean isExistingMember = room.isMember(cleanedUsername) ||
                (cleanedUserId != null && room.getMembers().stream().anyMatch(m -> cleanedUserId.equals(m.getUserId())));
        if (isExistingMember) {
            boolean isAdmin = (room.getAdminId() != null && cleanedUserId != null && room.getAdminId().equals(cleanedUserId))
                    || (room.getAdmin() != null && room.getAdmin().equalsIgnoreCase(cleanedUsername));
            String existingToken = roomSecurityService.generateToken(room.getRoomId(), cleanedUsername, isAdmin);
            JoinRequest active = new JoinRequest(room.getRoomId(), cleanedUsername, avatar, room.getInvitationVersion(), room.getInviteTokenHash());
            active.setUserId(cleanedUserId);
            active.setStatus("APPROVED");
            active.setApprovedToken(existingToken);
            active.setReviewedAt(new Date());
            active.setNewJoin(false); // Existing member reconnecting
            return active;
        }

        // If room is set to "Direct Join via Link" (bypasses manual admin approval)
        if (!room.isAdminApprovalRequired()) {
            RoomMember newMember = new RoomMember(cleanedUserId, cleanedUsername, "MEMBER", avatar);
            room.getMembers().removeIf(m -> (cleanedUserId != null && cleanedUserId.equals(m.getUserId())) ||
                    (m.getUsername() != null && m.getUsername().equalsIgnoreCase(cleanedUsername)));
            room.getMembers().add(newMember);
            room.setLastActivityAt(new Date());
            roomRepo.save(room);

            String approvedToken = roomSecurityService.generateToken(room.getRoomId(), cleanedUsername, false);
            JoinRequest directRequest = new JoinRequest(room.getRoomId(), cleanedUsername, avatar, room.getInvitationVersion(), room.getInviteTokenHash());
            directRequest.setUserId(cleanedUserId);
            directRequest.setStatus("APPROVED");
            directRequest.setApprovedToken(approvedToken);
            directRequest.setReviewedAt(new Date());
            directRequest.setNewJoin(true); // New direct join via link
            return joinRequestRepo != null ? joinRequestRepo.save(directRequest) : directRequest;
        }

        // Check if an existing pending request is already waiting
        if (joinRequestRepo != null) {
            Optional<JoinRequest> existing = joinRequestRepo.findFirstByRoomIdAndUserNameAndStatus(room.getRoomId(), cleanedUsername, "PENDING");
            if (existing.isPresent()) {
                JoinRequest req = existing.get();
                if (req.getInvitationVersion() == room.getInvitationVersion()) {
                    if (avatar != null && !avatar.trim().isEmpty()) {
                        req.setAvatar(avatar.trim());
                    }
                    if (cleanedUserId != null) {
                        req.setUserId(cleanedUserId);
                    }
                    return joinRequestRepo.save(req);
                } else {
                    req.setStatus("EXPIRED");
                    joinRequestRepo.save(req);
                }
            }
        }

        JoinRequest newRequest = new JoinRequest(room.getRoomId(), cleanedUsername, avatar, room.getInvitationVersion(), room.getInviteTokenHash());
        newRequest.setUserId(cleanedUserId);
        return joinRequestRepo != null ? joinRequestRepo.save(newRequest) : newRequest;
    }

    /**
     * Retrieves all pending join requests for the current invitation version.
     */
    public List<JoinRequest> getPendingJoinRequests(String roomId, String requesterAdmin) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            throw new IllegalArgumentException("Room Doesn't exist!");
        }

        if (room.getAdmin() == null || requesterAdmin == null || !room.getAdmin().equalsIgnoreCase(requesterAdmin.trim())) {
            throw new UnauthorizedAdminException("Only the room Admin can view join requests");
        }

        if (joinRequestRepo == null) {
            return java.util.Collections.emptyList();
        }

        return joinRequestRepo.findByRoomIdAndInvitationVersionAndStatus(roomId, room.getInvitationVersion(), "PENDING");
    }

    /**
     * Admin approves a pending join request.
     */
    public JoinRequest approveJoinRequest(String roomId, String requestId, String requesterAdmin) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            throw new IllegalArgumentException("Room Doesn't exist!");
        }

        if (room.getAdmin() == null || requesterAdmin == null || !room.getAdmin().equalsIgnoreCase(requesterAdmin.trim())) {
            throw new UnauthorizedAdminException("Only the room Admin can approve join requests");
        }

        if (joinRequestRepo == null) {
            throw new IllegalStateException("JoinRequest repository is not available");
        }

        JoinRequest request = joinRequestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Join request not found"));

        if (!request.getRoomId().equals(roomId)) {
            throw new IllegalArgumentException("Join request does not belong to this room");
        }

        if (!"PENDING".equals(request.getStatus())) {
            throw new IllegalArgumentException("Join request is no longer pending (status: " + request.getStatus() + ")");
        }

        // Enforce security rule: requests from obsolete invitation versions cannot be approved
        if (request.getInvitationVersion() != room.getInvitationVersion()) {
            request.setStatus("EXPIRED");
            joinRequestRepo.save(request);
            throw new IllegalArgumentException("This invitation link has expired. The request cannot be approved.");
        }

        String username = request.getUserName();
        if (room.isRemoved(username)) {
            request.setStatus("REJECTED");
            joinRequestRepo.save(request);
            throw new RemovedMemberException("User has been removed from this room.");
        }

        // Add member to room with selected avatar and stable userId
        RoomMember existing = room.getMembers().stream()
                .filter(m -> (request.getUserId() != null && request.getUserId().equals(m.getUserId())) ||
                             (m.getUsername() != null && m.getUsername().equalsIgnoreCase(username)))
                .findFirst()
                .orElse(null);

        if (existing != null) {
            if (request.getUserId() != null) {
                existing.setUserId(request.getUserId());
            }
            existing.setConnected(true);
            existing.setLastSeenAt(new Date());
            if (request.getAvatar() != null && !request.getAvatar().trim().isEmpty()) {
                existing.setAvatar(request.getAvatar().trim());
            }
        } else {
            room.getMembers().add(new RoomMember(request.getUserId(), username, "MEMBER", request.getAvatar()));
        }

        roomRepo.save(room);

        // Issue approved member room token
        String userToken = roomSecurityService.generateToken(roomId, username, false);
        request.setStatus("APPROVED");
        request.setApprovedToken(userToken);
        request.setReviewedAt(new Date());

        return joinRequestRepo.save(request);
    }

    /**
     * Admin rejects a pending join request.
     */
    public JoinRequest rejectJoinRequest(String roomId, String requestId, String requesterAdmin) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            throw new IllegalArgumentException("Room Doesn't exist!");
        }

        if (room.getAdmin() == null || requesterAdmin == null || !room.getAdmin().equalsIgnoreCase(requesterAdmin.trim())) {
            throw new UnauthorizedAdminException("Only the room Admin can reject join requests");
        }

        if (joinRequestRepo == null) {
            throw new IllegalStateException("JoinRequest repository is not available");
        }

        JoinRequest request = joinRequestRepo.findById(requestId)
                .orElseThrow(() -> new IllegalArgumentException("Join request not found"));

        if (!request.getRoomId().equals(roomId)) {
            throw new IllegalArgumentException("Join request does not belong to this room");
        }

        request.setStatus("REJECTED");
        request.setReviewedAt(new Date());

        return joinRequestRepo.save(request);
    }

    /**
     * Retrieves current status of a join request for guest checking.
     */
    public JoinRequest getJoinRequestStatus(String requestId) {
        if (joinRequestRepo == null) return null;
        return joinRequestRepo.findById(requestId).orElse(null);
    }

    /**
     * Updates member connection status (e.g. on WebSocket connect/disconnect).
     */
    public void updateMemberConnection(String roomId, String username, boolean connected) {
        if (roomId == null || username == null) return;
        Room room = roomRepo.findByRoomId(roomId);
        if (room != null && room.getMembers() != null) {
            for (RoomMember m : room.getMembers()) {
                if (m.getUsername() != null && m.getUsername().equalsIgnoreCase(username.trim())) {
                    m.setConnected(connected);
                    m.setLastSeenAt(new Date());
                    roomRepo.save(room);
                    break;
                }
            }
        }
    }

    /**
     * Updates message retention settings for a room.
     * Enforces backend Admin authorization (HTTP 403 if unauthorized)
     * and validates retention durations (HTTP 400 if invalid).
     * Recalculates expiration timestamps of existing messages.
     */
    public Room updateRetentionSettings(String roomId, String requesterUser, Boolean customEnabled, Long customSeconds) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            throw new IllegalArgumentException("Room Doesn't exist!");
        }

        // Direct 1-on-1 private chats have a strict, non-configurable 24-hour auto-delete policy
        if (room.isDirectChat()) {
            throw new IllegalArgumentException("Private chats use a fixed 24-hour auto-delete policy and cannot be reconfigured.");
        }

        // Authorization check: only the persisted Admin can update retention settings
        String admin = room.getAdmin();
        if (admin != null && !admin.trim().isEmpty()) {
            if (requesterUser == null || !admin.trim().equalsIgnoreCase(requesterUser.trim())) {
                throw new UnauthorizedAdminException("Only the room Admin can modify deletion settings");
            }
        } else {
            // If room has no persisted admin (legacy room), reject unauthorized updates
            if (requesterUser == null || requesterUser.trim().isEmpty()) {
                throw new UnauthorizedAdminException("Only the room Admin can modify deletion settings");
            }
        }

        boolean enabled = Boolean.TRUE.equals(customEnabled);
        if (enabled) {
            if (customSeconds != null && customSeconds.equals(RetentionConstants.DEFAULT_RETENTION_SECONDS)) {
                room.setCustomDeletionTimeEnabled(false);
                room.setCustomDeletionAfterSeconds(RetentionConstants.DEFAULT_RETENTION_SECONDS);
            } else if (customSeconds == null || !RetentionConstants.isAllowedDuration(customSeconds)) {
                throw new InvalidRetentionException("Invalid retention duration: " + customSeconds);
            } else {
                room.setCustomDeletionTimeEnabled(true);
                room.setCustomDeletionAfterSeconds(customSeconds);
            }
        } else {
            // When unchecked or set to default, custom retention is disabled and 7-day default (604800s) is restored
            room.setCustomDeletionTimeEnabled(false);
            room.setCustomDeletionAfterSeconds(RetentionConstants.DEFAULT_RETENTION_SECONDS);
        }

        Room savedRoom = roomRepo.save(room);

        // Recalculate expiration of all existing messages using each message's original timestamp
        messageService.recalculateExpirationForRoom(roomId, savedRoom.getEffectiveRetentionSeconds());

        return savedRoom;
    }

    /**
     * Records room activity timestamp, restarting the 30-day inactivity timer.
     */
    public void recordRoomActivity(String roomId) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room != null) {
            room.setLastActivityAt(new Date());
            roomRepo.save(room);
        }
    }

    /**
     * Updates the room's invitation approval mode (Admin only).
     */
    public Room updateInvitationMode(String roomId, String requesterAdmin, boolean adminApprovalRequired) {
        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            throw new IllegalArgumentException("Room Doesn't exist!");
        }

        if (room.getAdmin() == null || requesterAdmin == null || !room.getAdmin().equalsIgnoreCase(requesterAdmin.trim())) {
            throw new UnauthorizedAdminException("Only the room Admin can modify invitation settings");
        }

        room.setAdminApprovalRequired(adminApprovalRequired);
        return roomRepo.save(room);
    }

    public JoinRequest createJoinRequest(String token, String userName, String avatar) {
        return createJoinRequest(token, userName, avatar, null);
    }

    /**
     * Retrieves all rooms created by a specific user (by userId, admin name, or admin email).
     */
    public java.util.List<Room> getRoomsCreatedByUser(String userId, String username, String email) {
        java.util.Set<String> seenRoomIds = new java.util.HashSet<>();
        java.util.List<Room> result = new java.util.ArrayList<>();

        String cUserId = (userId != null && !userId.trim().isEmpty()) ? userId.trim() : null;
        String cUsername = (username != null && !username.trim().isEmpty()) ? username.trim() : null;
        String cEmail = (email != null && !email.trim().isEmpty()) ? email.trim() : null;

        // Auto-enrich user identity from userRepo if available
        if (userRepo != null) {
            if (cUserId != null && (cUsername == null || cEmail == null)) {
                userRepo.findById(cUserId).ifPresent(u -> {
                    // enriched
                });
            } else if (cUserId == null && (cUsername != null || cEmail != null)) {
                if (cEmail != null) {
                    userRepo.findByEmail(cEmail).ifPresent(u -> {
                        // enriched
                    });
                }
                if (cUsername != null) {
                    List<User> byName = userRepo.findByNameIgnoreCase(cUsername);
                    if (byName != null && !byName.isEmpty()) {
                        User u = byName.get(0);
                        // enriched
                    }
                }
            }
        }

        java.util.List<Room> candidates = new java.util.ArrayList<>();

        if (mongoTemplate != null && (cUserId != null || cUsername != null || cEmail != null)) {
            try {
                org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query();
                java.util.List<org.springframework.data.mongodb.core.query.Criteria> criteriaList = new java.util.ArrayList<>();
                if (cUserId != null) {
                    criteriaList.add(org.springframework.data.mongodb.core.query.Criteria.where("adminId").is(cUserId));
                }
                if (cUsername != null) {
                    criteriaList.add(org.springframework.data.mongodb.core.query.Criteria.where("admin").regex("^" + java.util.regex.Pattern.quote(cUsername) + "$", "i"));
                }
                if (cEmail != null) {
                    criteriaList.add(org.springframework.data.mongodb.core.query.Criteria.where("admin").regex("^" + java.util.regex.Pattern.quote(cEmail) + "$", "i"));
                }
                if (!criteriaList.isEmpty()) {
                    query.addCriteria(new org.springframework.data.mongodb.core.query.Criteria().orOperator(criteriaList.toArray(new org.springframework.data.mongodb.core.query.Criteria[0])));
                    candidates.addAll(mongoTemplate.find(query, Room.class));
                }
            } catch (Exception ignored) {
            }
        }

        if (cUserId != null || cUsername != null || cEmail != null) {
            try {
                candidates.addAll(roomRepo.findRoomsCreatedByUser(
                        cUserId != null ? cUserId : "---",
                        cUsername != null ? cUsername : "---",
                        cEmail != null ? cEmail : "---"
                ));
            } catch (Exception ignored) {}
        }

        // Direct fallbacks by adminId and admin
        if (cUserId != null) {
            try {
                candidates.addAll(roomRepo.findByAdminId(cUserId));
            } catch (Exception ignored) {}
        }
        if (cUsername != null) {
            try {
                candidates.addAll(roomRepo.findByAdminIgnoreCase(cUsername));
            } catch (Exception ignored) {}
        }
        if (cEmail != null) {
            try {
                candidates.addAll(roomRepo.findByAdminIgnoreCase(cEmail));
            } catch (Exception ignored) {}
        }

        for (Room r : candidates) {
            if (r == null) continue;
            // Group History Isolation: Only actual GROUP rooms appear under Groups Created.
            // Direct/private rooms must NEVER appear in group history!
            if ("DIRECT".equalsIgnoreCase(r.getRoomType()) || "PRIVATE".equalsIgnoreCase(r.getRoomType()) ||
                    (r.getRoomId() != null && r.getRoomId().startsWith("direct_"))) {
                continue;
            }

            // Verify user is actually the creator
            boolean isCreator = (r.getAdminId() != null && cUserId != null && r.getAdminId().equals(cUserId))
                    || (r.getAdmin() != null && cUsername != null && r.getAdmin().equalsIgnoreCase(cUsername))
                    || (r.getAdmin() != null && cEmail != null && r.getAdmin().equalsIgnoreCase(cEmail));

            if (isCreator) {
                if (r.getAdminId() == null && cUserId != null) {
                    r.setAdminId(cUserId);
                    try { roomRepo.save(r); } catch (Exception ignored) {}
                }

                // Ensure creator is present in members list with ADMIN role
                boolean hasCreatorMember = r.getMembers().stream().anyMatch(m ->
                        (cUserId != null && cUserId.equals(m.getUserId())) ||
                        (cUsername != null && m.getUsername() != null && m.getUsername().equalsIgnoreCase(cUsername)) ||
                        (cEmail != null && m.getUsername() != null && m.getUsername().equalsIgnoreCase(cEmail))
                );
                if (!hasCreatorMember && (cUsername != null || cEmail != null)) {
                    String memberName = cUsername != null ? cUsername : cEmail;
                    RoomMember adminMember = new RoomMember(cUserId, memberName, "ADMIN", null);
                    adminMember.setJoinedAt(r.getCreatedAt() != null ? r.getCreatedAt() : new java.util.Date());
                    r.getMembers().add(adminMember);
                    try { roomRepo.save(r); } catch (Exception ignored) {}
                }

                if (seenRoomIds.add(r.getRoomId())) {
                    result.add(r);
                }
            }
        }

        result.sort((a, b) -> {
            java.util.Date da = a.getCreatedAt() != null ? a.getCreatedAt() : new java.util.Date(0);
            java.util.Date db = b.getCreatedAt() != null ? b.getCreatedAt() : new java.util.Date(0);
            return db.compareTo(da);
        });

        log.info("[ROOM HISTORY: CREATED] Found {} rooms created by user '{}' (userId: '{}', email: '{}')",
                result.size(), cUsername, cUserId, cEmail);

        return result;
    }

    public java.util.List<Room> getRoomsCreatedByUser(String userId, String username) {
        return getRoomsCreatedByUser(userId, username, null);
    }

    /**
     * Retrieves all rooms joined by a specific user.
     * Crucial Rules:
     * 1. Creator must NOT appear in Rooms Joined for rooms they created!
     * 2. If a room admin removes a user from a room, that room must NO longer appear in that user's history!
     * 3. Normal users must see all authorized rooms they have joined.
     */
    public java.util.List<Room> getRoomsJoinedByUser(String userId, String username, String email) {
        String cleanedUserId = (userId != null && !userId.trim().isEmpty()) ? userId.trim() : null;
        String cleanedUsername = (username != null && !username.trim().isEmpty()) ? username.trim() : null;
        String cleanedEmail = (email != null && !email.trim().isEmpty()) ? email.trim() : null;

        // Auto-enrich user identity from userRepo if available
        if (userRepo != null) {
            if (cleanedUserId != null && (cleanedUsername == null || cleanedEmail == null)) {
                Optional<User> uOpt = userRepo.findById(cleanedUserId);
                if (uOpt.isPresent()) {
                    User u = uOpt.get();
                    if (cleanedUsername == null) cleanedUsername = u.getName();
                    if (cleanedEmail == null) cleanedEmail = u.getEmail();
                }
            } else if (cleanedUserId == null && (cleanedUsername != null || cleanedEmail != null)) {
                if (cleanedEmail != null) {
                    Optional<User> uOpt = userRepo.findByEmail(cleanedEmail);
                    if (uOpt.isPresent()) {
                        User u = uOpt.get();
                        cleanedUserId = u.getId();
                        if (cleanedUsername == null) cleanedUsername = u.getName();
                    }
                }
                if (cleanedUserId == null && cleanedUsername != null) {
                    Optional<User> uOpt = userRepo.findByEmail(cleanedUsername);
                    if (uOpt.isPresent()) {
                        User u = uOpt.get();
                        cleanedUserId = u.getId();
                        if (cleanedEmail == null) cleanedEmail = u.getEmail();
                    } else {
                        List<User> byName = userRepo.findByNameIgnoreCase(cleanedUsername);
                        if (byName != null && !byName.isEmpty()) {
                            User u = byName.get(0);
                            cleanedUserId = u.getId();
                            if (cleanedEmail == null) cleanedEmail = u.getEmail();
                        }
                    }
                }
            }
        }

        java.util.List<Room> candidateRooms = new java.util.ArrayList<>();

        if (mongoTemplate != null && (cleanedUserId != null || cleanedUsername != null || cleanedEmail != null)) {
            try {
                org.springframework.data.mongodb.core.query.Query query = new org.springframework.data.mongodb.core.query.Query();
                java.util.List<org.springframework.data.mongodb.core.query.Criteria> criteriaList = new java.util.ArrayList<>();
                if (cleanedUserId != null) {
                    criteriaList.add(org.springframework.data.mongodb.core.query.Criteria.where("members.userId").is(cleanedUserId));
                }
                if (cleanedUsername != null) {
                    criteriaList.add(org.springframework.data.mongodb.core.query.Criteria.where("members.username").regex("^" + java.util.regex.Pattern.quote(cleanedUsername) + "$", "i"));
                }
                if (cleanedEmail != null) {
                    criteriaList.add(org.springframework.data.mongodb.core.query.Criteria.where("members.username").regex("^" + java.util.regex.Pattern.quote(cleanedEmail) + "$", "i"));
                }
                if (!criteriaList.isEmpty()) {
                    query.addCriteria(new org.springframework.data.mongodb.core.query.Criteria().orOperator(criteriaList.toArray(new org.springframework.data.mongodb.core.query.Criteria[0])));
                    candidateRooms.addAll(mongoTemplate.find(query, Room.class));
                }
            } catch (Exception ignored) {
            }
        }

        if (cleanedUserId != null || cleanedUsername != null || cleanedEmail != null) {
            try {
                candidateRooms.addAll(roomRepo.findRoomsJoinedByUser(
                        cleanedUserId != null ? cleanedUserId : "---",
                        cleanedUsername != null ? cleanedUsername : "---",
                        cleanedEmail != null ? cleanedEmail : "---"
                ));
            } catch (Exception ignored) {}
        }

        log.info("[ROOM HISTORY: JOINED] Querying candidate rooms for userId='{}', username='{}', email='{}'. Candidate rooms found: {}",
                cleanedUserId, cleanedUsername, cleanedEmail, candidateRooms.size());

        final String finalUserId = cleanedUserId;
        final String finalUsername = cleanedUsername;
        final String finalEmail = cleanedEmail;

        java.util.List<Room> result = new java.util.ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();

        for (Room r : candidateRooms) {
            if (r == null) continue;
            // Group History Isolation: Only actual GROUP rooms appear under Groups Joined.
            // Direct/private rooms must NEVER appear in group history!
            if ("DIRECT".equalsIgnoreCase(r.getRoomType()) || "PRIVATE".equalsIgnoreCase(r.getRoomType()) ||
                    (r.getRoomId() != null && r.getRoomId().startsWith("direct_"))) {
                continue;
            }

            // Rule 1: CREATOR MUST NOT APPEAR IN ROOMS JOINED FOR ROOMS THEY CREATED
            // Apply this rule based on the authenticated user's actual ID and the room's creator ID
            boolean isCreator = (r.getAdminId() != null && finalUserId != null && r.getAdminId().equals(finalUserId))
                    || (r.getAdmin() != null && finalUsername != null && r.getAdmin().equalsIgnoreCase(finalUsername))
                    || (r.getAdmin() != null && finalEmail != null && r.getAdmin().equalsIgnoreCase(finalEmail));

            if (isCreator) {
                // If the user created this room, DO NOT display in Rooms Joined!
                continue;
            }

            // Rule 2: Check if user is removed from this room: removed users MUST NOT see room in history
            if (r.isRemovedUser(finalUserId, finalUsername) || (finalEmail != null && r.isRemoved(finalEmail))) {
                continue;
            }

            // Rule 3: Verify the user is actually currently in members list
            RoomMember userMember = r.getMembers().stream().filter(m ->
                    (finalUserId != null && finalUserId.equals(m.getUserId())) ||
                    (finalUsername != null && m.getUsername() != null && m.getUsername().equalsIgnoreCase(finalUsername)) ||
                    (finalEmail != null && m.getUsername() != null && m.getUsername().equalsIgnoreCase(finalEmail))
            ).findFirst().orElse(null);

            if (userMember != null) {
                boolean roomUpdated = false;
                if (userMember.getUserId() == null && finalUserId != null) {
                    userMember.setUserId(finalUserId);
                    roomUpdated = true;
                }
                if (userMember.getJoinedAt() == null) {
                    userMember.setJoinedAt(r.getCreatedAt() != null ? r.getCreatedAt() : new java.util.Date());
                    roomUpdated = true;
                }
                if (roomUpdated) {
                    try { roomRepo.save(r); } catch (Exception ignored) {}
                }

                if (seen.add(r.getRoomId())) {
                    result.add(r);
                }
            }
        }

        result.sort((a, b) -> {
            java.util.Date da = getMemberJoinDate(a, finalUserId, finalUsername, finalEmail);
            java.util.Date db = getMemberJoinDate(b, finalUserId, finalUsername, finalEmail);
            return db.compareTo(da);
        });

        log.info("[ROOM HISTORY: JOINED] Returning {} joined rooms for user '{}' (userId: '{}')",
                result.size(), finalUsername, finalUserId);

        return result;
    }

    private java.util.Date getMemberJoinDate(Room room, String userId, String username, String email) {
        if (room == null || room.getMembers() == null) {
            return new java.util.Date(0);
        }
        for (RoomMember m : room.getMembers()) {
            if ((userId != null && userId.equals(m.getUserId())) ||
                (username != null && m.getUsername() != null && m.getUsername().equalsIgnoreCase(username)) ||
                (email != null && m.getUsername() != null && m.getUsername().equalsIgnoreCase(email))) {
                if (m.getJoinedAt() != null) {
                    return m.getJoinedAt();
                }
            }
        }
        return room.getCreatedAt() != null ? room.getCreatedAt() : new java.util.Date(0);
    }

    public java.util.List<Room> getRoomsJoinedByUser(String userId, String username) {
        return getRoomsJoinedByUser(userId, username, null);
    }

    /**
     * Updates user's avatar across all rooms where they are currently a member.
     */
    public void updateUserAvatarInAllRooms(String userId, String username, String email, String newAvatar) {
        if (newAvatar == null || newAvatar.trim().isEmpty()) return;
        String avatar = newAvatar.trim();

        java.util.List<Room> rooms = getRoomsJoinedByUser(userId, username, email);
        for (Room r : rooms) {
            boolean modified = false;
            for (RoomMember m : r.getMembers()) {
                if ((userId != null && userId.equals(m.getUserId())) ||
                    (username != null && m.getUsername() != null && m.getUsername().equalsIgnoreCase(username)) ||
                    (email != null && m.getUsername() != null && m.getUsername().equalsIgnoreCase(email))) {
                    m.setAvatar(avatar);
                    modified = true;
                }
            }
            if (modified) {
                try {
                    roomRepo.save(r);
                } catch (Exception ignored) {}
            }
        }
    }

    public Room saveRoom(Room room) {
        return roomRepo.save(room);
    }
}
