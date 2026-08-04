package mozhi.parseltongue.service;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.Decoders;
import io.jsonwebtoken.security.Keys;
import mozhi.parseltongue.config.AuthProperties;
import mozhi.parseltongue.exception.ApiException;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.time.Instant;
import java.util.Date;

@Service
public class JwtService {

    private final AuthProperties properties;
    private final SecretKey signingKey;

    public JwtService(AuthProperties properties) {
        this.properties = properties;
        try {
            this.signingKey = Keys.hmacShaKeyFor(Decoders.BASE64.decode(properties.jwt().secret()));
        } catch (RuntimeException exception) {
            throw new IllegalStateException("auth.jwt.secret 必须是至少 32 字节的 Base64 密钥", exception);
        }
    }

    public String createAccessToken(Long userId, String sessionId) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.jwt().accessTokenTtl());
        return Jwts.builder()
                .subject(userId.toString())
                .claim("sid", sessionId)
                .issuedAt(Date.from(now))
                .expiration(Date.from(expiresAt))
                .signWith(signingKey, Jwts.SIG.HS256)
                .compact();
    }

    public JwtClaims parseAccessToken(String token) {
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            String subject = claims.getSubject();
            String sessionId = claims.get("sid", String.class);
            if (subject == null || sessionId == null || sessionId.isBlank()) {
                throw ApiException.unauthorized("无效的访问令牌");
            }
            return new JwtClaims(Long.valueOf(subject), sessionId);
        } catch (ApiException exception) {
            throw exception;
        } catch (JwtException | IllegalArgumentException exception) {
            throw ApiException.unauthorized("访问令牌无效或已过期");
        }
    }

    public long accessTokenTtlSeconds() {
        return properties.jwt().accessTokenTtl().toSeconds();
    }

    public record JwtClaims(Long userId, String sessionId) {
    }
}
