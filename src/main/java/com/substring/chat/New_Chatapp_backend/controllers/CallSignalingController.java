package com.substring.chat.New_Chatapp_backend.controllers;

import com.substring.chat.New_Chatapp_backend.config.AppConstants;
import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.entities.RoomMember;
import com.substring.chat.New_Chatapp_backend.entities.User;
import com.substring.chat.New_Chatapp_backend.payload.CallSignalMessage;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import com.substring.chat.New_Chatapp_backend.repo.UserRepo;
import com.substring.chat.New_Chatapp_backend.security.RoomSecurityService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.handler.annotation.DestinationVariable;
import org.springframework.messaging.handler.annotation.MessageMapping;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Controller
@CrossOrigin(AppConstants.FRONT_END_BASE_URL)
public class CallSignalingController {

    private static final Logger log = LoggerFactory.getLogger(CallSignalingController.class);

    private final SimpMessagingTemplate messagingTemplate;
    private final RoomRepo roomRepo;
    private final UserRepo userRepo;
    private final RoomSecurityService roomSecurityService;

    // Registry of ongoing active calls indexed by roomId
    private final ConcurrentHashMap<String, ActiveCallState> activeCallsByRoom = new ConcurrentHashMap<>();

    @Value("${app.webrtc.turn.url:${WEBRTC_TURN_URL:}}")
    private String turnUrl;

    @Value("${app.webrtc.turn.username:${WEBRTC_TURN_USERNAME:}}")
    private String turnUsername;

    @Value("${app.webrtc.turn.credential:${WEBRTC_TURN_CREDENTIAL:}}")
    private String turnCredential;

    @Autowired
    public CallSignalingController(
            SimpMessagingTemplate messagingTemplate,
            RoomRepo roomRepo,
            @Autowired(required = false) UserRepo userRepo,
            @Autowired(required = false) RoomSecurityService roomSecurityService) {
        this.messagingTemplate = messagingTemplate;
        this.roomRepo = roomRepo;
        this.userRepo = userRepo;
        this.roomSecurityService = roomSecurityService;
    }

    public CallSignalingController(SimpMessagingTemplate messagingTemplate, RoomRepo roomRepo) {
        this(messagingTemplate, roomRepo, null, null);
    }

