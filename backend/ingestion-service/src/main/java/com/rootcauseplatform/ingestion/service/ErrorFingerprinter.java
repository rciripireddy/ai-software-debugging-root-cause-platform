package com.rootcauseplatform.ingestion.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

/**
 * Computes a deterministic fingerprint that identifies "the same error" across occurrences.
 *
 * <p>The fingerprint is a SHA-256 hash over the service name, the exception type, the
 * normalized message and the top {@value #MAX_FRAMES} normalized stack frames. Normalization
 * removes values that change between occurrences of the same problem: UUIDs, hexadecimal
 * values, numbers, and line numbers in stack frames. The environment is deliberately not
 * included, so the same error in staging and production shares a fingerprint.
 */
@Component
public class ErrorFingerprinter {

    static final int MAX_FRAMES = 5;

    /** Bump when the algorithm changes so old and new fingerprints never collide. */
    private static final String ALGORITHM_VERSION = "v1";

    private static final Pattern UUID = Pattern.compile(
            "\\b[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\b");
    private static final Pattern HEX = Pattern.compile("\\b0x[0-9a-f]+\\b|\\b[0-9a-f]{16,}\\b");
    private static final Pattern NUMBER = Pattern.compile("\\d+");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");
    private static final Pattern JAVA_LINE_NUMBER = Pattern.compile(":\\d+\\)");
    private static final Pattern PYTHON_LINE_NUMBER = Pattern.compile("line \\d+");

    public String fingerprint(String service, String exceptionType, String message, String stackTrace) {
        List<String> parts = new ArrayList<>();
        parts.add(ALGORITHM_VERSION);
        parts.add(normalizeName(service));
        parts.add(normalizeName(exceptionType));
        parts.add(normalizeMessage(message));
        parts.addAll(topFrames(stackTrace));
        return sha256(String.join("\n", parts));
    }

    static String normalizeMessage(String message) {
        if (message == null) {
            return "";
        }
        String normalized = message.toLowerCase(Locale.ROOT);
        normalized = UUID.matcher(normalized).replaceAll("<uuid>");
        normalized = HEX.matcher(normalized).replaceAll("<hex>");
        normalized = NUMBER.matcher(normalized).replaceAll("<n>");
        return WHITESPACE.matcher(normalized).replaceAll(" ").trim();
    }

    /**
     * Extracts the first stack frames, for example {@code at com.shop.Foo.bar(Foo.java:42)}
     * (Java) or {@code File "app.py", line 12, in handler} (Python), without line numbers.
     */
    static List<String> topFrames(String stackTrace) {
        if (stackTrace == null || stackTrace.isBlank()) {
            return List.of();
        }
        return stackTrace.lines()
                .map(String::strip)
                .filter(line -> line.startsWith("at ") || line.startsWith("File \""))
                .limit(MAX_FRAMES)
                .map(ErrorFingerprinter::normalizeFrame)
                .toList();
    }

    private static String normalizeFrame(String frame) {
        String normalized = JAVA_LINE_NUMBER.matcher(frame).replaceAll(")");
        normalized = PYTHON_LINE_NUMBER.matcher(normalized).replaceAll("line");
        return HEX.matcher(normalized.toLowerCase(Locale.ROOT)).replaceAll("<hex>");
    }

    private static String normalizeName(String value) {
        return value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", e);
        }
    }
}
