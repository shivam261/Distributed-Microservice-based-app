package com.example.demospring;

import com.example.demospring.config.SpringSecurity;
import com.example.demospring.controllers.DemoEntryController;
import com.example.demospring.controllers.HealthCheck;
import com.example.demospring.controllers.UserController;
import com.example.demospring.dto.CreateEntryRequest;
import com.example.demospring.dto.PasswordChangeRequest;
import com.example.demospring.entity.DemoEntry;
import com.example.demospring.entity.User;
import com.example.demospring.repository.DemoEntryRepository;
import com.example.demospring.repository.UserRepository;
import com.example.demospring.service.DemoEntryService;
import com.example.demospring.service.UserDetailServiceImpl;
import com.example.demospring.service.UserService;
import com.mongodb.client.result.UpdateResult;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.bson.Document;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.MongoOperations;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {UserController.class, DemoEntryController.class, HealthCheck.class})
@Import({SpringSecurity.class, UserService.class, DemoEntryService.class, UserDetailServiceImpl.class})
class DemospringApplicationTests {
    private static final String ALICE_ID = "507f1f77bcf86cd799439011";
    private static final String BOB_ID = "507f1f77bcf86cd799439012";
    private static final String ALICE_ENTRY = "507f1f77bcf86cd799439021";
    private static final String BOB_ENTRY = "507f1f77bcf86cd799439022";
    private static final String PASSWORD = "original-password";

    @Autowired private MockMvc mvc;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private UserService userService;
    @Autowired private DemoEntryService entryService;
    @MockitoBean private UserRepository users;
    @MockitoBean private DemoEntryRepository entries;
    @MockitoBean private MongoOperations mongo;

