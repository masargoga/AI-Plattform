package com.agenticpilot.api;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Authenticates API calls with a Bearer token, then applies the rate limit of the key. Failed attempts are limited per
 * client address, so a key cannot be guessed by trying many tokens. Not a Spring bean: it is added to the API filter chain only.
 */
final class ApiAuthenticationFilter extends OncePerRequestFilter {

    static final String REQUEST_ID = ApiAuthenticationFilter.class.getName() + ".requestId";
    static final String PRINCIPAL = ApiAuthenticationFilter.class.getName() + ".principal";
    static final String AUTHORITY = "ROLE_API";

    private final ApiKeyAuthenticator authenticator;
    private final RateLimiter keyLimiter;
    private final RateLimiter failureLimiter;
    private final long maxBodyBytes;

    ApiAuthenticationFilter(ApiKeyAuthenticator authenticator, RateLimiter keyLimiter, RateLimiter failureLimiter, long maxBodyBytes) {
        this.authenticator = authenticator;
        this.keyLimiter = keyLimiter;
        this.failureLimiter = failureLimiter;
        this.maxBodyBytes = maxBodyBytes;
    }

    /** Streaming answers finish in an async dispatch; the key must be known again there. */
    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        return false;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        if (request.getDispatcherType() == jakarta.servlet.DispatcherType.ASYNC && request.getAttribute(PRINCIPAL) instanceof ApiPrincipal known) {
            proceed(request, response, chain, known);
            return;
        }
        String requestId = UUID.randomUUID().toString();
        request.setAttribute(REQUEST_ID, requestId);
        response.setHeader("X-Request-Id", requestId);

        if (request.getContentLengthLong() > maxBodyBytes) {
            ApiErrorWriter.write(request, response, 413, "payload_too_large", "The request body is too large.");
            return;
        }
        String token = bearer(request.getHeader("Authorization"));
        Optional<ApiPrincipal> principal = token == null ? Optional.empty() : authenticator.authenticate(token);
        if (principal.isEmpty()) {
            RateLimiter.Result attempt = failureLimiter.tryAcquire(request.getRemoteAddr());
            if (!attempt.allowed()) {
                response.setHeader("Retry-After", String.valueOf(attempt.retryAfterSeconds()));
                ApiErrorWriter.write(request, response, 429, "rate_limited", "Too many failed attempts. Try again later.");
                return;
            }
            response.setHeader("WWW-Authenticate", "Bearer");
            ApiErrorWriter.write(request, response, 401, "unauthorized", "The API key is missing or not valid.");
            return;
        }
        RateLimiter.Result limit = keyLimiter.tryAcquire(principal.get().keyId().toString());
        response.setHeader("X-RateLimit-Limit", String.valueOf(limit.limit()));
        response.setHeader("X-RateLimit-Remaining", String.valueOf(limit.remaining()));
        if (!limit.allowed()) {
            response.setHeader("Retry-After", String.valueOf(limit.retryAfterSeconds()));
            ApiErrorWriter.write(request, response, 429, "rate_limited", "Rate limit exceeded. Try again in " + limit.retryAfterSeconds() + " seconds.");
            return;
        }
        request.setAttribute(PRINCIPAL, principal.get());
        proceed(request, response, chain, principal.get());
    }

    private static void proceed(HttpServletRequest request, HttpServletResponse response, FilterChain chain, ApiPrincipal principal)
            throws IOException, ServletException {
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(new UsernamePasswordAuthenticationToken(principal, null, List.of(new SimpleGrantedAuthority(AUTHORITY))));
        SecurityContextHolder.setContext(context);
        try {
            chain.doFilter(request, response);
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private static String bearer(String header) {
        if (header == null || header.length() < 8 || !header.regionMatches(true, 0, "Bearer ", 0, 7)) {
            return null;
        }
        return header.substring(7).strip();
    }
}
