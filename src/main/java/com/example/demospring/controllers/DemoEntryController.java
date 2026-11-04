package com.example.demospring.controllers;

import com.example.demospring.dto.CreateEntryRequest;
import com.example.demospring.entity.DemoEntry;
import com.example.demospring.service.DemoEntryService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/demo")
public class DemoEntryController {
    private final DemoEntryService demoEntryService;

    public DemoEntryController(DemoEntryService demoEntryService) {
        this.demoEntryService = demoEntryService;
    }

    @GetMapping({"", "/{userid}"})
    public List<DemoEntry> getAll(@PathVariable(required = false) String userid) {
        return demoEntryService.getAll(userid);
    }

    @PostMapping({"", "/{userid}"})
    public ResponseEntity<DemoEntry> createEntry(@RequestBody CreateEntryRequest request,
                                                @PathVariable(required = false) String userid) {
        return ResponseEntity.status(HttpStatus.CREATED).body(demoEntryService.create(request, userid));
    }

    @GetMapping("/id/{id}")
    public DemoEntry getDemoEntryById(@PathVariable String id) {
        return demoEntryService.getById(id);
    }

    @DeleteMapping("/id/{id}")
    public ResponseEntity<Void> deleteDemoEntry(@PathVariable String id) {
        demoEntryService.deleteEntry(id);
        return ResponseEntity.noContent().build();
    }
}
