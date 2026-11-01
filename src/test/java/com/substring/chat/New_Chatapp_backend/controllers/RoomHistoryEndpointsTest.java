package com.substring.chat.New_Chatapp_backend.controllers;

import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.entities.RoomMember;
import com.substring.chat.New_Chatapp_backend.entities.User;
import com.substring.chat.New_Chatapp_backend.security.RoomSecurityService;
import com.substring.chat.New_Chatapp_backend.security.UserPrincipal;
import com.substring.chat.New_Chatapp_backend.services.InactiveRoomCleanupService;
import com.substring.chat.New_Chatapp_backend.services.MessageService;
import com.substring.chat.New_Chatapp_backend.services.RoomService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Date;
import java.util.List;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class RoomHistoryEndpointsTest {

    private MockMvc mockMvc;

    @Mock
    private RoomService roomService;

    @Mock
    private MessageService messageService;

    @Mock
    private InactiveRoomCleanupService inactiveRoomCleanupService;

    @Mock
    private RoomSecurityService roomSecurityService;

    private RoomController roomController;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        roomController = new RoomController(roomService, messageService, inactiveRoomCleanupService, roomSecurityService, null);
        mockMvc = MockMvcBuilders.standaloneSetup(roomController).build();
    }

    @org.junit.jupiter.api.AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateUser(String id, String email, String name, String avatar) {
        User user = new User();
        user.setId(id);
        user.setEmail(email);
        user.setName(name);
        user.setAvatar(avatar);

        UserPrincipal principal = UserPrincipal.create(user);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @Test
    void testGetRoomsCreatedHistory() throws Exception {
        authenticateUser("user-1", "alice@example.com", "Alice", "Felix");

        Room room1 = new Room();
        room1.setId("db-1");
        room1.setRoomId("alice-room-1");
        room1.setRoomName("Alice's Secret Room");
        room1.setAdmin("Alice");
        room1.setAdminId("user-1");
        room1.setCreatedAt(new Date());

        when(roomService.getRoomsCreatedByUser(eq("user-1"), eq("Alice"), any())).thenReturn(List.of(room1));

        mockMvc.perform(get("/api/v1/rooms/history/created"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roomId").value("alice-room-1"))
                .andExpect(jsonPath("$[0].roomName").value("Alice's Secret Room"))
                .andExpect(jsonPath("$[0].admin").value("Alice"));
    }

    @Test
    void testGetRoomsJoinedHistory() throws Exception {
        authenticateUser("user-1", "alice@example.com", "Alice", "Felix");

        Room room1 = new Room();
        room1.setId("db-2");
        room1.setRoomId("general-chat");
        room1.setRoomName("General Chat");
        room1.setAdmin("Bob");
        room1.setCreatedAt(new Date());

        RoomMember member = new RoomMember("user-1", "Alice", "MEMBER", "Felix");
        member.setJoinedAt(new Date());
        room1.getMembers().add(member);

        when(roomService.getRoomsJoinedByUser(eq("user-1"), eq("Alice"), any())).thenReturn(List.of(room1));

        mockMvc.perform(get("/api/v1/rooms/history/joined"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].roomId").value("general-chat"))
                .andExpect(jsonPath("$[0].roomName").value("General Chat"))
                .andExpect(jsonPath("$[0].admin").value("Bob"))
                .andExpect(jsonPath("$[0].role").value("MEMBER"));
    }

    @Test
    void testRejoinRoomSuccess() throws Exception {
        authenticateUser("user-1", "alice@example.com", "Alice", "Felix");

        Room room = new Room();
        room.setId("db-3");
        room.setRoomId("team-dev");
        room.setAdmin("Alice");
        room.setAdminId("user-1");
        room.setEncryptedInviteCode("encCode");

        when(roomService.getRoom("team-dev")).thenReturn(room);
        when(roomSecurityService.generateToken("team-dev", "Alice", true)).thenReturn("jwt-rejoin-token");
        when(roomSecurityService.decryptInviteCode("encCode")).thenReturn("ABCD-1234-EFGH");

        mockMvc.perform(post("/api/v1/rooms/team-dev/rejoin"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.roomId").value("team-dev"))
                .andExpect(jsonPath("$.userName").value("Alice"))
                .andExpect(jsonPath("$.admin").value(true))
                .andExpect(jsonPath("$.token").value("jwt-rejoin-token"))
                .andExpect(jsonPath("$.inviteCode").value("ABCD-1234-EFGH"));
    }

    @Test
    void testRejoinRoomWhenRemoved() throws Exception {
        authenticateUser("user-1", "alice@example.com", "Alice", "Felix");

        Room room = new Room();
        room.setId("db-4");
        room.setRoomId("exclusive-room");
        room.setAdmin("Boss");
        room.getRemovedUserIds().add("user-1");
        room.getRemovedMembers().add("alice");

        when(roomService.getRoom("exclusive-room")).thenReturn(room);

        mockMvc.perform(post("/api/v1/rooms/exclusive-room/rejoin"))
                .andExpect(status().isForbidden());
    }
}
