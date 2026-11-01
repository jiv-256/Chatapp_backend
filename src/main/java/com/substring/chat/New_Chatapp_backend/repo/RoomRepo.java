package com.substring.chat.New_Chatapp_backend.repo;


import com.substring.chat.New_Chatapp_backend.entities.Room;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

import java.util.Date;
import java.util.List;

public interface RoomRepo extends MongoRepository<Room, String> {
    Room findByRoomId(String roomId);

    Room findByInviteTokenHash(String inviteTokenHash);

    List<Room> findByLastActivityAtBefore(Date cutoff);

    void deleteByRoomId(String roomId);

    List<Room> findByAdminId(String adminId);

    List<Room> findByAdminIgnoreCase(String admin);

    @Query("{ '$or': [ { 'adminId': ?0 }, { 'admin': ?1 } ] }")
    List<Room> findRoomsCreated(String adminId, String admin);

    @Query("{ '$or': [ { 'members.userId': ?0 }, { 'members.username': ?1 } ] }")
    List<Room> findRoomsJoined(String userId, String username);

    @Query("{ '$or': [ { 'adminId': ?0 }, { 'admin': ?1 }, { 'admin': ?2 } ] }")
    List<Room> findRoomsCreatedByUser(String adminId, String admin, String email);

    @Query("{ '$or': [ { 'members.userId': ?0 }, { 'members.username': ?1 }, { 'members.username': ?2 } ] }")
    List<Room> findRoomsJoinedByUser(String userId, String username, String email);
}

