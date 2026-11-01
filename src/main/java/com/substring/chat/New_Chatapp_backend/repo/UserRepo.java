package com.substring.chat.New_Chatapp_backend.repo;

import com.substring.chat.New_Chatapp_backend.entities.User;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface UserRepo extends MongoRepository<User, String> {
    Optional<User> findByEmail(String email);
    Optional<User> findByGoogleId(String googleId);
    boolean existsByEmail(String email);
    java.util.List<User> findByName(String name);
    java.util.List<User> findByNameIgnoreCase(String name);
}
