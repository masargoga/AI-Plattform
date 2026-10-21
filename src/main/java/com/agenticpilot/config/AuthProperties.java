package com.agenticpilot.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Sign-in settings. secureCookies must be true when the app is served over HTTPS (it is in the postgres profile). */
@ConfigurationProperties("agenticpilot.auth")
public record AuthProperties(
        @DefaultValue("12") int rememberMeHours,
        @DefaultValue("false") boolean secureCookies,
        @DefaultValue("5") int maxFailedLogins,
        @DefaultValue("15") int lockMinutes) {
}
