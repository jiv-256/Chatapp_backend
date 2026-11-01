package com.substring.chat.New_Chatapp_backend.services;

import com.substring.chat.New_Chatapp_backend.config.RetentionConstants;
import com.substring.chat.New_Chatapp_backend.entities.Message;
import com.substring.chat.New_Chatapp_backend.entities.Room;
import com.substring.chat.New_Chatapp_backend.repo.MessageRepo;
import com.substring.chat.New_Chatapp_backend.repo.RoomRepo;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MessageExpirationServiceTest {

    @Mock
    private MessageRepo messageRepo;

    @Mock
    private RoomRepo roomRepo;

    private MessageService messageService;

    @BeforeEach
    void setUp() {
        messageService = new MessageService(messageRepo, roomRepo);
    }

    @Test
    void testMessageCreatedWithDefaultRetentionHas7DayExpiration() {
        Room room = new Room();
        room.setRoomId("room-1");
        room.setCustomDeletionTimeEnabled(false);
        room.setCustomDeletionAfterSeconds(RetentionConstants.DEFAULT_RETENTION_SECONDS);
        when(roomRepo.findByRoomId("room-1")).thenReturn(room);
        when(messageRepo.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Date before = new Date();
        Message saved = messageService.createMessage("room-1", "Alice", "Hello World");
        Date after = new Date();

        assertThat(saved.getContent()).isEqualTo("Hello World");
        assertThat(saved.getSender()).isEqualTo("Alice");
        assertThat(saved.getTimeStamp().getTime()).isGreaterThanOrEqualTo(before.getTime()).isLessThanOrEqualTo(after.getTime());

        // Expected expiresAt = timeStamp + 604800s
        long expectedMillis = saved.getTimeStamp().getTime() + (604800L * 1000L);
        assertThat(saved.getExpiresAt().getTime()).isEqualTo(expectedMillis);

        // Verifies room lastActivityAt is updated
        assertThat(room.getLastActivityAt().getTime()).isGreaterThanOrEqualTo(before.getTime()).isLessThanOrEqualTo(after.getTime());
        verify(roomRepo).save(room);
    }

    @Test
    void testMessageCreatedWithCustomRetentionHasCustomExpiration() {
        Room room = new Room();
        room.setRoomId("room-2");
        room.setCustomDeletionTimeEnabled(true);
        room.setCustomDeletionAfterSeconds(300L); // 5 minutes
        when(roomRepo.findByRoomId("room-2")).thenReturn(room);
        when(messageRepo.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Message saved = messageService.createMessage("room-2", "Bob", "Testing 5m expiration");

        long expectedMillis = saved.getTimeStamp().getTime() + (300L * 1000L);
        assertThat(saved.getExpiresAt().getTime()).isEqualTo(expectedMillis);
    }

    @Test
    void testRecalculatingExpirationPreservesOriginalTimestamps() {
        Date originalTime1 = new Date(System.currentTimeMillis() - 100000);
        Date originalTime2 = new Date(System.currentTimeMillis() - 50000);

        Message msg1 = new Message("1", "room-1", "Alice", "Message 1", originalTime1, new Date());
        Message msg2 = new Message("2", "room-1", "Bob", "Message 2", originalTime2, new Date());

        List<Message> messages = new ArrayList<>(List.of(msg1, msg2));
        when(messageRepo.findByRoomId("room-1")).thenReturn(messages);

        // Admin changes retention to 60 seconds (1 minute)
        int updatedCount = messageService.recalculateExpirationForRoom("room-1", 60L);

        assertThat(updatedCount).isEqualTo(2);

        // Original creation timestamps must NOT be modified
        assertThat(msg1.getTimeStamp()).isEqualTo(originalTime1);
        assertThat(msg2.getTimeStamp()).isEqualTo(originalTime2);

        // New expiresAt must be exactly original + 60s
        assertThat(msg1.getExpiresAt().getTime()).isEqualTo(originalTime1.getTime() + 60_000L);
        assertThat(msg2.getExpiresAt().getTime()).isEqualTo(originalTime2.getTime() + 60_000L);

        verify(messageRepo).saveAll(messages);
    }

    @Test
    void testDisablingCustomRetentionRecalculatesMessagesBackToSevenDays() {
        Date originalTime = new Date(System.currentTimeMillis() - 200000);
        Message msg = new Message("1", "room-1", "Alice", "Message", originalTime, new Date());

        when(messageRepo.findByRoomId("room-1")).thenReturn(List.of(msg));

        messageService.recalculateExpirationForRoom("room-1", RetentionConstants.DEFAULT_RETENTION_SECONDS);

        assertThat(msg.getTimeStamp()).isEqualTo(originalTime);
        assertThat(msg.getExpiresAt().getTime()).isEqualTo(originalTime.getTime() + (604800L * 1000L));
    }

    @Test
    void testGetValidMessagesFiltersOutPastExpiredMessages() {
        Date now = new Date();
        Date pastExpired = new Date(now.getTime() - 10_000L); // Expired 10 seconds ago
        Date futureActive = new Date(now.getTime() + 60_000L); // Expires in 60 seconds

        Message expiredMsg = new Message("1", "room-1", "Alice", "Old", new Date(now.getTime() - 100000), pastExpired);
        Message activeMsg = new Message("2", "room-1", "Bob", "Fresh", new Date(now.getTime() - 1000), futureActive);

        when(messageRepo.findByRoomIdOrderByTimeStampAsc("room-1")).thenReturn(List.of(expiredMsg, activeMsg));

        List<Message> valid = messageService.getValidMessagesForRoom("room-1");

        assertThat(valid).hasSize(1);
        assertThat(valid.get(0).getId()).isEqualTo("2");
    }

    @Test
    void testCreateMessageWithReplyToExistingMessageAssociatesOriginalContent() {
        Room room = new Room();
        room.setRoomId("room-1");
        when(roomRepo.findByRoomId("room-1")).thenReturn(room);
        when(messageRepo.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Message originalMsg = new Message("msg-1", "room-1", "Sajal", "Bhai, kal milte hain!", new Date(), new Date());
        when(messageRepo.findById("msg-1")).thenReturn(java.util.Optional.of(originalMsg));

        Message reply = messageService.createMessage(
                "room-1", "Rahul", "Haan bhai, milte hain.", "temp-123", "avatar.png",
                "msg-1", "Sajal", "Bhai, kal milte hain!"
        );

        assertThat(reply.getReplyToMessageId()).isEqualTo("msg-1");
        assertThat(reply.getReplyToSender()).isEqualTo("Sajal");
        assertThat(reply.getReplyToText()).isEqualTo("Bhai, kal milte hain!");
    }

    @Test
    void testCreateMessageWithReplyToOtherRoomMessageDoesNotAssociate() {
        Room room = new Room();
        room.setRoomId("room-1");
        when(roomRepo.findByRoomId("room-1")).thenReturn(room);
        when(messageRepo.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Message otherRoomMsg = new Message("msg-other", "other-room", "Hacker", "Secret", new Date(), new Date());
        when(messageRepo.findById("msg-other")).thenReturn(java.util.Optional.of(otherRoomMsg));

        Message reply = messageService.createMessage(
                "room-1", "Rahul", "Test reply", "temp-456", "avatar.png",
                "msg-other", "Hacker", "Secret"
        );

        // Security check: must not associate message from a different room
        assertThat(reply.getReplyToMessageId()).isNull();
        assertThat(reply.getReplyToSender()).isNull();
        assertThat(reply.getReplyToText()).isNull();
    }

    @Test
    void testCreateMessageWithReplyToDeletedMessageUsesSafeFallback() {
        Room room = new Room();
        room.setRoomId("room-1");
        when(roomRepo.findByRoomId("room-1")).thenReturn(room);
        when(messageRepo.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        when(messageRepo.findById("msg-deleted")).thenReturn(java.util.Optional.empty());

        Message reply = messageService.createMessage(
                "room-1", "Rahul", "Replying to deleted msg", "temp-789", "avatar.png",
                "msg-deleted", "Sajal", "Original message"
        );

        assertThat(reply.getReplyToMessageId()).isEqualTo("msg-deleted");
        assertThat(reply.getReplyToSender()).isEqualTo("Sajal");
        assertThat(reply.getReplyToText()).isEqualTo("Original message");
    }

    @Test
    void testDirectChatMessageCreatedWithStrict24HourExpiration() {
        Room directRoom = new Room();
        directRoom.setRoomId("direct_alice_bob");
        directRoom.setRoomType("DIRECT");
        when(roomRepo.findByRoomId("direct_alice_bob")).thenReturn(directRoom);
        when(messageRepo.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Date before = new Date();
        Message saved = messageService.createMessage("direct_alice_bob", "Alice", "Private 24-hour message");
        Date after = new Date();

        assertThat(saved.getContent()).isEqualTo("Private 24-hour message");
        assertThat(saved.getSender()).isEqualTo("Alice");
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getCreatedAt().getTime()).isGreaterThanOrEqualTo(before.getTime()).isLessThanOrEqualTo(after.getTime());
        assertThat(saved.getTimeStamp()).isEqualTo(saved.getCreatedAt());

        // Strict 24-hour auto-delete expiration (86400 seconds)
        long expectedExpiresAt = saved.getCreatedAt().getTime() + (RetentionConstants.DIRECT_CHAT_RETENTION_SECONDS * 1000L);
        assertThat(saved.getExpiresAt().getTime()).isEqualTo(expectedExpiresAt);
        assertThat(saved.isExpired(new Date(expectedExpiresAt - 1000L))).isFalse();
        assertThat(saved.isExpired(new Date(expectedExpiresAt + 1000L))).isTrue();

        verify(roomRepo).save(directRoom);
    }

    @Test
    void testDirectChatMessageWithPrefixHasStrict24HourExpiration() {
        Room directRoom = new Room();
        directRoom.setRoomId("direct_user1_user2");
        // roomType is null or default, but prefix is direct_
        when(roomRepo.findByRoomId("direct_user1_user2")).thenReturn(directRoom);
        when(messageRepo.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Message saved = messageService.createMessage("direct_user1_user2", "User1", "Prefix direct message");

        long expectedExpiresAt = saved.getCreatedAt().getTime() + (86400L * 1000L);
        assertThat(saved.getExpiresAt().getTime()).isEqualTo(expectedExpiresAt);
    }

    @Test
    void testDirectChatIgnoresCustomRetentionSettingsAndEnforces24Hours() {
        Room directRoom = new Room();
        directRoom.setRoomId("direct_alice_bob");
        directRoom.setRoomType("DIRECT");
        // Even if somehow marked with custom deletion settings
        directRoom.setCustomDeletionTimeEnabled(true);
        directRoom.setCustomDeletionAfterSeconds(300L); // 5 min
        when(roomRepo.findByRoomId("direct_alice_bob")).thenReturn(directRoom);
        when(messageRepo.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Message saved = messageService.createMessage("direct_alice_bob", "Alice", "Must still be 24 hours");

        // Must still be 24 hours (86400s)
        long expectedExpiresAt = saved.getCreatedAt().getTime() + (86400L * 1000L);
        assertThat(saved.getExpiresAt().getTime()).isEqualTo(expectedExpiresAt);
    }

    @Test
    void testGroupChatMessagePreservesSevenDayDefaultAndImmuneToDirect24HourRule() {
        Room groupRoom = new Room();
        groupRoom.setRoomId("engineering-team");
        groupRoom.setRoomType("GROUP");
        when(roomRepo.findByRoomId("engineering-team")).thenReturn(groupRoom);
        when(messageRepo.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Message saved = messageService.createMessage("engineering-team", "Bob", "Group message should last 7 days");

        long expectedExpiresAt = saved.getCreatedAt().getTime() + (RetentionConstants.DEFAULT_RETENTION_SECONDS * 1000L);
        assertThat(saved.getExpiresAt().getTime()).isEqualTo(expectedExpiresAt);
        assertThat(saved.getExpiresAt().getTime() - saved.getCreatedAt().getTime()).isEqualTo(604800L * 1000L);
    }

    @Test
    void testForwardedMessageToDirectRoomReceivesFreshTimestampAndIndependent24HourExpiration() {
        Date originalDate = new Date(System.currentTimeMillis() - (12L * 3600 * 1000L)); // 12 hours ago
        Date originalExpiry = new Date(originalDate.getTime() + (86400L * 1000L)); // Had 12h left
        Message original = new Message("msg-orig", "direct_userA_userB", "Alice", "Confidential text", originalDate, originalExpiry);

        Room targetDirectRoom = new Room();
        targetDirectRoom.setRoomId("direct_alice_charlie");
        targetDirectRoom.setRoomType("DIRECT");

        when(messageRepo.findById("msg-orig")).thenReturn(java.util.Optional.of(original));
        when(roomRepo.findByRoomId("direct_alice_charlie")).thenReturn(targetDirectRoom);
        when(messageRepo.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Date beforeForward = new Date();
        List<Message> forwarded = messageService.forwardMessage("msg-orig", List.of("direct_alice_charlie"), "Alice", "avatar.png");
        Date afterForward = new Date();

        assertThat(forwarded).hasSize(1);
        Message fwd = forwarded.get(0);

        assertThat(fwd.getContent()).isEqualTo("Confidential text");
        assertThat(fwd.isForwarded()).isTrue();
        assertThat(fwd.getForwardedFromSender()).isEqualTo("Alice");

        // Forwarded message gets independent fresh timestamps
        assertThat(fwd.getCreatedAt().getTime()).isGreaterThanOrEqualTo(beforeForward.getTime()).isLessThanOrEqualTo(afterForward.getTime());
        long expectedForwardExpiry = fwd.getCreatedAt().getTime() + (RetentionConstants.DIRECT_CHAT_RETENTION_SECONDS * 1000L);
        assertThat(fwd.getExpiresAt().getTime()).isEqualTo(expectedForwardExpiry);

        // Original message timestamp and expiry were untouched
        assertThat(original.getCreatedAt()).isEqualTo(originalDate);
        assertThat(original.getExpiresAt()).isEqualTo(originalExpiry);
    }

    @Test
    void testForwardedMessageToGroupRoomReceivesFreshGroupRetention() {
        Message original = new Message("msg-orig2", "direct_userA_userB", "Alice", "Shared note", new Date(), new Date());

        Room targetGroupRoom = new Room();
        targetGroupRoom.setRoomId("project-alpha");
        targetGroupRoom.setRoomType("GROUP");

        when(messageRepo.findById("msg-orig2")).thenReturn(java.util.Optional.of(original));
        when(roomRepo.findByRoomId("project-alpha")).thenReturn(targetGroupRoom);
        when(messageRepo.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        List<Message> forwarded = messageService.forwardMessage("msg-orig2", List.of("project-alpha"), "Alice", "avatar.png");

        assertThat(forwarded).hasSize(1);
        Message fwd = forwarded.get(0);
        long expectedGroupExpiry = fwd.getCreatedAt().getTime() + (RetentionConstants.DEFAULT_RETENTION_SECONDS * 1000L);
        assertThat(fwd.getExpiresAt().getTime()).isEqualTo(expectedGroupExpiry);
    }

    @Test
    void testReplyPreviewSanitizedToOriginalMessageExpiredWhenReferencedMessageExpiresInGetValidMessages() {
        Date now = new Date();
        Date pastExpired = new Date(now.getTime() - 1000L); // Expired 1 second ago
        Date futureActive = new Date(now.getTime() + 80000L * 1000L);

        Message expiredMsg = new Message("msg-secret", "direct_alice_bob", "Alice", "Super secret credit card number", new Date(now.getTime() - 90000L * 1000L), pastExpired);
        Message replyingMsg = new Message("msg-reply", "direct_alice_bob", "Bob", "Got it, thanks!", new Date(now.getTime() - 5000L), futureActive);
        replyingMsg.setReplyToMessageId("msg-secret");
        replyingMsg.setReplyToSender("Alice");
        replyingMsg.setReplyToText("Super secret credit card number");

        when(messageRepo.findByRoomIdOrderByTimeStampAsc("direct_alice_bob")).thenReturn(List.of(expiredMsg, replyingMsg));

        List<Message> valid = messageService.getValidMessagesForRoom("direct_alice_bob");

        // Expired message itself is completely filtered out
        assertThat(valid).hasSize(1);
        Message remaining = valid.get(0);
        assertThat(remaining.getId()).isEqualTo("msg-reply");

        // Sensitive referenced content in reply preview is sanitized so it cannot be recovered
        assertThat(remaining.getReplyToText()).isEqualTo("Original message expired");
    }

    @Test
    void testCreateMessageReplyingToAlreadyExpiredMessageSanitizesPreviewAtCreation() {
        Room directRoom = new Room();
        directRoom.setRoomId("direct_alice_bob");
        directRoom.setRoomType("DIRECT");
        when(roomRepo.findByRoomId("direct_alice_bob")).thenReturn(directRoom);
        when(messageRepo.save(any(Message.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Date now = new Date();
        Date pastExpired = new Date(now.getTime() - 10000L);
        Message expiredMsg = new Message("msg-old", "direct_alice_bob", "Alice", "Old expired secret", new Date(now.getTime() - 90000L * 1000L), pastExpired);
        when(messageRepo.findById("msg-old")).thenReturn(java.util.Optional.of(expiredMsg));

        Message reply = messageService.createMessage(
                "direct_alice_bob", "Bob", "Replying to expired msg", "temp-999", "avatar.png",
                "msg-old", "Alice", "Old expired secret"
        );

        assertThat(reply.getReplyToMessageId()).isEqualTo("msg-old");
        assertThat(reply.getReplyToText()).isEqualTo("Original message expired");
    }

    @Test
    void testRecalculateExpirationForDirectRoomStrictlyEnforces24Hours() {
        Date originalTime = new Date(System.currentTimeMillis() - 50000);
        Message directMsg = new Message("direct-msg-1", "direct_alice_bob", "Alice", "Direct msg", originalTime, new Date());

        Room directRoom = new Room();
        directRoom.setRoomId("direct_alice_bob");
        directRoom.setRoomType("DIRECT");

        when(roomRepo.findByRoomId("direct_alice_bob")).thenReturn(directRoom);
        when(messageRepo.findByRoomId("direct_alice_bob")).thenReturn(List.of(directMsg));

        // Attempting to pass 60 seconds to recalculate
        int updatedCount = messageService.recalculateExpirationForRoom("direct_alice_bob", 60L);

        assertThat(updatedCount).isEqualTo(1);
        // Direct room must enforce 86400s (24 hours), not 60s
        assertThat(directMsg.getExpiresAt().getTime()).isEqualTo(originalTime.getTime() + (86400L * 1000L));
    }
}
