package com.rootcauseplatform.incident.service;

/** The request conflicts with the current state, e.g. an active incident already exists. */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
