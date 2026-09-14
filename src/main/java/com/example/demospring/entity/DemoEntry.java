package com.example.demospring.entity;

import lombok.Getter;
import lombok.NonNull;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

@Document(collection = "demo_entries")
@Getter
@Setter
public class DemoEntry {

    @Id
    private String id;

    @NonNull
    private String title;

    private  String content;

}

