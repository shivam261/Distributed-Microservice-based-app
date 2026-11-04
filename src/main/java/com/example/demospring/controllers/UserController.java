package com.example.demospring.controllers;

import com.example.demospring.dto.PasswordChangeRequest;
import com.example.demospring.dto.RegistrationRequest;
import com.example.demospring.dto.UserResponse;
import com.example.demospring.service.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user")
public class UserController {
    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @GetMapping
    public UserResponse currentUser() {
        return UserResponse.from(userService.currentUser());
    }

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@RequestBody RegistrationRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(UserResponse.from(userService.register(request)));
    }

    @DeleteMapping("/id")
    public ResponseEntity<Void> deleteUser() {
        userService.deleteCurrentUser();
        return ResponseEntity.noContent().build();
    }

    @PutMapping({"/password", "/id/{id}"})
    public ResponseEntity<Void> updatePassword(@PathVariable(required = false) String id,
                                              @RequestBody PasswordChangeRequest request) {
        userService.changePassword(request, id);
        return ResponseEntity.accepted().build();
    }
}
