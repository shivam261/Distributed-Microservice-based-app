package com.example.demospring.service;

import com.example.demospring.dto.PasswordChangeRequest;
import com.example.demospring.dto.RegistrationRequest;
import com.example.demospring.entity.DemoEntry;
import com.example.demospring.entity.User;
import com.example.demospring.repository.DemoEntryRepository;
import com.example.demospring.repository.UserRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
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
    private final DemoEntryRepository entryRepository;
    private final PasswordEncoder passwordEncoder;
    private final MongoOperations mongoOperations;

    public UserService(UserRepository userRepository, DemoEntryRepository entryRepository,
                       PasswordEncoder passwordEncoder, MongoOperations mongoOperations) {
        this.userRepository = userRepository;
        this.entryRepository = entryRepository;
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
        return userRepository.findById(authenticatedAccountId()).filter(user -> !user.isDeleting())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED));
    }

    private String authenticatedAccountId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof AccountPrincipal principal)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        return principal.accountId();
    }

    public User currentUser(String requestedId) {
        User user = currentUser();
        if (!Objects.equals(user.getId(), requestedId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN);
        }
        return user;
    }

    public void deleteCurrentUser() {
        String id = authenticatedAccountId();
        // Mark first so a create cannot attach another entry after this snapshot.
        User user = mongoOperations.findAndModify(Query.query(where("id").is(id)),
                new Update().set("deleting", true), FindAndModifyOptions.options().returnNew(true), User.class);
        if (user == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED);
        }
        List<String> entryIds = user.getDemoEntries().stream().filter(Objects::nonNull)
                .map(DemoEntry::getId).toList();
        // Keep the marked account and its references until cleanup succeeds, so deletion can be retried.
        if (!entryIds.isEmpty()) {
            entryRepository.deleteAllById(entryIds);
        }
        userRepository.deleteById(id);
    }

    static Query activeAccount(String id) {
        return Query.query(where("id").is(id).and("deleting").ne(true));
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
                activeAccount(user.getId()).addCriteria(where("password").is(user.getPassword())),
                new Update().set("password", passwordEncoder.encode(request.newPassword())), User.class);
        if (result.getMatchedCount() == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account changed; please retry");
        }
    }
}
