package com.example.demospring.dto;

import java.util.List;

public record UpdateEntryRequest(String title, String content, List<String> tags) {
}
