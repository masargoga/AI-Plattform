package com.agenticpilot.config;

import com.agenticpilot.api.ApiProperties;
import com.agenticpilot.api.ApiSecurity;
import com.agenticpilot.api.ApiKeyAuthenticator;
import org.springframework.core.annotation.Order;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

/** The API has its own filter chain: no session, no CSRF, Bearer API keys only. The UI chain handles everything else. */
@Configuration
class ApiSecurityConfig {

    @Bean
    @Order(1)
    SecurityFilterChain apiFilterChain(HttpSecurity http, ApiKeyAuthenticator authenticator, ApiProperties properties) throws Exception {
        http.securityMatcher("/api/**")
                .csrf(csrf -> csrf.disable())
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .requestCache(c -> c.disable())
                .authorizeHttpRequests(a -> a.anyRequest().hasAuthority(ApiSecurity.AUTHORITY))
                .addFilterBefore(ApiSecurity.filter(authenticator, properties), AuthorizationFilter.class)
                .exceptionHandling(e -> e
                        .authenticationEntryPoint((req, res, ex) -> ApiSecurity.unauthorized(req, res))
                        .accessDeniedHandler((req, res, ex) -> ApiSecurity.forbidden(req, res)));
        return http.build();
    }
}
