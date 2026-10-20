package com.eventflow.identity.web.dto;

import java.util.List;

public record UserResponse(Long id, String email, List<String> roles) {}
