package com.example.demospring.service;

import com.example.demospring.dto.CreateEntryRequest;
import com.example.demospring.dto.UpdateEntryRequest;
import com.example.demospring.entity.DemoEntry;
import com.example.demospring.entity.User;
import com.example.demospring.repository.DemoEntryRepository;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.stream.Stream;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
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
        if (request.tags() != null) {
            entry.setTags(normalizeTags(request.tags()));
        }
        entry.setUpdatedAt(Instant.now());
        DemoEntry saved = demoEntryRepository.insert(entry);
        // Only attach the new reference while the owning account is active.
        var result = mongoOperations.updateFirst(UserService.activeAccount(user.getId()),
                new Update().push("demoEntries", saved), User.class);
        if (result.getMatchedCount() == 0) {
            demoEntryRepository.deleteById(saved.getId());
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Account is being deleted or no longer exists");
        }
        return saved;
    }

    public List<DemoEntry> getAll(String requestedUserId, String query, String tag, String sort) {
        Stream<DemoEntry> active = entries(owner(requestedUserId)).filter(entry -> entry.getDeletedAt() == null);
        if (query != null && !query.isBlank()) {
            String term = normalize(query);
            active = active.filter(entry -> normalize(entry.getTitle()).contains(term)
                    || (entry.getContent() != null && normalize(entry.getContent()).contains(term)));
        }
        if (tag != null && !tag.isBlank()) {
            String filterTag = normalize(tag);
            active = active.filter(entry -> entry.getTags().contains(filterTag));
        }
        if ("updated".equals(sort)) {
            active = active.sorted(Comparator.comparing(DemoEntry::getUpdatedAt,
                    Comparator.nullsLast(Comparator.reverseOrder())).thenComparing(DemoEntry::getId));
        } else if (sort != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported sort");
        }
        return active.toList();
    }

    public List<DemoEntry> getTrash() {
        return entries(userService.currentUser()).filter(entry -> entry.getDeletedAt() != null)
                .sorted(Comparator.comparing(DemoEntry::getDeletedAt).reversed().thenComparing(DemoEntry::getId))
                .toList();
    }

    public DemoEntry getById(String id) {
        return ownedEntry(userService.currentUser(), id, false);
    }

    public DemoEntry edit(String id, UpdateEntryRequest request) {
        ownedEntry(userService.currentUser(), id, false);
        Update update = new Update();
        if (request.title() != null) {
            RequestValidation.requireText(request.title(), "Title");
            update.set("title", request.title());
        }
        if (request.content() != null) {
            update.set("content", request.content());
        }
        if (request.tags() != null) {
            update.set("tags", normalizeTags(request.tags()));
        }
        if (update.getUpdateObject().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide a title, content, or tags");
        }
        return updateEntry(entryState(id, false), update.currentDate("updatedAt"));
    }

    public void deleteEntry(String id) {
        ownedEntry(userService.currentUser(), id, false);
        updateEntry(entryState(id, false), new Update().currentDate("deletedAt"));
    }

    public DemoEntry restore(String id) {
        ownedEntry(userService.currentUser(), id, true);
        return updateEntry(entryState(id, true), new Update().unset("deletedAt").currentDate("updatedAt"));
    }

    public void purge(String id) {
        User user = userService.currentUser();
        DemoEntry entry = ownedEntry(user, id, true);
        // Delete the document first so a failed reference cleanup cannot leave private content behind.
        if (mongoOperations.remove(entryState(id, true), DemoEntry.class).getDeletedCount() == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        mongoOperations.updateFirst(Query.query(where("id").is(user.getId())),
                new Update().pull("demoEntries", entry), User.class);
    }

    private DemoEntry updateEntry(Query query, Update update) {
        DemoEntry entry = mongoOperations.findAndModify(query, update,
                FindAndModifyOptions.options().returnNew(true), DemoEntry.class);
        if (entry == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND);
        }
        return entry;
    }

    private static Query entryState(String id, boolean trashed) {
        var state = where("id").is(id).and("deletedAt");
        return Query.query(trashed ? state.ne(null) : state.isNull());
    }

    private User owner(String requestedUserId) {
        return requestedUserId == null ? userService.currentUser() : userService.currentUser(requestedUserId);
    }

    private static Stream<DemoEntry> entries(User user) {
        return user.getDemoEntries().stream().filter(Objects::nonNull);
    }

    private static DemoEntry ownedEntry(User user, String id, boolean trashed) {
        return entries(user).filter(entry -> id.equals(entry.getId()) && (entry.getDeletedAt() != null) == trashed)
                .findFirst().orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }

    private static List<String> normalizeTags(List<String> tags) {
        if (tags.size() > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Provide at most 20 tags");
        }
        for (String tag : tags) {
            RequestValidation.requireText(tag, "Tag");
            if (tag.strip().length() > 40) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Tags must be at most 40 characters");
            }
        }
        return tags.stream().map(DemoEntryService::normalize).distinct().toList();
    }

    private static String normalize(String value) {
        return value.strip().toLowerCase(Locale.ROOT);
    }
}
