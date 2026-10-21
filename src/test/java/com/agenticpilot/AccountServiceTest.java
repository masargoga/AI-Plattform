package com.agenticpilot;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.support.AsUser;
import com.agenticpilot.user.AccountService;
import com.agenticpilot.user.AppUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class AccountServiceTest {

    private static final String SEED = "test-only-password";

    @Autowired
    AccountService account;
    @Autowired
    AppUserRepository users;
    @Autowired
    PasswordEncoder encoder;
    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void restore() {
        // admin2 is the user this class changes; put the seed password back for the other tests.
        var u = users.findByUsername("admin2").orElseThrow();
        u.changePasswordHash(encoder.encode(SEED));
        users.save(u);
        AsUser.clear();
    }

    @Test
    void changesThePasswordAndSignsOtherDevicesOut() {
        AsUser.admin2();
        jdbc.update("insert into persistent_logins (username, series, token, last_used) values ('admin2', 'series-1', 'tok', current_timestamp)");
        account.changePassword(SEED, "a-brand-new-passphrase", "a-brand-new-passphrase");
        assertThat(encoder.matches("a-brand-new-passphrase", users.findByUsername("admin2").orElseThrow().getPasswordHash())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from persistent_logins where username = 'admin2'", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from audit_entry where event = 'Password changed'", Integer.class)).isPositive();
    }

    @Test
    void refusesWrongCurrentShortMismatchedAndUnchanged() {
        AsUser.admin2();
        assertThatThrownBy(() -> account.changePassword("wrong", "a-brand-new-passphrase", "a-brand-new-passphrase"))
                .isInstanceOf(InvalidInputException.class).hasMessage("account.password.error.current");
        assertThatThrownBy(() -> account.changePassword(SEED, "short", "short"))
                .isInstanceOf(InvalidInputException.class).hasMessage("account.password.error.length");
        assertThatThrownBy(() -> account.changePassword(SEED, "a-brand-new-passphrase", "another-passphrase-x"))
                .isInstanceOf(InvalidInputException.class).hasMessage("account.password.error.mismatch");
        assertThatThrownBy(() -> account.changePassword(SEED, SEED + "-x".repeat(0), SEED + "-x".repeat(0)))
                .isInstanceOf(InvalidInputException.class);
        assertThat(encoder.matches(SEED, users.findByUsername("admin2").orElseThrow().getPasswordHash())).isTrue();
    }

    @Test
    void needsASignedInUser() {
        AsUser.clear();
        assertThatThrownBy(() -> account.changePassword(SEED, "a-brand-new-passphrase", "a-brand-new-passphrase"))
                .isInstanceOf(AccessDeniedException.class);
    }
}