    // Detached copies emulate repository reads; tests must inspect persisted state after requests.
    private final Map<String, User> storedUsers = new HashMap<>();
    private final Map<String, DemoEntry> storedEntries = new HashMap<>();

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        storedUsers.clear();
        storedEntries.clear();
        seed("alice", ALICE_ID, ALICE_ENTRY);
        seed("bob", BOB_ID, BOB_ENTRY);
        when(users.existsByUsername(anyString())).thenAnswer(call -> storedUsers.containsKey(call.getArgument(0)));
        when(users.findById(anyString())).thenAnswer(call -> storedUsers.values().stream()
                .filter(user -> user.getId().equals(call.getArgument(0))).findFirst().map(this::copy));
        when(users.findCredentialsByUsername(anyString())).thenAnswer(call ->
                Optional.ofNullable(storedUsers.get(call.getArgument(0))).map(this::copy));
        when(users.insert(any(User.class))).thenAnswer(call -> {
            User user = copy(call.getArgument(0));
            if (storedUsers.containsKey(user.getUsername())) {
                throw new DuplicateKeyException("duplicate username");
            }
            assertThat(user.getId()).isNull();
            user.setId(new ObjectId().toHexString());
            storedUsers.put(user.getUsername(), copy(user));
            return user;
        });
        when(entries.insert(any(DemoEntry.class))).thenAnswer(call -> {
            DemoEntry entry = copyEntry(call.getArgument(0));
            assertThat(entry.getId()).isNull();
            entry.setId(new ObjectId().toHexString());
            storedEntries.put(entry.getId(), copyEntry(entry));
            return entry;
        });
        doAnswer(call -> {
            storedEntries.remove(call.getArgument(0));
            return null;
        }).when(entries).deleteById(anyString());
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(User.class))).thenAnswer(call -> {
            Document query = ((Query) call.getArgument(0)).getQueryObject();
            User target = storedUsers.values().stream()
                    .filter(user -> user.getId().equals(query.getString("id")))
                    .filter(user -> !query.containsKey("password")
                            || user.getPassword().equals(query.getString("password")))
                    .findFirst().orElse(null);
            if (target == null) {
                return UpdateResult.acknowledged(0, 0L, null);
            }
            Document update = ((Update) call.getArgument(1)).getUpdateObject();
            if (update.containsKey("$set")) {
                target.setPassword(update.get("$set", Document.class).getString("password"));
            }
            if (update.containsKey("$push")) {
                DemoEntry entry = (DemoEntry) update.get("$push", Document.class).get("demoEntries");
                target.getDemoEntries().add(copyEntry(entry));
            }
            if (update.containsKey("$pull")) {
                DemoEntry entry = (DemoEntry) update.get("$pull", Document.class).get("demoEntries");
                target.getDemoEntries().removeIf(value -> value.getId().equals(entry.getId()));
            }
            return UpdateResult.acknowledged(1, 1L, null);
        });
    }

    @Test
    void healthCheckAndRegistrationArePublic() throws Exception {
        mvc.perform(get("/health-check")).andExpect(status().isOk());
        mvc.perform(post("/user").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol\",\"password\":\"carol-password\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.username").value("carol"))
                .andExpect(jsonPath("$.roles[0]").value("USER"))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.demoEntries").doesNotExist());
        assertThat(passwordEncoder.matches("carol-password", storedUsers.get("carol").getPassword())).isTrue();
        mvc.perform(get("/user").header("Authorization", basic("carol", "carol-password")))
                .andExpect(status().isOk());
        verify(users, never()).save(any(User.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"\"id\":\"507f1f77bcf86cd799439012\"", "\"roles\":[\"ADMIN\"]", "\"demoEntries\":[]"})
    void registrationRejectsPersistenceFields(String injectedField) throws Exception {
        String before = storedUsers.get("bob").getPassword();
        mvc.perform(post("/user").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"mallory\",\"password\":\"new-password\"," + injectedField + "}"))
                .andExpect(status().isBadRequest());
        assertThat(storedUsers).doesNotContainKey("mallory");
        assertThat(storedUsers.get("bob").getPassword()).isEqualTo(before);
        assertThat(storedUsers.get("bob").getRoles()).containsExactly("USER");
        verify(users, never()).insert(any(User.class));
    }

    @Test
    void duplicateRegistrationDoesNotMutateAccount() throws Exception {
        String before = storedUsers.get("bob").getPassword();
        mvc.perform(post("/user").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"bob\",\"password\":\"attacker-password\"}"))
                .andExpect(status().isConflict());
        assertThat(storedUsers.get("bob").getPassword()).isEqualTo(before);
        verify(users, never()).insert(any(User.class));
    }

    @Test
    void concurrentDuplicateRegistrationReturnsConflict() throws Exception {
        when(users.insert(any(User.class))).thenThrow(new DuplicateKeyException("username race"));
        mvc.perform(post("/user").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol\",\"password\":\"carol-password\"}"))
                .andExpect(status().isConflict());
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"username\":\"carol\"}", "{\"username\":\" \",\"password\":\"password\"}",
            "{\"username\":\"carol\",\"password\":\" \"}"})
    void invalidRegistrationReturnsBadRequest(String body) throws Exception {
        mvc.perform(post("/user").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
        verify(users, never()).insert(any(User.class));
    }

    @Test
    void passwordsBeyondBcryptByteLimitAreRejected() throws Exception {
        mvc.perform(post("/user").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"carol\",\"password\":\"" + "é".repeat(37) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void allPrivateRoutesRequireAuthentication() throws Exception {
        mvc.perform(get("/user")).andExpect(status().isUnauthorized());
        mvc.perform(get("/demo")).andExpect(status().isUnauthorized());
        mvc.perform(get("/demo/id/" + BOB_ENTRY)).andExpect(status().isUnauthorized());
        mvc.perform(post("/demo").contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/demo/id/" + BOB_ENTRY)).andExpect(status().isUnauthorized());
        mvc.perform(put("/user/password").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
        mvc.perform(delete("/user/id")).andExpect(status().isUnauthorized());
    }

    @Test
    void userReadReturnsOnlyCallerAndNoPasswordOrEntries() throws Exception {
        mvc.perform(get("/user").header("Authorization", alice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("alice"))
                .andExpect(jsonPath("$.id").value(ALICE_ID))
                .andExpect(jsonPath("$.password").doesNotExist())
                .andExpect(jsonPath("$.demoEntries").doesNotExist())
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("bob"))));
        verify(users, never()).findAll();
    }

    @Test
    void inFlightRequestCannotFollowAReusedUsername() throws Exception {
        User deleted = copy(storedUsers.get("alice"));
        seed("alice", new ObjectId().toHexString(), new ObjectId().toHexString());
        when(users.findCredentialsByUsername("alice")).thenReturn(Optional.of(deleted));
        mvc.perform(post("/demo").header("Authorization", alice())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"stale request\"}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(entries, mongo);
    }

    @Test
    void callerCannotResetAnotherUsersPasswordEvenKnowingIt() throws Exception {
        String before = storedUsers.get("bob").getPassword();
        mvc.perform(put("/user/id/" + BOB_ID).header("Authorization", alice())
                        .contentType(MediaType.APPLICATION_JSON).content(passwordChange(PASSWORD)))
                .andExpect(status().isForbidden());
        assertThat(storedUsers.get("bob").getPassword()).isEqualTo(before);
        verifyNoInteractions(mongo);
    }

    @Test
    void passwordChangeRequiresCorrectCurrentPassword() throws Exception {
        String before = storedUsers.get("alice").getPassword();
        mvc.perform(put("/user/password").header("Authorization", alice())
                        .contentType(MediaType.APPLICATION_JSON).content(passwordChange("incorrect")))
                .andExpect(status().isForbidden());
        mvc.perform(put("/user/password").header("Authorization", alice())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"newPassword\":\"new-password\"}"))
                .andExpect(status().isBadRequest());
        assertThat(storedUsers.get("alice").getPassword()).isEqualTo(before);
        verifyNoInteractions(mongo);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/user/password", "/user/id/507f1f77bcf86cd799439011"})
    void selfPasswordChangeAuthenticatesWithNewPassword(String path) throws Exception {
        String bobBefore = storedUsers.get("bob").getPassword();
        mvc.perform(put(path).header("Authorization", alice())
                        .contentType(MediaType.APPLICATION_JSON).content(passwordChange(PASSWORD)))
                .andExpect(status().isAccepted());
        mvc.perform(get("/user").header("Authorization", alice())).andExpect(status().isUnauthorized());
        mvc.perform(get("/user").header("Authorization", basic("alice", "new-password")))
                .andExpect(status().isOk());
        assertThat(storedUsers.get("bob").getPassword()).isEqualTo(bobBefore);
        verify(users, never()).save(any(User.class));
    }

    @Test
    void concurrentPasswordChangeReturnsConflict() throws Exception {
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        mvc.perform(put("/user/password").header("Authorization", alice())
                        .contentType(MediaType.APPLICATION_JSON).content(passwordChange(PASSWORD)))
                .andExpect(status().isConflict());
    }

    @Test
    void crossAccountEntryOperationsAreDeniedWithoutMutation() throws Exception {
        String bobBefore = storedUsers.get("bob").getPassword();
        mvc.perform(get("/demo/" + BOB_ID).header("Authorization", alice())).andExpect(status().isForbidden());
        mvc.perform(get("/demo/id/" + BOB_ENTRY).header("Authorization", alice())).andExpect(status().isNotFound());
        mvc.perform(delete("/demo/id/" + BOB_ENTRY).header("Authorization", alice())).andExpect(status().isNotFound());
        mvc.perform(post("/demo/" + BOB_ID).header("Authorization", alice())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"injected\"}"))
                .andExpect(status().isForbidden());
        assertThat(storedUsers.get("bob").getPassword()).isEqualTo(bobBefore);
        assertThat(storedUsers.get("bob").getDemoEntries()).hasSize(1);
        assertThat(storedEntries.get(BOB_ENTRY).getContent()).isEqualTo("bob private content");
        verifyNoInteractions(mongo, entries);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/demo", "/demo/507f1f77bcf86cd799439011"})
    void createRejectsClientSuppliedEntryId(String path) throws Exception {
        mvc.perform(post(path).header("Authorization", alice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + BOB_ENTRY + "\",\"title\":\"overwrite\"}"))
                .andExpect(status().isBadRequest());
        assertThat(storedEntries.get(BOB_ENTRY).getContent()).isEqualTo("bob private content");
        verifyNoInteractions(mongo, entries);
    }

    @ParameterizedTest
    @ValueSource(strings = {"/demo", "/demo/507f1f77bcf86cd799439011"})
    void creatingEntryPreservesCredentialsAndLogin(String path) throws Exception {
        String before = storedUsers.get("alice").getPassword();
        mvc.perform(post(path).header("Authorization", alice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"new entry\",\"content\":\"private content\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.id").isString());
        assertThat(storedUsers.get("alice").getPassword()).isEqualTo(before);
        assertThat(storedUsers.get("alice").getRoles()).containsExactly("USER");
        assertThat(storedUsers.get("alice").getDemoEntries()).hasSize(2);
        assertThat(storedUsers.get("bob").getDemoEntries()).hasSize(1);
        mvc.perform(get("/user").header("Authorization", alice())).andExpect(status().isOk());
        mvc.perform(get("/demo").header("Authorization", alice()))
                .andExpect(jsonPath("$.length()").value(2));
        verify(users, never()).save(any(User.class));
        verify(entries, never()).save(any(DemoEntry.class));
    }

    @Test
    void ownEntriesRemainReadableAndDeletable() throws Exception {
        mvc.perform(get("/demo").header("Authorization", alice()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(ALICE_ENTRY));
        mvc.perform(get("/demo/" + ALICE_ID).header("Authorization", alice()))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(ALICE_ENTRY));
        mvc.perform(get("/demo/id/" + ALICE_ENTRY).header("Authorization", alice()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.content").value("alice private content"));
        mvc.perform(delete("/demo/id/" + ALICE_ENTRY).header("Authorization", alice()))
                .andExpect(status().isNoContent());
        mvc.perform(get("/demo/id/" + ALICE_ENTRY).header("Authorization", alice()))
                .andExpect(status().isNotFound());
        mvc.perform(get("/demo").header("Authorization", alice())).andExpect(jsonPath("$.length()").value(0));
        assertThat(storedEntries).doesNotContainKey(ALICE_ENTRY).containsKey(BOB_ENTRY);
    }

    @Test
    void entryCreationRejectsEmptyTitleAndHandlesDeletedOwner() throws Exception {
        mvc.perform(post("/demo").header("Authorization", alice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
        when(mongo.updateFirst(any(Query.class), any(Update.class), eq(User.class)))
                .thenReturn(UpdateResult.acknowledged(0, 0L, null));
        mvc.perform(post("/demo").header("Authorization", alice()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"entry\"}"))
                .andExpect(status().isConflict());
        assertThat(storedEntries).hasSize(2);
    }

    @Test
    void serviceLayerAlsoRejectsUnauthenticatedCalls() {
        assertThatThrownBy(() -> entryService.getAll(null)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> entryService.getById(BOB_ENTRY)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> entryService.create(new CreateEntryRequest("x", "y"), BOB_ID))
                .isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> entryService.deleteEntry(BOB_ENTRY)).isInstanceOf(ResponseStatusException.class);
        assertThatThrownBy(() -> userService.changePassword(new PasswordChangeRequest(PASSWORD, "x"), BOB_ID))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(mongo, entries, users);
    }

    private String passwordChange(String current) {
        return "{\"currentPassword\":\"" + current + "\",\"newPassword\":\"new-password\"}";
    }

    private String alice() {
        return basic("alice", PASSWORD);
    }

    private String basic(String username, String password) {
        return "Basic " + Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
    }

    private void seed(String username, String id, String entryId) {
        User user = new User(username, passwordEncoder.encode(PASSWORD));
        user.setId(id);
        user.setRoles(List.of("USER"));
        DemoEntry entry = new DemoEntry();
        entry.setId(entryId);
        entry.setTitle(username + " title");
        entry.setContent(username + " private content");
        user.getDemoEntries().add(entry);
        storedUsers.put(username, user);
        storedEntries.put(entryId, copyEntry(entry));
    }

    private User copy(User source) {
        User target = new User(source.getUsername(), source.getPassword());
        target.setId(source.getId());
        target.setRoles(new ArrayList<>(source.getRoles()));
        target.setDemoEntries(new ArrayList<>(source.getDemoEntries().stream().map(this::copyEntry).toList()));
        return target;
    }

    private DemoEntry copyEntry(DemoEntry source) {
        DemoEntry target = new DemoEntry();
        target.setId(source.getId());
        target.setTitle(source.getTitle());
        target.setContent(source.getContent());
        return target;
    }
}
