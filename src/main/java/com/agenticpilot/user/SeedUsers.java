package com.agenticpilot.user;

import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** Creates the three dummy users on first start: one Agent owner and two Admins (for the four-eyes flow). */
@Component
class SeedUsers implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SeedUsers.class);

    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final SeedProperties seed;

    SeedUsers(AppUserRepository users, PasswordEncoder encoder, SeedProperties seed) {
        this.users = users;
        this.encoder = encoder;
        this.seed = seed;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        if (seed.password() == null || seed.password().isBlank()) {
            throw new IllegalStateException(
                    "No users exist yet. Set AGENTICPILOT_SEED_PASSWORD (agenticpilot.seed.password) to create the demo users.");
        }
        String hash = encoder.encode(seed.password());
        users.saveAll(List.of(
                new AppUser("owner", "Olivia Owner", hash, Role.AGENT_OWNER),
                new AppUser("admin", "Adam Admin", hash, Role.ADMIN),
                new AppUser("admin2", "Alex Admin", hash, Role.ADMIN)));
        log.info("Seeded 3 demo users (owner, admin, admin2)");
    }
}