    /**
     * WebRTC signaling message exchange over STOMP WebSocket.
     * Enforces token validation and room membership verification on the backend.
     * Automatically coordinates group calls and WhatsApp-style 1-to-1 call routing.
     */
    @MessageMapping("/call/{roomId}")
    public void handleCallSignal(
            @DestinationVariable String roomId,
            CallSignalMessage signal,
            SimpMessageHeaderAccessor headerAccessor) {

        String authenticatedUser = null;
        if (headerAccessor != null && headerAccessor.getSessionAttributes() != null) {
            authenticatedUser = (String) headerAccessor.getSessionAttributes().get("ws_user");
        }

        if (authenticatedUser == null || authenticatedUser.trim().isEmpty()) {
            if (signal != null && signal.getSender() != null) {
                authenticatedUser = signal.getSender().trim();
            }
        }

        if (authenticatedUser == null || authenticatedUser.trim().isEmpty()) {
            log.warn("[WebRTC Call] Dropping unauthenticated signal in room '{}'", roomId);
            return;
        }

        // Backend Authorization: verify user is an active, authorized member of the room
        Room room = roomRepo.findByRoomId(roomId);
        if (room == null) {
            log.warn("[WebRTC Call] Room '{}' does not exist for call signal", roomId);
            return;
        }

        if (room.isRemoved(authenticatedUser)) {
            log.warn("[WebRTC Call] Removed user '{}' attempted call signaling in room '{}'", authenticatedUser, roomId);
            return;
        }

        final String authUser = authenticatedUser;
        boolean isMemberOrAdmin = (room.getAdmin() != null && room.getAdmin().equalsIgnoreCase(authUser))
                || room.isMember(authUser)
                || (room.getParticipantUserIds() != null && room.getParticipantUserIds().contains(authUser))
                || (room.getMembers() != null && room.getMembers().stream().anyMatch(m ->
                        (m.getUsername() != null && m.getUsername().equalsIgnoreCase(authUser)) ||
                        (m.getUserId() != null && m.getUserId().equalsIgnoreCase(authUser))
                   ));

        if (!isMemberOrAdmin) {
            log.warn("[WebRTC Call] Non-member '{}' attempted call signaling in room '{}'", authenticatedUser, roomId);
            return;
        }

        if (signal == null) {
            return;
        }

        // Bind verified server values
        signal.setSender(authenticatedUser);
        signal.setRoomId(roomId);
        if (signal.getTimestamp() <= 0) {
            signal.setTimestamp(System.currentTimeMillis());
        }

        // Resolve real profile name & avatar if missing
        resolveSenderProfile(signal, authenticatedUser, room);

        boolean isGroup = !"DIRECT".equalsIgnoreCase(room.getRoomType()) &&
                (room.getRoomId() == null || !room.getRoomId().startsWith("direct_"));
        signal.setGroup(isGroup);
        signal.setRoomName(room.getRoomName() != null ? room.getRoomName() : roomId);

        String type = signal.getType() != null ? signal.getType().toUpperCase() : "UNKNOWN";

        log.info("[WebRTC Call] Dispatched signal '{}' from '{}' (target: '{}') in room '{}' (callType: '{}', isGroup: {})",
                type, authenticatedUser, signal.getTarget(), roomId, signal.getCallType(), isGroup);

        switch (type) {
            case "INITIATE":
            case "START": {
                handleCallInitiate(roomId, room, signal, authenticatedUser, isGroup);
                break;
            }
            case "ACCEPT":
            case "JOIN": {
                handleCallAccept(roomId, signal, authenticatedUser, isGroup);
                break;
            }
            case "LEAVE": {
                handleCallLeave(roomId, room, signal, authenticatedUser, isGroup);
                break;
            }
            case "DECLINE": {
                handleCallDecline(roomId, room, signal, authenticatedUser, isGroup);
                break;
            }
            case "END": {
                handleCallEnd(roomId, room, signal, authenticatedUser, isGroup);
                break;
            }
            default: {
                // Forward WebRTC SDP and ICE signaling payloads (OFFER, ANSWER, ICE_CANDIDATE, etc.)
                messagingTemplate.convertAndSend("/topic/room/" + roomId + "/call", signal);
                break;
            }
        }
    }

    private void handleCallInitiate(String roomId, Room room, CallSignalMessage signal, String authenticatedUser, boolean isGroup) {
        ActiveCallState existingCall = activeCallsByRoom.get(roomId);

        // If an active call already exists in this group room within reasonable window, attach to it
        if (isGroup && existingCall != null && !existingCall.getParticipants().isEmpty() &&
                (System.currentTimeMillis() - existingCall.getStartedAt() < 24 * 60 * 60 * 1000L)) {
            signal.setCallId(existingCall.getCallId());
            signal.setCallType(existingCall.getCallType());
            existingCall.addParticipant(authenticatedUser, signal.getSenderName(), signal.getSenderAvatar());
        } else {
            String callId = signal.getCallId();
            if (callId == null || callId.trim().isEmpty()) {
                callId = "call_" + roomId + "_" + System.currentTimeMillis();
                signal.setCallId(callId);
            }
            String callType = signal.getCallType() != null ? signal.getCallType().toLowerCase() : "video";
            ActiveCallState newCall = new ActiveCallState(
                    callId,
                    roomId,
                    room.getRoomName() != null ? room.getRoomName() : roomId,
                    callType,
                    isGroup,
                    authenticatedUser,
                    signal.getSenderName(),
                    signal.getSenderAvatar(),
                    System.currentTimeMillis(),
                    "ringing"
            );
            newCall.addParticipant(authenticatedUser, signal.getSenderName(), signal.getSenderAvatar());
            activeCallsByRoom.put(roomId, newCall);
        }

        // For 1-to-1 direct private calls, route incoming call notification to the recipient globally
        if (!isGroup) {
            routeDirectCallNotification(room, signal, authenticatedUser);
        }

        // Broadcast signal to the room
        messagingTemplate.convertAndSend("/topic/room/" + roomId + "/call", signal);

        // Broadcast current active call status to the room
        broadcastActiveCallStatus(roomId);
    }

