package com.rootcauseplatform.incident.auth;

import java.time.Duration;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.rootcauseplatform.incident.auth.AuthMessages.ChangePasswordRequest;
import com.rootcauseplatform.incident.auth.AuthMessages.LoginRequest;
import com.rootcauseplatform.incident.auth.AuthMessages.SessionResponse;
import com.rootcauseplatform.incident.auth.AuthMessages.UserResponse;
import com.rootcauseplatform.incident.auth.TokenService.IssuedToken;
import com.rootcauseplatform.incident.security.AuthProperties;
import com.rootcauseplatform.incident.security.CallerPrincipal;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

/**
 * Sign-in for the dashboard and API clients. A successful sign-in sets the access token as an
 * HttpOnly, SameSite=Strict cookie (used by the dashboard); API clients can send the same token
 * as {@code Authorization: Bearer}. See docs/api.md#authentication.
 */
@RestController
@RequestMapping(path = "/api/v1/auth", produces = MediaType.APPLICATION_JSON_VALUE)
public class AuthController {

    public static final String LOGIN_PATH = "/api/v1/auth/login";
    public static final String LOGOUT_PATH = "/api/v1/auth/logout";

    private static final Logger log = LoggerFactory.getLogger(AuthController.class);

    private final AuthService authService;
    private final TokenService tokenService;
    private final AuthProperties properties;

    public AuthController(AuthService authService, TokenService tokenService, AuthProperties properties) {
        this.authService = authService;
        this.tokenService = tokenService;
        this.properties = properties;
    }

    @PostMapping(path = "/login", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<SessionResponse> login(@Valid @RequestBody LoginRequest body, HttpServletRequest request) {
        UserAccount user = authService.authenticate(body.username(), body.password(), request.getRemoteAddr());
        IssuedToken token = tokenService.issue(user);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, sessionCookie(token.value(), Duration.between(Instant.now(),
                        token.expiresAt())).toString())
                .body(new SessionResponse(UserResponse.from(user), token.expiresAt(), token.csrfToken()));
    }

    /** The signed-in user; also how the dashboard finds out whether its cookie is still valid. */
    @GetMapping("/me")
    public SessionResponse me(@AuthenticationPrincipal CallerPrincipal caller) {
        UserAccount user = authService.currentUser(caller.id());
        return new SessionResponse(UserResponse.from(user), caller.expiresAt(), caller.csrfToken());
    }

    /**
     * Removes the session cookie. Access tokens are not stored on the server, so a token copied
     * before sign-out stays valid until it expires (see docs/security.md).
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(@AuthenticationPrincipal CallerPrincipal caller) {
        if (caller != null && caller.kind() == CallerPrincipal.Kind.USER) {
            log.atInfo()
                    .setMessage("Signed out")
                    .addKeyValue("action", "auth.logout")
                    .addKeyValue("userId", caller.id())
                    .addKeyValue("username", caller.username())
                    .log();
        }
        return ResponseEntity.noContent().header(HttpHeaders.SET_COOKIE, sessionCookie("", Duration.ZERO).toString())
                .build();
    }

    @PutMapping(path = "/password", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<Void> changePassword(@AuthenticationPrincipal CallerPrincipal caller,
            @Valid @RequestBody ChangePasswordRequest body, HttpServletRequest request) {
        authService.changePassword(caller.id(), body.currentPassword(), body.newPassword(), request.getRemoteAddr());
        return ResponseEntity.noContent().build();
    }

    private ResponseCookie sessionCookie(String value, Duration maxAge) {
        return ResponseCookie.from(properties.cookieName(), value)
                .httpOnly(true)
                .secure(properties.cookieSecure())
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build();
    }
}
