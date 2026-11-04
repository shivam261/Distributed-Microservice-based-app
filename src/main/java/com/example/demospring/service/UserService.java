package com.example.demospring.service;

import com.example.demospring.dto.PasswordChangeRequest;
import com.example.demospring.dto.RegistrationRequest;
import com.example.demospring.entity.User;
import com.example.demospring.repository.UserRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.data.mongodb.core.query.Criteria.where;

@Service
public class UserService {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final MongoOperations mongoOperations;

    public UserService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       MongoOperations mongoOperations) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.mongoOperations = mongoOperations;
    }

    public User register(RegistrationRequest request) {
        RequestValidation.requireText(request.username(), "Username");
        RequestValidation.requirePassword(request.password());
        if (userRepository.existsByUsername(request.username())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
        User user = new User(request.username(), passwordEncoder.encode(request.password()));
        user.setRoles(List.of("USER"));
        try {
            // insert never replaces an existing document; the unique username index handles races.
            return userRepository.insert(user);
        } catch (DuplicateKeyException exception) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Username already exists");
        }
    }

    public User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return userRepository.findById(principal.accountId())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    public User currentUser(String requestedId) {
        User user = currentUser();
        if (!Objects.equals(user.getId(), requestedId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return user;
    }

    public void deleteCurrentUser() {
        userRepository.deleteById(currentUser().getId());
    }

    public void changePassword(PasswordChangeRequest request, String requestedId) {
        User user = requestedId == null ? currentUser() : currentUser(requestedId);
        RequestValidation.requireText(request.currentPassword(), "Current password");
        RequestValidation.requirePassword(request.newPassword());
        if (!passwordEncoder.matches(request.currentPassword(), user.getPassword())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Current password is incorrect");
        }
        // Compare-and-set avoids overwriting a concurrent password change.
        var result = mongoOperations.updateFirst(
                Query.query(where("id").is(user.getId()).and("password").is(user.getPassword())),
                new Update().set("password", passwordEncoder.encode(request.newPassword())), User.class);
        if (result.getMatchedCount() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account changed; please retry");
        }
    }
}
