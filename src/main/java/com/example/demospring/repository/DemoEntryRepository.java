package com.example.demospring.repository;

import com.example.demospring.entity.DemoEntry;
import org.springframework.data.mongodb.repository.MongoRepository;


public interface DemoEntryRepository extends MongoRepository<DemoEntry, String> {

}
