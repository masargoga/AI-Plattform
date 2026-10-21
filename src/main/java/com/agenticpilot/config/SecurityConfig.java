package com.agenticpilot.config;

import com.agenticpilot.ui.LoginView;
import com.vaadin.flow.spring.security.VaadinSecurityConfigurer;
import java.util.UUID;
import javax.sql.DataSource;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.RememberMeServices;
import org.springframework.security.web.authentication.rememberme.JdbcTokenRepositoryImpl;
import org.springframework.security.web.authentication.rememberme.PersistentTokenBasedRememberMeServices;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter;

/**
 * Web security for the UI. Signing in also creates a persistent token in the database (remember me), so any replica can
 * recognise the user when the replica that held the session is gone. The token is rotated on use, tied to a series that
 * is deleted at sign-out and when the password changes, and reuse of a stolen token is detected.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    /** Only needs to be the same inside one process: it signs the remember-me authentication token in memory. */
    private static final String REMEMBER_ME_KEY = UUID.randomUUID().toString();

    @Bean
    PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    PersistentTokenRepository persistentTokenRepository(DataSource dataSource) {
        JdbcTokenRepositoryImpl repository = new JdbcTokenRepositoryImpl();
        repository.setDataSource(dataSource);
        return repository;
    }

    @Bean
    RememberMeServices rememberMeServices(UserDetailsService users, PersistentTokenRepository tokens, AuthProperties props) {
        PersistentTokenBasedRememberMeServices services = new PersistentTokenBasedRememberMeServices(REMEMBER_ME_KEY, users, tokens);
        services.setAlwaysRemember(true);
        services.setTokenValiditySeconds(props.rememberMeHours() * 3600);
        services.setUseSecureCookie(props.secureCookies());
        services.setCookieName("ap-remember-me");
        return services;
    }

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, RememberMeServices rememberMeServices) throws Exception {
        http.authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/health", "/actuator/health/**", "/fonts/**", "/styles.css", "/app.css",
                        "/v3/api-docs", "/v3/api-docs/**").permitAll());
        http.rememberMe(rm -> rm.rememberMeServices(rememberMeServices).key(REMEMBER_ME_KEY));
        http.headers(h -> h
                .frameOptions(f -> f.sameOrigin())
                .referrerPolicy(r -> r.policy(ReferrerPolicyHeaderWriter.ReferrerPolicy.SAME_ORIGIN))
                .permissionsPolicyHeader(p -> p.policy("camera=(), microphone=(), geolocation=(), payment=()"))
                .contentSecurityPolicy(c -> c.policyDirectives("frame-ancestors 'self'; object-src 'none'; base-uri 'self'; form-action 'self'")));
        http.with(VaadinSecurityConfigurer.vaadin(), vaadin -> vaadin.loginView(LoginView.class));
        return http.build();
    }

    /**
     * Metrics and probes on a separate management port (AGENTICPILOT_MANAGEMENT_PORT) are open on that port only. The port is
     * not published by the Service or the Ingress, so only the cluster's monitoring can reach it. Everything else on it is denied.
     */
    @Bean
    @Order(0)
    SecurityFilterChain managementFilterChain(HttpSecurity http, Environment env) throws Exception {
        http.securityMatcher(request -> {
            Integer configured = env.getProperty("management.server.port", Integer.class);
            if (configured == null) {
                return false;
            }
            int port = configured > 0 ? configured : env.getProperty("local.management.port", Integer.class, -1);
            return request.getLocalPort() == port;
        }).csrf(csrf -> csrf.disable())
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info", "/actuator/prometheus").permitAll()
                        .anyRequest().denyAll());
        return http.build();
    }
}
