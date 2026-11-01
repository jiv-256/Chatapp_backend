package com.substring.chat.New_Chatapp_backend.repo;

import com.substring.chat.New_Chatapp_backend.entities.JoinRequest;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface JoinRequestRepo extends MongoRepository<JoinRequest, String> {

    List<JoinRequest> findByRoomIdAndStatus(String roomId, String status);

    List<JoinRequest> findByRoomIdAndInvitationVersionAndStatus(String roomId, int invitationVersion, String status);

    Optional<JoinRequest> findFirstByRoomIdAndUserNameAndStatus(String roomId, String userName, String status);

    List<JoinRequest> findByRoomId(String roomId);

    void deleteByRoomId(String roomId);
}
