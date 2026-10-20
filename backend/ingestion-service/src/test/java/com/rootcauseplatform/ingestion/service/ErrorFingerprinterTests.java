package com.rootcauseplatform.ingestion.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ErrorFingerprinterTests {

    private static final String STACK_TRACE = """
            java.net.SocketTimeoutException: Read timed out
            \tat java.base/sun.nio.ch.NioSocketImpl.timedRead(NioSocketImpl.java:288)
            \tat com.shop.payment.GatewayClient.authorize(GatewayClient.java:74)
            \tat com.shop.payment.PaymentService.charge(PaymentService.java:41)
            """;

    private final ErrorFingerprinter fingerprinter = new ErrorFingerprinter();

    @Test
    void producesTheSameHexEncodedSha256ForTheSameInput() {
        String first = fingerprint("payment-service", "java.net.SocketTimeoutException", "Read timed out", STACK_TRACE);
        String second = fingerprint("payment-service", "java.net.SocketTimeoutException", "Read timed out", STACK_TRACE);

        assertThat(first).isEqualTo(second).matches("[0-9a-f]{64}");
    }

    @Test
    void ignoresValuesThatChangeBetweenOccurrences() {
        String first = fingerprint("inventory-service", "DataAccessException",
                "Pool-1 timed out after 30000ms for order 3f2b8c1e-9a4d-4e57-b0c2-7d1e6f5a9b33", null);
        String second = fingerprint("inventory-service", "DataAccessException",
                "Pool-2 timed out after 15000ms for order 0a1b2c3d-4e5f-6a7b-8c9d-0e1f2a3b4c5d", null);

        assertThat(first).isEqualTo(second);
    }

    @Test
    void ignoresLineNumbersCaseAndWhitespace() {
        String shiftedLines = STACK_TRACE.replace(":74)", ":80)").replace(":41)", ":45)");

        assertThat(fingerprint("Payment-Service", "java.net.SocketTimeoutException", "Read  timed out", shiftedLines))
                .isEqualTo(fingerprint("payment-service", "java.net.SocketTimeoutException", "read timed out",
                        STACK_TRACE));
    }

    @Test
    void distinguishesServicesExceptionTypesAndCallSites() {
        String base = fingerprint("payment-service", "java.net.SocketTimeoutException", "Read timed out", STACK_TRACE);

        assertThat(fingerprint("checkout-service", "java.net.SocketTimeoutException", "Read timed out", STACK_TRACE))
                .isNotEqualTo(base);
        assertThat(fingerprint("payment-service", "java.net.ConnectException", "Read timed out", STACK_TRACE))
                .isNotEqualTo(base);
        assertThat(fingerprint("payment-service", "java.net.SocketTimeoutException", "Read timed out",
                STACK_TRACE.replace("GatewayClient.authorize", "GatewayClient.capture")))
                .isNotEqualTo(base);
    }

    @Test
    void usesOnlyTheTopStackFrames() {
        String deeper = STACK_TRACE + """
                \tat com.shop.payment.Frame4.call(Frame4.java:1)
                \tat com.shop.payment.Frame5.call(Frame5.java:1)
                """;

        assertThat(fingerprint("svc", "E", "m", deeper + "\tat com.shop.Frame6.call(Frame6.java:1)\n"))
                .isEqualTo(fingerprint("svc", "E", "m", deeper + "\tat com.shop.Other.call(Other.java:1)\n"));
        assertThat(ErrorFingerprinter.topFrames(deeper)).hasSize(ErrorFingerprinter.MAX_FRAMES);
    }

    @Test
    void supportsPythonTracebacks() {
        String traceback = """
                Traceback (most recent call last):
                  File "app/handlers.py", line 42, in charge
                  File "app/gateway.py", line 17, in authorize
                TimeoutError: timed out
                """;

        assertThat(ErrorFingerprinter.topFrames(traceback))
                .containsExactly("file \"app/handlers.py\", line, in charge", "file \"app/gateway.py\", line, in authorize");
    }

    @Test
    void handlesEventsWithoutExceptionDetails() {
        assertThat(fingerprint("svc", null, "Disk usage above 90%", null)).matches("[0-9a-f]{64}");
    }

    private String fingerprint(String service, String exceptionType, String message, String stackTrace) {
        return fingerprinter.fingerprint(service, exceptionType, message, stackTrace);
    }
}
