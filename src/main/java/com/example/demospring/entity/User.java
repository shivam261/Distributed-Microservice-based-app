package com.example.demospring.entity;

import lombok.Data;
import lombok.NonNull;
import com.fasterxml.jackson.annotation.JsonIgnore;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.DBRef;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.Indexed;

import java.util.ArrayList;
import java.util.List;

@Document(collection = "users")
@CompoundIndex(name = "unique_entry_owner", def = "{ 'demoEntries': 1 }", unique = true,
        partialFilter = "{ 'demoEntries.0': { '$exists': true } }")
@Data
public class User {
    @Id
    private String id;

    @Indexed(unique = true)
    @NonNull
    private String username;

    @NonNull
    @JsonIgnore
    @lombok.ToString.Exclude
    private String password;

    @DBRef
    private List<DemoEntry> demoEntries = new ArrayList<>();
    private List<String> roles = new ArrayList<>();

}
