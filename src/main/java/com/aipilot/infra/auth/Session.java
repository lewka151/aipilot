package com.aipilot.infra.auth;

public record Session(
        String apiToken,
        Integer userId,
        String userName,
        boolean admin
) {
    public boolean isAuthenticated() {
        return apiToken != null && !apiToken.isBlank();
    }

    public boolean isAdmin() {
        return admin;
    }
}