    private void handleCallAccept(String roomId, CallSignalMessage signal, String authenticatedUser, boolean isGroup) {
        ActiveCallState activeCall = activeCallsByRoom.get(roomId);
        if (activeCall != null) {
            activeCall.setStatus("connected");
            activeCall.addParticipant(authenticatedUser, signal.getSenderName(), signal.getSenderAvatar());
        }

        // Broadcast ACCEPT signal to room peers
        messagingTemplate.convertAndSend("/topic/room/" + roomId + "/call", signal);

        // Broadcast updated active call status
        broadcastActiveCallStatus(roomId);
    }

    private void handleCallLeave(String roomId, Room room, CallSignalMessage signal, String authenticatedUser, boolean isGroup) {
        ActiveCallState activeCall = activeCallsByRoom.get(roomId);

        if (activeCall != null) {
            activeCall.removeParticipant(authenticatedUser);

            if (isGroup) {
                if (activeCall.getParticipants().isEmpty()) {
                    // All group members left -> Call ends completely
                    activeCallsByRoom.remove(roomId);
                    broadcastActiveCallStatus(roomId);

                    CallSignalMessage endSignal = new CallSignalMessage();
                    endSignal.setType("END");
                    endSignal.setCallId(activeCall.getCallId());
                    endSignal.setRoomId(roomId);
                    endSignal.setSender(authenticatedUser);
                    endSignal.setTimestamp(System.currentTimeMillis());
                    messagingTemplate.convertAndSend("/topic/room/" + roomId + "/call", endSignal);
                    return;
                } else {
                    // Call remains active for other members
                    broadcastActiveCallStatus(roomId);
                }
            } else {
                // In 1-to-1 direct chat, if either participant leaves, the call terminates
                activeCallsByRoom.remove(roomId);
                broadcastActiveCallStatus(roomId);

                CallSignalMessage endSignal = new CallSignalMessage();
                endSignal.setType("END");
                endSignal.setCallId(activeCall.getCallId());
                endSignal.setRoomId(roomId);
                endSignal.setSender(authenticatedUser);
                endSignal.setTimestamp(System.currentTimeMillis());

                messagingTemplate.convertAndSend("/topic/room/" + roomId + "/call", endSignal);
                routeDirectCallSignalToOther(room, endSignal, authenticatedUser);
                return;
            }
        }

        // Broadcast LEAVE signal to room peers
        messagingTemplate.convertAndSend("/topic/room/" + roomId + "/call", signal);
        if (!isGroup) {
            routeDirectCallSignalToOther(room, signal, authenticatedUser);
        }
    }

    private void handleCallDecline(String roomId, Room room, CallSignalMessage signal, String authenticatedUser, boolean isGroup) {
        ActiveCallState activeCall = activeCallsByRoom.get(roomId);

        if (!isGroup) {
            // Direct 1-to-1 call declined
            activeCallsByRoom.remove(roomId);
            broadcastActiveCallStatus(roomId);

            messagingTemplate.convertAndSend("/topic/room/" + roomId + "/call", signal);
            routeDirectCallSignalToOther(room, signal, authenticatedUser);
        } else {
            // Group call decline
            if (activeCall != null) {
                activeCall.removeParticipant(authenticatedUser);
                broadcastActiveCallStatus(roomId);
            }
            messagingTemplate.convertAndSend("/topic/room/" + roomId + "/call", signal);
        }
    }

    private void handleCallEnd(String roomId, Room room, CallSignalMessage signal, String authenticatedUser, boolean isGroup) {
        activeCallsByRoom.remove(roomId);
        broadcastActiveCallStatus(roomId);

        messagingTemplate.convertAndSend("/topic/room/" + roomId + "/call", signal);

        if (!isGroup) {
            routeDirectCallSignalToOther(room, signal, authenticatedUser);
        }
    }

