package com.example.demospring.service;

import com.example.demospring.entity.DemoEntry;
import com.example.demospring.entity.User;
import com.example.demospring.repository.DemoEntryRepository;
import com.example.demospring.repository.UserRepository;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;


import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@Component
public class DemoEntryService
{
    @Autowired
    private DemoEntryRepository demoEntryRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserService userService;

    public boolean saveEntry(DemoEntry demoEntry, ObjectId userId){

        Optional<User> user = userService.getById(userId);
        if(user.isPresent()){
            DemoEntry demoentry = demoEntryRepository.save(demoEntry);
            user.get().getDemoEntries().add(demoentry);
            userService.createUser(user.get());
            return true;
        }
        return false;


    }

    public List<DemoEntry> getAll(ObjectId id){
        return userService.listAllDemos(id);
    }

    public void deleteEntry(String id){
        demoEntryRepository.deleteById(id);
        return;
    }

    public Optional<DemoEntry> getById(String id){
        return demoEntryRepository.findById(id);
    }



}
