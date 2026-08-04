package mozhi.parseltongue.controller;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import mozhi.parseltongue.config.AuthProperties;
import mozhi.parseltongue.dto.UserDTO;
import mozhi.parseltongue.dto.request.LoginRequest;
import mozhi.parseltongue.dto.request.RegisterRequest;
import mozhi.parseltongue.dto.response.ApiResponse;
import mozhi.parseltongue.dto.response.LoginResponse;
import mozhi.parseltongue.filter.JwtAuthenticationFilter;
import mozhi.parseltongue.service.AuthService;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseCookie;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final AuthService authService;
    private final AuthProperties properties;

    public AuthController(AuthService authService, AuthProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<UserDTO>> register(
            @Valid @RequestBody RegisterRequest request
    ) {
        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(ApiResponse.success(authService.register(request)));
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<LoginResponse>> login(
            @Valid @RequestBody LoginRequest request
    ) {
        return authenticationResponse(authService.login(request));
    }

    @PostMapping("/refresh")
    public ResponseEntity<ApiResponse<LoginResponse>> refresh(HttpServletRequest request) {
        String refreshToken = findRefreshToken(request);
        return authenticationResponse(authService.refresh(refreshToken));
    }

    @PostMapping("/logout")
    public ResponseEntity<ApiResponse<Void>> logout(
            @RequestAttribute(JwtAuthenticationFilter.CURRENT_USER_ATTRIBUTE) UserDTO user,
            @RequestAttribute(JwtAuthenticationFilter.CURRENT_SESSION_ID_ATTRIBUTE) String sessionId
    ) {
        authService.logout(user.id(), sessionId);
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                .body(ApiResponse.success());
    }

    @PostMapping("/logout-all")
    public ResponseEntity<ApiResponse<Void>> logoutAll(
            @RequestAttribute(JwtAuthenticationFilter.CURRENT_USER_ATTRIBUTE) UserDTO user
    ) {
        authService.logoutAll(user.id());
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, clearRefreshCookie().toString())
                .body(ApiResponse.success());
    }

    private ResponseEntity<ApiResponse<LoginResponse>> authenticationResponse(
            AuthService.AuthenticationResult result
    ) {
        ResponseCookie cookie = ResponseCookie
                .from(properties.cookie().refreshTokenName(), result.refreshToken())
                .httpOnly(true)
                .secure(properties.cookie().secure())
                .sameSite(properties.cookie().sameSite())
                .path("/api/auth")
                .maxAge(properties.session().ttl())
                .build();
        return ResponseEntity.ok()
                .header(HttpHeaders.SET_COOKIE, cookie.toString())
                .body(ApiResponse.success(result.response()));
    }

    private ResponseCookie clearRefreshCookie() {
        return ResponseCookie
                .from(properties.cookie().refreshTokenName(), "")
                .httpOnly(true)
                .secure(properties.cookie().secure())
                .sameSite(properties.cookie().sameSite())
                .path("/api/auth")
                .maxAge(0)
                .build();
    }

    private String findRefreshToken(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (properties.cookie().refreshTokenName().equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }
}
