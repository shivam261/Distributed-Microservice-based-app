package com.example.demospring.dto;

import java.util.List;

public record CreateEntryRequest(String title, String content, List<String> tags) {
}
