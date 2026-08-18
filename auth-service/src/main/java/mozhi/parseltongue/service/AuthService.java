package mozhi.parseltongue.service;

import mozhi.parseltongue.config.AuthProperties;
import mozhi.parseltongue.dao.UserDao;
import mozhi.parseltongue.dao.UserSessionDao;
import mozhi.parseltongue.dto.UserDTO;
import mozhi.parseltongue.dto.request.LoginRequest;
import mozhi.parseltongue.dto.request.RegisterRequest;
import mozhi.parseltongue.dto.response.LoginResponse;
import mozhi.parseltongue.entity.User;
import mozhi.parseltongue.entity.UserRole;
import mozhi.parseltongue.entity.UserSession;
import mozhi.parseltongue.exception.ApiException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.UUID;

@Service
public class AuthService {

    private final UserDao userDao;
    private final UserSessionDao userSessionDao;
    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuthProperties properties;
    private final SecureRandom secureRandom = new SecureRandom();

    public AuthService(
            UserDao userDao,
            UserSessionDao userSessionDao,
            BCryptPasswordEncoder passwordEncoder,
            JwtService jwtService,
            AuthProperties properties
    ) {
        this.userDao = userDao;
        this.userSessionDao = userSessionDao;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.properties = properties;
    }

    @Transactional
    public UserDTO register(RegisterRequest request) {
        String username = request.username().trim();
        validatePasswordBytes(request.password());

        if (userDao.findByUsername(username) != null) {
            throw ApiException.conflict("用户名已被使用");
        }
        Instant now = Instant.now();
        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(passwordEncoder.encode(request.password()));
        user.setRole(UserRole.USER);
        user.setEnabled(true);
        user.setCreatedAt(now);
        user.setUpdatedAt(now);
        userDao.insert(user);
        return UserDTO.from(user);
    }

    @Transactional
    public AuthenticationResult login(LoginRequest request) {
        User user = userDao.findByUsername(request.username().trim());
        if (user == null || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw ApiException.unauthorized("用户名或密码错误");
        }
        if (!user.isEnabled()) {
            throw ApiException.forbidden("账号已被禁用");
        }

        Instant now = Instant.now();
        String refreshToken = newRefreshToken();
        UserSession session = new UserSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(user.getId());
        session.setRefreshTokenHash(hashToken(refreshToken));
        session.setCreatedAt(now);
        session.setExpiresAt(now.plus(properties.session().ttl()));
        userSessionDao.insert(session);
        return issueAuthentication(user, session.getId(), refreshToken);
    }

    @Transactional
    public AuthenticationResult refresh(String refreshToken) {
        if (refreshToken == null || refreshToken.isBlank()) {
            throw ApiException.unauthorized("缺少刷新令牌");
        }

        String oldHash = hashToken(refreshToken);
        UserSession session = userSessionDao.findByRefreshTokenHash(oldHash);
        Instant now = Instant.now();
        if (session == null || session.getRevokedAt() != null || !session.getExpiresAt().isAfter(now)) {
            throw ApiException.unauthorized("刷新令牌无效或已过期");
        }

        User user = userDao.findById(session.getUserId());
        if (user == null || !user.isEnabled()) {
            throw ApiException.unauthorized("用户不存在或已被禁用");
        }

        String newRefreshToken = newRefreshToken();
        int updated = userSessionDao.rotateRefreshToken(
                session.getId(),
                oldHash,
                hashToken(newRefreshToken),
                now
        );
        if (updated != 1) {
            throw ApiException.unauthorized("刷新令牌已被使用");
        }
        return issueAuthentication(user, session.getId(), newRefreshToken);
    }

    @Transactional(readOnly = true)
    public UserDTO validateSession(Long userId, String sessionId) {
        User user = userSessionDao.findAuthenticatedUser(sessionId, userId, Instant.now());
        if (user == null) {
            throw ApiException.unauthorized("登录会话无效或已撤销");
        }
        return UserDTO.from(user);
    }

    @Transactional
    public void logout(Long userId, String sessionId) {
        userSessionDao.revoke(sessionId, userId, Instant.now());
    }

    @Transactional
    public void logoutAll(Long userId) {
        userSessionDao.revokeAllByUserId(userId, Instant.now());
    }

    private AuthenticationResult issueAuthentication(
            User user,
            String sessionId,
            String refreshToken
    ) {
        String accessToken = jwtService.createAccessToken(user.getId(), sessionId);
        LoginResponse response = new LoginResponse(
                accessToken,
                "Bearer",
                jwtService.accessTokenTtlSeconds(),
                UserDTO.from(user)
        );
        return new AuthenticationResult(response, refreshToken);
    }

    private String newRefreshToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static String hashToken(String token) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw ApiException.badRequest("密码的 UTF-8 编码不能超过 72 字节");
        }
    }

    public record AuthenticationResult(LoginResponse response, String refreshToken) {
    }
}
