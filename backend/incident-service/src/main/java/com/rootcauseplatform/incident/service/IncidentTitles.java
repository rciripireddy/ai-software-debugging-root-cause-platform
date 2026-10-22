package com.rootcauseplatform.incident.service;

/** Default incident titles: {@code "<exceptionType>: <message>"}, or the message alone. */
final class IncidentTitles {

    private static final int MAX_LENGTH = 255;

    private IncidentTitles() {
    }

    static String of(String exceptionType, String message) {
        String title = exceptionType == null || exceptionType.isBlank() ? message : exceptionType + ": " + message;
        title = title.strip().replaceAll("\\s+", " ");
        return title.length() <= MAX_LENGTH ? title : title.substring(0, MAX_LENGTH - 1) + "…";
    }
}
