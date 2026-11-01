package com.substring.chat.New_Chatapp_backend.services;

import com.substring.chat.New_Chatapp_backend.controllers.RoomController;
import com.substring.chat.New_Chatapp_backend.entities.Message;
import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.entities.RoomMember;
import com.substring.chat.New_Chatapp_backend.entities.User;
import com.substring.chat.New_Chatapp_backend.payload.DeleteMessagesRequest;
import com.substring.chat.New_Chatapp_backend.payload.DirectChatRequest;
import com.substring.chat.New_Chatapp_backend.payload.ReactionRequest;
import com.substring.chat.New_Chatapp_backend.payload.ForwardMessageRequest;
import com.substring.chat.New_Chatapp_backend.repo.MessageRepo;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import com.substring.chat.New_Chatapp_backend.repo.UserRepo;
import com.substring.chat.New_Chatapp_backend.security.RoomSecurityService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import com.substring.chat.New_Chatapp_backend.security.UserPrincipal;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageManagementAndDirectChatTest {

    @Mock
    private RoomRepo roomRepo;

    @Mock
    private MessageRepo messageRepo;

    @Mock
    private UserRepo userRepo;

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private MessageService messageService;
    private RoomService roomService;
    private RoomSecurityService roomSecurityService;
    private RoomController roomController;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(messageRepo, roomRepo);
        roomSecurityService = new RoomSecurityService("TestSecretKeyForTestingPurposesOnly1234567890!");
        roomService = new RoomService(roomRepo, messageService, roomSecurityService, null, null, userRepo);
        roomController = new RoomController(roomService, messageService, null, roomSecurityService);
    }

    @Test
    @DisplayName("Delete for me adds user to deletedForUsers and filters message from history")
    void testDeleteForMe() {
        String roomId = "room-1";
        Message msg1 = new Message("m1", roomId, "Alice", "Hello Bob", new Date(), null);
        Message msg2 = new Message("m2", roomId, "Bob", "Hello Alice", new Date(), null);

        when(messageRepo.findById("m1")).thenReturn(Optional.of(msg1));
        when(messageRepo.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        int count = messageService.deleteMessagesForMe(roomId, List.of("m1"), "user-bob-id", "Bob");
        assertThat(count).isEqualTo(1);
        assertThat(msg1.getDeletedForUsers()).contains("user-bob-id", "Bob");

        // Now test filtering in getValidMessagesForRoom
        when(messageRepo.findByRoomIdOrderByTimeStampAsc(roomId)).thenReturn(List.of(msg1, msg2));
        List<Message> visibleToBob = messageService.getValidMessagesForRoom(roomId, "user-bob-id", "Bob");
        assertThat(visibleToBob).hasSize(1);
        assertThat(visibleToBob.get(0).getId()).isEqualTo("m2");

        // Visible to Alice
        List<Message> visibleToAlice = messageService.getValidMessagesForRoom(roomId, "user-alice-id", "Alice");
        assertThat(visibleToAlice).hasSize(2);
    }

    @Test
    @DisplayName("Delete for everyone succeeds when all messages belong to authenticated user")
    void testDeleteForEveryoneSuccess() {
        String roomId = "room-1";
        Message msg1 = new Message("m1", roomId, "Alice", "My message 1", new Date(), null);
        Message msg2 = new Message("m2", roomId, "Alice", "My message 2", new Date(), null);

        when(messageRepo.findById("m1")).thenReturn(Optional.of(msg1));
        when(messageRepo.findById("m2")).thenReturn(Optional.of(msg2));
        when(messageRepo.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Message> deleted = messageService.deleteMessagesForEveryone(roomId, List.of("m1", "m2"), "user-alice-id", "Alice");
        assertThat(deleted).hasSize(2);
        assertThat(msg1.isDeletedForEveryone()).isTrue();
        assertThat(msg1.getContent()).isEqualTo("This message was deleted");
        assertThat(msg2.isDeletedForEveryone()).isTrue();
    }

    @Test
    @DisplayName("Delete for everyone REJECTS when selection includes another user's message")
    void testDeleteForEveryoneRejectsOtherUsersMessages() {
        String roomId = "room-1";
        Message msg1 = new Message("m1", roomId, "Alice", "My message 1", new Date(), null);
        Message msg2 = new Message("m2", roomId, "Bob", "Bob's message", new Date(), null);

        when(messageRepo.findById("m1")).thenReturn(Optional.of(msg1));
        when(messageRepo.findById("m2")).thenReturn(Optional.of(msg2));

        assertThatThrownBy(() ->
                messageService.deleteMessagesForEveryone(roomId, List.of("m1", "m2"), "user-alice-id", "Alice")
        ).isInstanceOf(SecurityException.class)
         .hasMessageContaining("Unauthorized: You can only delete your own messages for everyone.");

        // Confirm message content was NOT modified
        assertThat(msg1.isDeletedForEveryone()).isFalse();
        assertThat(msg2.isDeletedForEveryone()).isFalse();
    }

    @Test
    @DisplayName("Emoji reactions toggle: add, change, and remove")
    void testToggleReactions() {
        String roomId = "room-1";
        Message msg = new Message("m1", roomId, "Alice", "Great job!", new Date(), null);
        when(messageRepo.findById("m1")).thenReturn(Optional.of(msg));
        when(messageRepo.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        // 1. Add reaction ❤️
        Message r1 = messageService.toggleReaction(roomId, "m1", "Bob", "❤️");
        assertThat(r1.getReactions()).containsEntry("Bob", "❤️");

        // 2. Change reaction to 👍
        Message r2 = messageService.toggleReaction(roomId, "m1", "Bob", "👍");
        assertThat(r2.getReactions()).containsEntry("Bob", "👍");

        // 3. Click same reaction 👍 to remove it
        Message r3 = messageService.toggleReaction(roomId, "m1", "Bob", "👍");
        assertThat(r3.getReactions()).doesNotContainKey("Bob");
    }

    @Test
    @DisplayName("Forward message creates new messages with forwarded metadata and original sender")
    void testForwardMessage() {
        String sourceRoomId = "room-source";
        Message source = new Message("src-1", sourceRoomId, "Alice", "Important update", new Date(), null);
        when(messageRepo.findById("src-1")).thenReturn(Optional.of(source));

        Room targetRoom1 = new Room();
        targetRoom1.setRoomId("target-room-1");
        when(roomRepo.findByRoomId("target-room-1")).thenReturn(targetRoom1);

        when(messageRepo.save(any(Message.class))).thenAnswer(inv -> inv.getArgument(0));

        List<Message> forwarded = messageService.forwardMessage("src-1", List.of("target-room-1"), "Bob", "Leo");
        assertThat(forwarded).hasSize(1);
        Message fwd = forwarded.get(0);
        assertThat(fwd.getContent()).isEqualTo("Important update");
        assertThat(fwd.getSender()).isEqualTo("Bob");
        assertThat(fwd.isForwarded()).isTrue();
        assertThat(fwd.getForwardedFromSender()).isEqualTo("Alice");
    }

    @Test
    @DisplayName("Direct contacts only includes users with exchanged direct messages")
    void testDirectContactsOnlyIncludesUsersWithExchangedDirectMessages() {
        org.springframework.test.util.ReflectionTestUtils.setField(roomController, "roomRepo", roomRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(roomController, "userRepo", userRepo);
        org.springframework.test.util.ReflectionTestUtils.setField(roomController, "messageRepo", messageRepo);

        User alice = new User();
        alice.setId("u-alice");
        alice.setEmail("alice@example.com");
        alice.setName("Alice");
        alice.setAvatar("Felix");

        User bob = new User();
        bob.setId("u-bob");
        bob.setEmail("bob@example.com");
        bob.setName("Bob");
        bob.setAvatar("Felix");

        User charlie = new User();
        charlie.setId("u-charlie");
        charlie.setEmail("charlie@example.com");
        charlie.setName("Charlie");
        charlie.setAvatar("Felix");

        User dave = new User();
        dave.setId("u-dave");
        dave.setEmail("dave@example.com");
        dave.setName("Dave");
        dave.setAvatar("Felix");

        // Authenticate Alice
        UserPrincipal principal = UserPrincipal.create(alice);
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
        SecurityContextHolder.getContext().setAuthentication(auth);

        // 1. Direct room with Bob - has messages
        Room roomAliceBob = new Room();
        roomAliceBob.setRoomId("direct_u-alice_u-bob");
        roomAliceBob.setRoomType("DIRECT");
        roomAliceBob.setParticipantUserIds(List.of("u-alice", "u-bob"));
        roomAliceBob.getMembers().add(new RoomMember("u-alice", "Alice", "MEMBER", "Felix"));
        roomAliceBob.getMembers().add(new RoomMember("u-bob", "Bob", "MEMBER", "Felix"));

        // 2. Direct room with Charlie - NO messages
        Room roomAliceCharlie = new Room();
        roomAliceCharlie.setRoomId("direct_u-alice_u-charlie");
        roomAliceCharlie.setRoomType("DIRECT");
        roomAliceCharlie.setParticipantUserIds(List.of("u-alice", "u-charlie"));
        roomAliceCharlie.getMembers().add(new RoomMember("u-alice", "Alice", "MEMBER", "Felix"));
        roomAliceCharlie.getMembers().add(new RoomMember("u-charlie", "Charlie", "MEMBER", "Felix"));

        // 3. Group room where Dave is also a member
        Room groupRoom = new Room();
        groupRoom.setRoomId("team-group");
        groupRoom.setRoomType("GROUP");
        groupRoom.getMembers().add(new RoomMember("u-alice", "Alice", "ADMIN", "Felix"));
        groupRoom.getMembers().add(new RoomMember("u-dave", "Dave", "MEMBER", "Felix"));

        when(roomRepo.findAll()).thenReturn(List.of(roomAliceBob, roomAliceCharlie, groupRoom));
        when(userRepo.findById("u-bob")).thenReturn(Optional.of(bob));
        when(messageRepo.findByRoomId("direct_u-alice_u-bob")).thenReturn(List.of(
                new Message("m1", "direct_u-alice_u-bob", "Alice", "Hi Bob", new Date(), null)
        ));
        when(messageRepo.findByRoomId("direct_u-alice_u-charlie")).thenReturn(Collections.emptyList());

        ResponseEntity<?> response = roomController.getForwardTargets();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        @SuppressWarnings("unchecked")
        Map<String, Object> body = (Map<String, Object>) response.getBody();
        assertThat(body).isNotNull();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> contacts = (List<Map<String, Object>>) body.get("contacts");
        assertThat(contacts).hasSize(1);
        assertThat(contacts.get(0).get("name")).isEqualTo("Bob");
        assertThat(contacts.get(0).get("id")).isEqualTo("u-bob");

        SecurityContextHolder.clearContext();
    }
}
