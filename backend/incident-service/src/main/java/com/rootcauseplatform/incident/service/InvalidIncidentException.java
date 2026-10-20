package com.rootcauseplatform.incident.service;

/** A syntactically valid request that cannot be processed, e.g. it references unknown events. */
public class InvalidIncidentException extends RuntimeException {

    public InvalidIncidentException(String message) {
        super(message);
    }
}
