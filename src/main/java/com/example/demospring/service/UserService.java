package com.example.demospring.service;

import com.example.demospring.entity.DemoEntry;
import com.example.demospring.entity.User;
import com.example.demospring.repository.UserRepository;
import org.bson.types.ObjectId;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<User> getAll() {
        return userRepository.findAll();
    }

    // Use this only when registering a new user
    public User createUser(User user) {
        user.setPassword(passwordEncoder.encode(user.getPassword()));

        return userRepository.save(user);
    }

    public void deleteUser(String username) {

        userRepository.deleteByUsername(username);
    }

    public Optional<User> getById(ObjectId id) {
        return userRepository.findById(id);
    }

    public boolean updatePassword(ObjectId id, String newPassword) {
        Optional<User> userOptional = userRepository.findById(id);

        if (userOptional.isEmpty()) {
            return false;
        }

        User user = userOptional.get();
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);

        return true;
    }

    public List<DemoEntry> listAllDemos(ObjectId id) {
        return userRepository.findById(id)
                .map(User::getDemoEntries)
                .orElse(Collections.emptyList());
    }
}