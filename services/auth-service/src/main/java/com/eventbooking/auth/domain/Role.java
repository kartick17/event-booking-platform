package com.eventbooking.auth.domain;

public enum Role {
    USER,
    ADMIN;

    public String asAuthority() {
        return "ROLE_" + name();
    }
}