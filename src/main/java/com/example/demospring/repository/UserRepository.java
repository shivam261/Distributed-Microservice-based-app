package com.example.demospring.repository;

import com.example.demospring.entity.User;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;
import org.springframework.data.mongodb.repository.Query;

public interface UserRepository extends MongoRepository<User, String> {
    @Query(value = "{ 'username': ?0 }", fields = "{ '_id': 1, 'username': 1, 'password': 1, 'roles': 1 }")
    Optional<User> findCredentialsByUsername(String username);

    boolean existsByUsername(String username);
}
