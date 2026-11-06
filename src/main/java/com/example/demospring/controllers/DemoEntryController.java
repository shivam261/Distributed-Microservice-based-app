package com.example.demospring.controllers;

import com.example.demospring.dto.CreateEntryRequest;
import com.example.demospring.dto.UpdateEntryRequest;
import com.example.demospring.entity.DemoEntry;
import com.example.demospring.service.DemoEntryService;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/demo")
public class DemoEntryController {
    private final DemoEntryService demoEntryService;

    public DemoEntryController(DemoEntryService demoEntryService) {
        this.demoEntryService = demoEntryService;
    }

    @GetMapping({"", "/{userid}"})
    public ResponseEntity<List<DemoEntry>> getAll(@PathVariable(required = false) String userid,
                                                 @RequestParam(required = false) String q,
                                                 @RequestParam(required = false) String tag,
                                                 @RequestParam(required = false) String sort,
                                                 @RequestParam(required = false) Integer page,
                                                 @RequestParam(required = false) Integer size) {
        return paginate(demoEntryService.getAll(userid, q, tag, sort), page, size);
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

    @PatchMapping("/id/{id}")
    public DemoEntry editDemoEntry(@PathVariable String id, @RequestBody UpdateEntryRequest request) {
        return demoEntryService.edit(id, request);
    }

    @DeleteMapping("/id/{id}")
    public ResponseEntity<Void> deleteDemoEntry(@PathVariable String id) {
        demoEntryService.deleteEntry(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/trash")
    public ResponseEntity<List<DemoEntry>> getTrash(@RequestParam(required = false) Integer page,
                                                   @RequestParam(required = false) Integer size) {
        return paginate(demoEntryService.getTrash(), page, size);
    }

    @PatchMapping("/trash/{id}")
    public DemoEntry restoreDemoEntry(@PathVariable String id) {
        return demoEntryService.restore(id);
    }

    @DeleteMapping("/trash/{id}")
    public ResponseEntity<Void> purgeDemoEntry(@PathVariable String id) {
        demoEntryService.purge(id);
        return ResponseEntity.noContent().build();
    }

    private static ResponseEntity<List<DemoEntry>> paginate(List<DemoEntry> entries, Integer page, Integer size) {
        if (page == null && size == null) {
            return ResponseEntity.ok(entries);
        }
        int pageNumber = page == null ? 0 : page;
        int pageSize = size == null ? 20 : size;
        if (pageNumber < 0 || pageSize < 1 || pageSize > 100) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page must be nonnegative and size must be 1–100");
        }
        int start = (int) Math.min((long) pageNumber * pageSize, entries.size());
        int end = start + Math.min(pageSize, entries.size() - start);
        return ResponseEntity.ok().header("X-Total-Count", Integer.toString(entries.size()))
                .body(entries.subList(start, end));
    }
}
