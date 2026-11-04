package com.example.demospring.service;

import com.example.demospring.dto.CreateEntryRequest;
import com.example.demospring.entity.DemoEntry;
import com.example.demospring.entity.User;
import com.example.demospring.repository.DemoEntryRepository;
import java.util.List;
import java.util.Objects;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import static org.springframework.data.mongodb.core.query.Criteria.where;

@Service
public class DemoEntryService {
    private final DemoEntryRepository demoEntryRepository;
    private final UserService userService;
    private final MongoOperations mongoOperations;

    public DemoEntryService(DemoEntryRepository demoEntryRepository, UserService userService,
                            MongoOperations mongoOperations) {
        this.demoEntryRepository = demoEntryRepository;
        this.userService = userService;
        this.mongoOperations = mongoOperations;
    }

    public DemoEntry create(CreateEntryRequest request, String requestedUserId) {
        User user = owner(requestedUserId);
        RequestValidation.requireText(request.title(), "Title");
        DemoEntry entry = new DemoEntry();
        entry.setTitle(request.title());
        entry.setContent(request.content());
        DemoEntry saved = demoEntryRepository.insert(entry);
        // Keep the existing DBRef ownership model. Only update references, never credentials.
        var result = mongoOperations.updateFirst(Query.query(where("id").is(user.getId())),
                new Update().push("demoEntries", saved), User.class);
        if (result.getMatchedCount() == 0) {
            demoEntryRepository.deleteById(saved.getId());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account no longer exists");
        }
        return saved;
    }

    public List<DemoEntry> getAll(String requestedUserId) {
        return entries(owner(requestedUserId));
    }

    public DemoEntry getById(String id) {
        return ownedEntry(userService.currentUser(), id);
    }

    public void deleteEntry(String id) {
        User user = userService.currentUser();
        DemoEntry entry = ownedEntry(user, id);
        mongoOperations.updateFirst(Query.query(where("id").is(user.getId())),
                new Update().pull("demoEntries", entry), User.class);
        demoEntryRepository.deleteById(id);
    }

    private User owner(String requestedUserId) {
        return requestedUserId == null ? userService.currentUser() : userService.currentUser(requestedUserId);
    }

    private List<DemoEntry> entries(User user) {
        return user.getDemoEntries().stream().filter(Objects::nonNull).toList();
    }

    private DemoEntry ownedEntry(User user, String id) {
        // Entry IDs alone never grant access; resolve only within the principal's references.
        return entries(user).stream().filter(entry -> Objects.equals(entry.getId(), id))
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
}
