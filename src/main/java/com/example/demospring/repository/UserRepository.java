package com.example.demospring.repository;

import com.example.demospring.entity.User;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {
    Optional<User> findByUsername(String username);

    long deleteByUsername(String username);
}
