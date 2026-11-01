package com.substring.chat.New_Chatapp_backend.repo;


import com.substring.chat.New_Chatapp_backend.entities.Message;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Date;
import java.util.List;

@Repository
public interface MessageRepo extends MongoRepository<Message, String> {

    List<Message> findByRoomId(String roomId);

    List<Message> findByRoomIdOrderByTimeStampAsc(String roomId);

    void deleteByRoomId(String roomId);

    long deleteByExpiresAtBefore(Date cutoff);

    List<Message> findByExpiresAtBefore(Date cutoff);
}