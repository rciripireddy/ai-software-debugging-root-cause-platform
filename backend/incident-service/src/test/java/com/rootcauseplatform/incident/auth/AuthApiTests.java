package com.rootcauseplatform.incident.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpRequest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.SpringBootTest.WebEnvironment;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import com.rootcauseplatform.incident.ApiClient;
import com.rootcauseplatform.incident.TestAuth;
import com.rootcauseplatform.incident.TestcontainersConfiguration;

import tools.jackson.databind.JsonNode;

/** Sign-in, sessions, token validation and the roles of the incident API. */
@SpringBootTest(webEnvironment = WebEnvironment.RANDOM_PORT)
@Import(TestcontainersConfiguration.class)
class AuthApiTests {

    @Value("${local.server.port}")
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AuthService authService;

    private ApiClient anonymous;

    @BeforeEach
    void setUp() {
        anonymous = new ApiClient(port);
    }

    @Test
    void initialAccountsAreCreatedFromConfigurationWithLowerCaseUsernames() {
        assertThat(jdbcTemplate.queryForList("select username || ':' || role || ':' || display_name from auth.users "
                + "where username in ('test-admin', 'test-viewer') order by username", String.class))
                .containsExactly("test-admin:ADMIN:Test Administrator", "test-viewer:VIEWER:Dashboard Viewer");
    }

    @Test
    void validLoginReturnsTheUserAndSetsAnHttpOnlySessionCookie() {
        ApiClient.Response response = login("TEST-ADMIN", TestAuth.ADMIN_PASSWORD);

        assertThat(response.status()).isEqualTo(200);
        JsonNode body = response.json();
        assertThat(body.at("/user/username").asString()).isEqualTo("test-admin");
        assertThat(body.at("/user/displayName").asString()).isEqualTo("Test Administrator");
        assertThat(body.at("/user/role").asString()).isEqualTo("ADMIN");
        assertThat(body.get("csrfToken").asString()).hasSizeGreaterThanOrEqualTo(40);
        assertThat(Instant.parse(body.get("expiresAt").asString()))
                .isBetween(Instant.now().plus(59, ChronoUnit.MINUTES), Instant.now().plus(61, ChronoUnit.MINUTES));
        assertThat(response.body()).doesNotContainIgnoringCase("password").doesNotContain("$2a$");

        String cookie = response.header("Set-Cookie");
        assertThat(cookie).startsWith("rcp_session=").contains("HttpOnly", "SameSite=Strict", "Path=/", "Max-Age=")
                .doesNotContain("Secure");
        assertThat(response.header("Cache-Control")).contains("no-store");
    }

    @Test
    void unknownUserWrongPasswordAndDisabledAccountGetTheSameAnswer() {
        String disabled = createUser(Role.VIEWER);
        jdbcTemplate.update("update auth.users set enabled = false where username = ?", disabled);

        List<ApiClient.Response> failures = List.of(
                login("no-such-user-" + UUID.randomUUID(), "some-password-123"),
                login(TestAuth.VIEWER_USERNAME, "wrong-password-123"),
                login(disabled, "password-for-" + disabled));

        for (ApiClient.Response failure : failures) {
            assertThat(failure.status()).isEqualTo(401);
            assertThat(failure.header("Set-Cookie")).isNull();
            assertThat(failure.json().get("title").asString()).isEqualTo("Invalid credentials");
            assertThat(failure.json().get("detail").asString()).isEqualTo("The username or password is incorrect.");
        }
    }

    @Test
    void loginRequestsAreValidated() {
        assertThat(anonymous.postJson("/api/v1/auth/login", Map.of("username", "x")).status()).isEqualTo(400);
        assertThat(anonymous.postJson("/api/v1/auth/login",
                Map.of("username", "x".repeat(101), "password", "secret-password")).status()).isEqualTo(400);
        assertThat(anonymous.postRaw("/api/v1/auth/login", "username=a&password=b",
                "application/x-www-form-urlencoded").status()).isEqualTo(415);
    }

