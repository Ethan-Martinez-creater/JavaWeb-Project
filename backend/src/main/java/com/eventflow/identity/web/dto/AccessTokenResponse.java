package com.eventflow.identity.web.dto;

public record AccessTokenResponse(String accessToken, String tokenType, long expiresInSeconds) {}