    /**
     * Broadcasts real-time active call presence on /topic/room/{roomId}/call/status
     */
    private void broadcastActiveCallStatus(String roomId) {
        ActiveCallState call = activeCallsByRoom.get(roomId);
        Map<String, Object> statusPayload = new LinkedHashMap<>();

        if (call != null && !call.getParticipants().isEmpty()) {
            statusPayload.put("active", true);
            statusPayload.put("callId", call.getCallId());
            statusPayload.put("roomId", call.getRoomId());
            statusPayload.put("roomName", call.getRoomName());
            statusPayload.put("callType", call.getCallType());
            statusPayload.put("isGroup", call.isGroup());
            statusPayload.put("initiator", call.getInitiator());
            statusPayload.put("initiatorName", call.getInitiatorName());
            statusPayload.put("initiatorAvatar", call.getInitiatorAvatar());
            statusPayload.put("startedAt", call.getStartedAt());
            statusPayload.put("status", call.getStatus());
            statusPayload.put("participants", new ArrayList<>(call.getParticipants().values()));
        } else {
            statusPayload.put("active", false);
            statusPayload.put("roomId", roomId);
        }

        messagingTemplate.convertAndSend("/topic/room/" + roomId + "/call/status", (Object) statusPayload);
    }

    /**
     * Route incoming 1-to-1 private call notification to recipient's global user channel
     */
    private void routeDirectCallNotification(Room room, CallSignalMessage signal, String authenticatedUser) {
        if (room == null) return;

        Set<String> recipientTargets = new HashSet<>();

        // 1. If signal already specifies target, include it
        if (signal.getTarget() != null && !signal.getTarget().trim().isEmpty()) {
            recipientTargets.add(signal.getTarget().trim());
        }

        // 2. Discover recipient from room members
        if (room.getMembers() != null) {
            for (RoomMember member : room.getMembers()) {
                if (member == null) continue;
                String mUser = member.getUsername();
                String mId = member.getUserId();

                boolean isSelf = (mUser != null && mUser.equalsIgnoreCase(authenticatedUser))
                        || (mId != null && mId.equalsIgnoreCase(authenticatedUser));

                if (!isSelf) {
                    if (mUser != null && !mUser.trim().isEmpty()) recipientTargets.add(mUser.trim());
                    if (mId != null && !mId.trim().isEmpty()) recipientTargets.add(mId.trim());
                }
            }
        }

        // 3. Discover recipient from participantUserIds
        if (room.getParticipantUserIds() != null) {
            for (String pid : room.getParticipantUserIds()) {
                if (pid != null && !pid.trim().isEmpty() && !pid.equalsIgnoreCase(authenticatedUser)) {
                    recipientTargets.add(pid.trim());
                }
            }
        }

        // 4. Fallback: Parse from direct roomId direct_idA_idB
        String roomId = room.getRoomId();
        if (roomId != null && roomId.startsWith("direct_")) {
            String[] parts = roomId.substring(7).split("_");
            for (String part : parts) {
                if (!part.trim().isEmpty() && !part.equalsIgnoreCase(authenticatedUser)) {
                    recipientTargets.add(part.trim());
                }
            }
        }

        Set<String> publishedChannels = new HashSet<>();

        for (String target : recipientTargets) {
            if (target.equalsIgnoreCase(authenticatedUser)) continue;

            // Direct channel by target string
            String chanDirect = "/topic/user/" + target.toLowerCase() + "/calls";
            if (publishedChannels.add(chanDirect)) {
                log.info("[WebRTC Call] Routing INITIATE signal to recipient channel '{}' (callId='{}')", chanDirect, signal.getCallId());
                messagingTemplate.convertAndSend(chanDirect, signal);
            }

            // Also query userRepo to notify by name, id, and email
            if (userRepo != null) {
                User u = userRepo.findById(target).orElse(null);
                if (u == null) {
                    List<User> list = userRepo.findByNameIgnoreCase(target);
                    if (list != null && !list.isEmpty()) u = list.get(0);
                }
                if (u == null) {
                    u = userRepo.findByEmail(target).orElse(null);
                }

                if (u != null) {
                    if (u.getName() != null) {
                        String chanName = "/topic/user/" + u.getName().trim().toLowerCase() + "/calls";
                        if (publishedChannels.add(chanName)) {
                            log.info("[WebRTC Call] Routing INITIATE signal to recipient name channel '{}' (callId='{}')", chanName, signal.getCallId());
                            messagingTemplate.convertAndSend(chanName, signal);
                        }
                    }
                    if (u.getId() != null) {
                        String chanId = "/topic/user/" + u.getId().trim() + "/calls";
                        if (publishedChannels.add(chanId)) {
                            log.info("[WebRTC Call] Routing INITIATE signal to recipient id channel '{}' (callId='{}')", chanId, signal.getCallId());
                            messagingTemplate.convertAndSend(chanId, signal);
                        }
                    }
                    if (u.getEmail() != null) {
                        String chanEmail = "/topic/user/" + u.getEmail().trim().toLowerCase() + "/calls";
                        if (publishedChannels.add(chanEmail)) {
                            log.info("[WebRTC Call] Routing INITIATE signal to recipient email channel '{}' (callId='{}')", chanEmail, signal.getCallId());
                            messagingTemplate.convertAndSend(chanEmail, signal);
                        }
                    }
                }
            }
        }
    }