    @Test
    void passwordsAreStoredOnlyAsBcryptHashes() {
        String hash = jdbcTemplate.queryForObject("select password_hash from auth.users where username = 'test-viewer'",
                String.class);

        assertThat(hash).startsWith("{bcrypt}$2a$12$").doesNotContain(TestAuth.VIEWER_PASSWORD);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                insert into auth.users (username, display_name, password_hash, role)
                values ('plain-text', 'Plain', 'a-plain-text-password', 'VIEWER')"""))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("ck_users_password_hash_encoded");
    }

    @Test
    void meReturnsTheSignedInUserFromTheCookie() {
        Session session = signIn(TestAuth.VIEWER_USERNAME, TestAuth.VIEWER_PASSWORD);

        ApiClient.Response me = anonymous.withHeaders(session.cookieHeader()).get("/api/v1/auth/me");

        assertThat(me.status()).isEqualTo(200);
        assertThat(me.json().at("/user/username").asString()).isEqualTo("test-viewer");
        assertThat(me.json().at("/user/role").asString()).isEqualTo("VIEWER");
        assertThat(me.json().get("csrfToken").asString()).isEqualTo(session.csrfToken());
        assertThat(anonymous.withHeaders(session.cookieHeader()).get("/api/v1/incidents").status()).isEqualTo(200);
    }

    @Test
    void meRejectsAccountsThatWereDisabledAfterSignIn() {
        String username = createUser(Role.VIEWER);
        Session session = signIn(username, "password-for-" + username);
        jdbcTemplate.update("update auth.users set enabled = false where username = ?", username);

        ApiClient.Response me = anonymous.withHeaders(session.cookieHeader()).get("/api/v1/auth/me");

        assertThat(me.status()).isEqualTo(401);
        assertThat(me.json().get("title").asString()).isEqualTo("Authentication required");
    }

    @Test
    void protectedEndpointsRequireCredentials() {
        for (String path : List.of("/api/v1/incidents", "/api/v1/incidents/stats",
                "/api/v1/incidents/" + UUID.randomUUID(), "/api/v1/auth/me")) {
            ApiClient.Response response = anonymous.get(path);
            assertThat(response.status()).as(path).isEqualTo(401);
            assertThat(response.header("Content-Type")).startsWith("application/problem+json");
            assertThat(response.json().get("title").asString()).isEqualTo("Authentication required");
            assertThat(response.json().get("correlationId").asString()).isNotBlank();
            assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer");
        }
        assertThat(anonymous.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of(UUID.randomUUID())))
                .status()).isEqualTo(401);
        assertThat(anonymous.get("/actuator/env").status()).isEqualTo(401);
    }

    @Test
    void healthAndInfoStayPublicEvenWithAnInvalidCookie() {
        ApiClient withStaleCookie = anonymous.withHeaders(Map.of("Cookie", "rcp_session=not-a-token"));

        assertThat(withStaleCookie.get("/actuator/health").status()).isEqualTo(200);
        assertThat(withStaleCookie.get("/actuator/health/readiness").status()).isEqualTo(200);
        assertThat(withStaleCookie.get("/actuator/info").status()).isEqualTo(200);
        assertThat(withStaleCookie.postJson("/api/v1/auth/login",
                Map.of("username", TestAuth.VIEWER_USERNAME, "password", TestAuth.VIEWER_PASSWORD)).status())
                .isEqualTo(200);
    }

    @Test
    void expiredTokensAreRejectedAsAnExpiredSession() {
        Instant past = Instant.now().minus(2, ChronoUnit.HOURS);
        String expired = TestAuth.token(TestAuth.claims("VIEWER", past, past.plus(1, ChronoUnit.HOURS)),
                TestAuth.JWT_SECRET);

        ApiClient.Response response = anonymous.withHeaders(TestAuth.bearer(expired)).get("/api/v1/incidents");

        assertThat(response.status()).isEqualTo(401);
        assertThat(response.json().get("title").asString()).isEqualTo("Session expired");
        assertThat(response.header("WWW-Authenticate")).isEqualTo("Bearer error=\"invalid_token\"");
    }

    @Test
    void tamperedForgedAndForeignTokensAreRejected() {
        Instant now = Instant.now();
        String valid = TestAuth.token("VIEWER");
        String[] parts = valid.split("\\.");
        String adminClaims = Base64.getUrlEncoder().withoutPadding().encodeToString(
                new String(Base64.getUrlDecoder().decode(parts[1])).replace("VIEWER", "ADMIN").getBytes());

        List<String> rejected = List.of(
                parts[0] + "." + adminClaims + "." + parts[2], // payload changed, signature kept
                TestAuth.token(TestAuth.claims("ADMIN", now, now.plusSeconds(600)),
                        "another-secret-of-at-least-32-characters"),
                TestAuth.token(TestAuth.claims("ADMIN", now, now.plusSeconds(600)).issuer("someone-else"),
                        TestAuth.JWT_SECRET),
                TestAuth.token(TestAuth.claims("ADMIN", now, now.plusSeconds(600)).audience(List.of("other-api")),
                        TestAuth.JWT_SECRET),
                TestAuth.token(TestAuth.claims("SUPERUSER", now, now.plusSeconds(600)), TestAuth.JWT_SECRET),
                parts[0] + "." + parts[1] + ".", // signature removed
                "not.a.jwt");

        for (String token : rejected) {
            ApiClient.Response response = anonymous.withHeaders(TestAuth.bearer(token)).get("/api/v1/incidents");
            assertThat(response.status()).as(token).isEqualTo(401);
            assertThat(response.json().get("title").asString()).isEqualTo("Authentication required");
        }
    }

    @Test
    void malformedAuthorizationHeadersAreRejected() {
        for (String header : List.of("Basic dGVzdDp0ZXN0", "Bearer", "Bearer    ", "Token abc", "bearer x.y.z")) {
            ApiClient.Response response = anonymous.withHeaders(Map.of("Authorization", header))
                    .get("/api/v1/incidents");
            assertThat(response.status()).as(header).isEqualTo(401);
        }
    }

    @Test
    void viewersCannotUseAdministratorActions() {
        ApiClient viewer = anonymous.withHeaders(TestAuth.bearer(TestAuth.token("VIEWER")));
        ApiClient admin = anonymous.withHeaders(TestAuth.bearer(TestAuth.token("ADMIN")));
        Map<String, Object> request = Map.of("errorEventIds", List.of(UUID.randomUUID()));

        ApiClient.Response denied = viewer.postJson("/api/v1/incidents", request);
        assertThat(denied.status()).isEqualTo(403);
        assertThat(denied.json().get("title").asString()).isEqualTo("Forbidden");
        assertThat(viewer.get("/api/v1/incidents").status()).isEqualTo(200);

        // Allowed past authorization; the unknown event is then rejected by the endpoint itself.
        assertThat(admin.postJson("/api/v1/incidents", request).status()).isEqualTo(422);
        assertThat(admin.get("/api/v1/incidents/stats").status()).isEqualTo(200);
    }

    @Test
    void cookieAuthenticatedChangesRequireTheCsrfToken() {
        Session session = signIn(TestAuth.VIEWER_USERNAME, TestAuth.VIEWER_PASSWORD);
        ApiClient withCookie = anonymous.withHeaders(session.cookieHeader());

        ApiClient.Response withoutToken = withCookie.post("/api/v1/auth/logout");
        assertThat(withoutToken.status()).isEqualTo(403);
        assertThat(withoutToken.json().get("title").asString()).isEqualTo("CSRF token missing or invalid");

        Map<String, String> wrongToken = new HashMap<>(session.cookieHeader());
        wrongToken.put("X-CSRF-Token", "not-the-token");
        assertThat(anonymous.withHeaders(wrongToken).post("/api/v1/auth/logout").status()).isEqualTo(403);
        assertThat(withCookie.putJson("/api/v1/auth/password",
                Map.of("currentPassword", "a", "newPassword", "b")).status()).isEqualTo(403);

        ApiClient.Response logout = anonymous.withHeaders(session.headersWithCsrf()).post("/api/v1/auth/logout");
        assertThat(logout.status()).isEqualTo(204);
        assertThat(logout.header("Set-Cookie")).startsWith("rcp_session=;").contains("Max-Age=0", "HttpOnly");
    }

    @Test
    void logoutWithoutASessionStillClearsTheCookie() {
        ApiClient.Response response = anonymous.post("/api/v1/auth/logout");

        assertThat(response.status()).isEqualTo(204);
        assertThat(response.header("Set-Cookie")).contains("Max-Age=0");
    }

    @Test
    void serviceTokenAllowsReadingIncidentsOnly() {
        ApiClient service = anonymous.withHeaders(TestAuth.serviceToken());

        assertThat(service.get("/api/v1/incidents").status()).isEqualTo(200);
        assertThat(service.get("/api/v1/auth/me").status()).isEqualTo(403);
        assertThat(service.postJson("/api/v1/incidents", Map.of("errorEventIds", List.of(UUID.randomUUID())))
                .status()).isEqualTo(403);

        String userToken = TestAuth.token("ADMIN");
        assertThat(anonymous.withHeaders(Map.of("X-Service-Token", "wrong-token")).get("/api/v1/incidents").status())
                .isEqualTo(401);
        assertThat(anonymous.withHeaders(Map.of("X-Service-Token", userToken)).get("/api/v1/incidents").status())
                .isEqualTo(401);
        assertThat(anonymous.withHeaders(TestAuth.bearer(TestAuth.SERVICE_TOKEN)).get("/api/v1/incidents").status())
                .isEqualTo(401);
    }

    @Test
    void repeatedFailedLoginsAreRateLimitedPerUsername() {
        String username = createUser(Role.VIEWER);
        for (int attempt = 1; attempt <= 5; attempt++) {
            assertThat(login(username, "wrong-password-" + attempt).status()).isEqualTo(401);
        }

        ApiClient.Response limited = login(username, "password-for-" + username);

        assertThat(limited.status()).isEqualTo(429);
        assertThat(limited.json().get("title").asString()).isEqualTo("Too many requests");
        assertThat(Long.parseLong(limited.header("Retry-After"))).isBetween(1L, 15 * 60L);
        assertThat(login(TestAuth.VIEWER_USERNAME, TestAuth.VIEWER_PASSWORD).status()).isEqualTo(200);
    }

    @Test
    void usersCanChangeTheirPassword() {
        String username = createUser(Role.VIEWER);
        String oldPassword = "password-for-" + username;
        Session session = signIn(username, oldPassword);
        ApiClient user = anonymous.withHeaders(session.headersWithCsrf());

        assertThat(user.putJson("/api/v1/auth/password",
                Map.of("currentPassword", "wrong-current-1", "newPassword", "a-new-password-123")).status())
                .isEqualTo(422);
        ApiClient.Response tooShort = user.putJson("/api/v1/auth/password",
                Map.of("currentPassword", oldPassword, "newPassword", "short"));
        assertThat(tooShort.status()).isEqualTo(422);
        assertThat(tooShort.json().get("detail").asString()).contains("at least 12 characters");

        assertThat(user.putJson("/api/v1/auth/password",
                Map.of("currentPassword", oldPassword, "newPassword", "a-new-password-123")).status()).isEqualTo(204);
        assertThat(login(username, oldPassword).status()).isEqualTo(401);
        assertThat(login(username, "a-new-password-123").status()).isEqualTo(200);
    }

    @Test
    void responsesCarrySecurityHeaders() {
        for (ApiClient.Response response : List.of(anonymous.get("/actuator/health"), anonymous.get("/api/v1/incidents"),
                TestAuth.client(port).get("/api/v1/incidents"))) {
            assertThat(response.header("X-Content-Type-Options")).isEqualTo("nosniff");
            assertThat(response.header("X-Frame-Options")).isEqualTo("DENY");
            assertThat(response.header("Content-Security-Policy")).isEqualTo("default-src 'none'; frame-ancestors 'none'");
            assertThat(response.header("Referrer-Policy")).isEqualTo("no-referrer");
            assertThat(response.header("Permissions-Policy")).contains("camera=()");
            assertThat(response.header("Cache-Control")).contains("no-store");
            assertThat(response.header("Strict-Transport-Security")).as("only sent over HTTPS").isNull();
        }
    }

    @Test
    void crossOriginRequestsAreNotAllowed() {
        HttpRequest preflight = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .method("OPTIONS", HttpRequest.BodyPublishers.noBody())
                .header("Origin", "https://attacker.example")
                .header("Access-Control-Request-Method", "POST")
                .header("Access-Control-Request-Headers", "content-type")
                .build();

        ApiClient.Response response = anonymous.send(preflight);

        assertThat(response.status()).isGreaterThanOrEqualTo(400);
        assertThat(response.header("Access-Control-Allow-Origin")).isNull();
        assertThat(response.header("Access-Control-Allow-Credentials")).isNull();
    }

    @Test
    void oversizedAndUnboundedBodiesAreRejectedBeforeReading() {
        String huge = "{\"username\": \"" + "x".repeat(1_100_000) + "\", \"password\": \"y\"}";
        ApiClient.Response tooLarge = anonymous.postRaw("/api/v1/auth/login", huge, "application/json");
        assertThat(tooLarge.status()).isEqualTo(413);
        assertThat(tooLarge.json().get("title").asString()).isEqualTo("Request too large");

        HttpRequest chunked = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/v1/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofInputStream(
                        () -> new ByteArrayInputStream("{\"username\":\"a\",\"password\":\"b\"}".getBytes())))
                .build();
        assertThat(anonymous.send(chunked).status()).isEqualTo(411);
    }

    private ApiClient.Response login(String username, String password) {
        return anonymous.postJson("/api/v1/auth/login", Map.of("username", username, "password", password));
    }

    private Session signIn(String username, String password) {
        ApiClient.Response response = login(username, password);
        assertThat(response.status()).isEqualTo(200);
        String cookie = response.header("Set-Cookie");
        return new Session(cookie.substring(0, cookie.indexOf(';')), response.json().get("csrfToken").asString());
    }

    /** A new account with the password {@code password-for-<username>}. */
    private String createUser(Role role) {
        String username = "user-" + UUID.randomUUID().toString().substring(0, 8);
        authService.createIfMissing(username, "Test User", "password-for-" + username, role);
        return username;
    }

    private record Session(String cookie, String csrfToken) {

        Map<String, String> cookieHeader() {
            return Map.of("Cookie", cookie);
        }

        Map<String, String> headersWithCsrf() {
            return Map.of("Cookie", cookie, "X-CSRF-Token", csrfToken);
        }
    }
}
