package com.rootcauseplatform.incident.auth;

/** What a signed-in user may do: {@code VIEWER} reads everything, {@code ADMIN} can also run actions. */
public enum Role {
    ADMIN,
    VIEWER
}
