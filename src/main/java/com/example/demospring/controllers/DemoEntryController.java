package com.example.demospring.controllers;

import com.example.demospring.entity.DemoEntry;
import com.example.demospring.service.DemoEntryService;
import com.example.demospring.service.UserService;
import org.bson.types.ObjectId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/demo")
public class DemoEntryController {


    @Autowired
    private DemoEntryService demoEntryService;

    @Autowired
    private UserService userService;

    @GetMapping("{id}")
    public List<DemoEntry> getAll(@PathVariable ObjectId id){
        return demoEntryService.getAll(id);
    }

    @PostMapping("{userid}")
    public ResponseEntity<DemoEntry> createEntry(@RequestBody DemoEntry myEntry, @PathVariable ObjectId userid){

        boolean res = demoEntryService.saveEntry(myEntry, userid);

        return new ResponseEntity<DemoEntry>(HttpStatus.CREATED);
    }

    @GetMapping("id/{myId}")
    public ResponseEntity<DemoEntry> getDemoEntryById(@PathVariable String myId){
        Optional<DemoEntry> rs = demoEntryService.getById(myId);
        return rs.map(demoEntry -> new ResponseEntity<>(demoEntry, HttpStatus.OK)).orElseGet(() -> new ResponseEntity<>(HttpStatus.NOT_FOUND));

    }
    @DeleteMapping("id/{myId}")
    public ResponseEntity<?> deleteDemoEntry(@PathVariable String myId){// ? is wild card, we can return any object if we want
        demoEntryService.deleteEntry(myId);
        return new ResponseEntity<>(HttpStatus.NO_CONTENT);
    }



}