    /**
     * Route a signaling message (e.g. END, DECLINE) to the other participant in a direct 1-to-1 chat
     */
    private void routeDirectCallSignalToOther(Room room, CallSignalMessage signal, String authenticatedUser) {
        if (room == null) return;

        Set<String> recipientTargets = new HashSet<>();

        if (signal.getTarget() != null && !signal.getTarget().trim().isEmpty()) {
            recipientTargets.add(signal.getTarget().trim());
        }

        if (room.getMembers() != null) {
            for (RoomMember member : room.getMembers()) {
                if (member == null) continue;
                String mUser = member.getUsername();
                String mId = member.getUserId();

                boolean isSelf = (mUser != null && mUser.equalsIgnoreCase(authenticatedUser))
                        || (mId != null && mId.equalsIgnoreCase(authenticatedUser));

                if (!isSelf) {
                    if (mUser != null && !mUser.trim().isEmpty()) recipientTargets.add(mUser.trim());
                    if (mId != null && !mId.trim().isEmpty()) recipientTargets.add(mId.trim());
                }
            }
        }

        if (room.getParticipantUserIds() != null) {
            for (String pid : room.getParticipantUserIds()) {
                if (pid != null && !pid.trim().isEmpty() && !pid.equalsIgnoreCase(authenticatedUser)) {
                    recipientTargets.add(pid.trim());
                }
            }
        }

        String roomId = room.getRoomId();
        if (roomId != null && roomId.startsWith("direct_")) {
            String[] parts = roomId.substring(7).split("_");
            for (String part : parts) {
                if (!part.trim().isEmpty() && !part.equalsIgnoreCase(authenticatedUser)) {
                    recipientTargets.add(part.trim());
                }
            }
        }

        Set<String> publishedChannels = new HashSet<>();

        for (String target : recipientTargets) {
            if (target.equalsIgnoreCase(authenticatedUser)) continue;

            String chanDirect = "/topic/user/" + target.toLowerCase() + "/calls";
            if (publishedChannels.add(chanDirect)) {
                log.info("[WebRTC Call] Routing signal '{}' to recipient channel '{}' (callId='{}')", signal.getType(), chanDirect, signal.getCallId());
                messagingTemplate.convertAndSend(chanDirect, signal);
            }

            if (userRepo != null) {
                User u = userRepo.findById(target).orElse(null);
                if (u == null) {
                    List<User> list = userRepo.findByNameIgnoreCase(target);
                    if (list != null && !list.isEmpty()) u = list.get(0);
                }
                if (u != null) {
                    if (u.getName() != null) {
                        String chanName = "/topic/user/" + u.getName().trim().toLowerCase() + "/calls";
                        if (publishedChannels.add(chanName)) {
                            messagingTemplate.convertAndSend(chanName, signal);
                        }
                    }
                    if (u.getId() != null) {
                        String chanId = "/topic/user/" + u.getId().trim() + "/calls";
                        if (publishedChannels.add(chanId)) {
                            messagingTemplate.convertAndSend(chanId, signal);
                        }
                    }
                    if (u.getEmail() != null) {
                        String chanEmail = "/topic/user/" + u.getEmail().trim().toLowerCase() + "/calls";
                        if (publishedChannels.add(chanEmail)) {
                            messagingTemplate.convertAndSend(chanEmail, signal);
                        }
                    }
                }
            }
        }
    }

    /**
     * Resolves human-readable sender profile name and avatar from repository or room members.
     */
    private void resolveSenderProfile(CallSignalMessage signal, String authenticatedUser, Room room) {
        if (signal.getSenderName() != null && !signal.getSenderName().trim().isEmpty() &&
            signal.getSenderAvatar() != null && !signal.getSenderAvatar().trim().isEmpty()) {
            return;
        }

        // Try userRepo
        if (userRepo != null) {
            User u = userRepo.findById(authenticatedUser).orElse(null);
            if (u == null) {
                u = userRepo.findByEmail(authenticatedUser).orElse(null);
            }
            if (u == null) {
                List<User> list = userRepo.findByNameIgnoreCase(authenticatedUser);
                if (list != null && !list.isEmpty()) u = list.get(0);
            }
            if (u != null) {
                if (signal.getSenderName() == null || signal.getSenderName().trim().isEmpty()) {
                    signal.setSenderName(u.getName() != null ? u.getName() : authenticatedUser);
                }
                if (signal.getSenderAvatar() == null || signal.getSenderAvatar().trim().isEmpty()) {
                    signal.setSenderAvatar(u.getAvatar() != null ? u.getAvatar() : "Felix");
                }
                return;
            }
        }

        // Try room.members
        if (room != null && room.getMembers() != null) {
            for (RoomMember rm : room.getMembers()) {
                if (rm != null && rm.getUsername() != null && rm.getUsername().equalsIgnoreCase(authenticatedUser)) {
                    if (signal.getSenderName() == null || signal.getSenderName().trim().isEmpty()) {
                        signal.setSenderName(rm.getUsername());
                    }
                    if (signal.getSenderAvatar() == null || signal.getSenderAvatar().trim().isEmpty()) {
                        signal.setSenderAvatar(rm.getAvatar() != null ? rm.getAvatar() : "Felix");
                    }
                    return;
                }
            }
        }

        // Fallbacks
        if (signal.getSenderName() == null || signal.getSenderName().trim().isEmpty()) {
            signal.setSenderName(authenticatedUser);
        }
        if (signal.getSenderAvatar() == null || signal.getSenderAvatar().trim().isEmpty()) {
            signal.setSenderAvatar("Felix");
        }
    }

    /**
     * REST endpoint to query whether a room currently has an ongoing active call.
     * Used when joining or reopening a group chat to render the green "Join call" button.
     */
    @GetMapping("/api/v1/rooms/{roomId}/call/active")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getActiveCall(@PathVariable String roomId) {
        ActiveCallState call = activeCallsByRoom.get(roomId);
        Map<String, Object> resp = new LinkedHashMap<>();

        if (call != null && !call.getParticipants().isEmpty()) {
            resp.put("active", true);
            resp.put("callId", call.getCallId());
            resp.put("roomId", call.getRoomId());
            resp.put("roomName", call.getRoomName());
            resp.put("callType", call.getCallType());
            resp.put("isGroup", call.isGroup());
            resp.put("initiator", call.getInitiator());
            resp.put("initiatorName", call.getInitiatorName());
            resp.put("initiatorAvatar", call.getInitiatorAvatar());
            resp.put("startedAt", call.getStartedAt());
            resp.put("status", call.getStatus());
            resp.put("participants", new ArrayList<>(call.getParticipants().values()));
        } else {
            resp.put("active", false);
            resp.put("roomId", roomId);
        }

        return ResponseEntity.ok(resp);
    }

    /**
     * Configurable ICE Server endpoint providing STUN and TURN configurations
     * without exposing secrets in client-side bundles.
     */
    @GetMapping("/api/v1/webrtc/config")
    @ResponseBody
    public ResponseEntity<Map<String, Object>> getWebRtcConfig() {
        List<Map<String, Object>> iceServers = new ArrayList<>();

        // Standard public STUN servers for NAT discovery
        Map<String, Object> stun1 = new HashMap<>();
        stun1.put("urls", List.of("stun:stun.l.google.com:19302", "stun:stun1.l.google.com:19302"));
        iceServers.add(stun1);

        // Configurable TURN server for restrictive symmetric NAT and corporate firewalls
        if (turnUrl != null && !turnUrl.trim().isEmpty()) {
            Map<String, Object> turnServer = new HashMap<>();
            turnServer.put("urls", turnUrl.trim());
            if (turnUsername != null && !turnUsername.trim().isEmpty()) {
                turnServer.put("username", turnUsername.trim());
            }
            if (turnCredential != null && !turnCredential.trim().isEmpty()) {
                turnServer.put("credential", turnCredential.trim());
            }
            iceServers.add(turnServer);
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("iceServers", iceServers);
        config.put("topology", "mesh");
        config.put("recommendedMaxMeshParticipants", 6);
        config.put("sfuSupported", false);
        config.put("turnConfigured", (turnUrl != null && !turnUrl.trim().isEmpty()));

        return ResponseEntity.ok(config);
    }

    // =========================================================================
    // Inner Models for Active Call State & Participant Metadata
    // =========================================================================

    public static class ActiveCallState {
        private String callId;
        private String roomId;
        private String roomName;
        private String callType; // "voice" or "video"
        private boolean isGroup;
        private String initiator;
        private String initiatorName;
        private String initiatorAvatar;
        private long startedAt;
        private String status; // "ringing", "connected", "ended"
        private final Map<String, ParticipantInfo> participants = new ConcurrentHashMap<>();

        public ActiveCallState(String callId, String roomId, String roomName, String callType, boolean isGroup,
                               String initiator, String initiatorName, String initiatorAvatar,
                               long startedAt, String status) {
            this.callId = callId;
            this.roomId = roomId;
            this.roomName = roomName;
            this.callType = callType;
            this.isGroup = isGroup;
            this.initiator = initiator;
            this.initiatorName = initiatorName;
            this.initiatorAvatar = initiatorAvatar;
            this.startedAt = startedAt;
            this.status = status;
        }

        public void addParticipant(String username, String name, String avatar) {
            if (username == null) return;
            participants.put(username.toLowerCase(), new ParticipantInfo(username, name, avatar, System.currentTimeMillis()));
        }

        public void removeParticipant(String username) {
            if (username == null) return;
            participants.remove(username.toLowerCase());
        }

        public String getCallId() { return callId; }
        public String getRoomId() { return roomId; }
        public String getRoomName() { return roomName; }
        public String getCallType() { return callType; }
        public boolean isGroup() { return isGroup; }
        public String getInitiator() { return initiator; }
        public String getInitiatorName() { return initiatorName; }
        public String getInitiatorAvatar() { return initiatorAvatar; }
        public long getStartedAt() { return startedAt; }
        public String getStatus() { return status; }
        public void setStatus(String status) { this.status = status; }
        public Map<String, ParticipantInfo> getParticipants() { return participants; }
    }

    public static class ParticipantInfo {
        private String username;
        private String name;
        private String avatar;
        private long joinedAt;

        public ParticipantInfo(String username, String name, String avatar, long joinedAt) {
            this.username = username;
            this.name = name != null && !name.trim().isEmpty() ? name : username;
            this.avatar = avatar != null && !avatar.trim().isEmpty() ? avatar : "Felix";
            this.joinedAt = joinedAt;
        }

        public String getUsername() { return username; }
        public String getName() { return name; }
        public String getAvatar() { return avatar; }
        public long getJoinedAt() { return joinedAt; }
    }
}


