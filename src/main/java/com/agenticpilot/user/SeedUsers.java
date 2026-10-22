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
@org.springframework.core.annotation.Order(10)
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

    private static String randomPassword() {
        String alphabet = "abcdefghijkmnpqrstuvwxyzABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        java.security.SecureRandom random = new java.security.SecureRandom();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 16; i++) {
            sb.append(alphabet.charAt(random.nextInt(alphabet.length())));
        }
        return sb.toString();
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (users.count() > 0) {
            return;
        }
        String password = seed.password();
        if (password == null || password.isBlank()) {
            if (!seed.generateIfMissing()) {
                throw new IllegalStateException(
                        "No users exist yet. Set AGENTICPILOT_SEED_PASSWORD (agenticpilot.seed.password) to create the demo users.");
            }
            password = randomPassword();
            // Printed to the console of whoever started the app, not written to the log
            System.out.println("\n=====================================================================\n"
                    + " No AGENTICPILOT_SEED_PASSWORD set: generated a password for this run.\n"
                    + " Users: owner, admin, admin2     Password: " + password + "\n"
                    + " (shown once; delete ./data to start over)\n"
                    + "=====================================================================\n");
        }
        String hash = encoder.encode(password);
        users.saveAll(List.of(
                new AppUser("owner", "Olivia Owner", hash, Role.AGENT_OWNER),
                new AppUser("admin", "Adam Admin", hash, Role.ADMIN),
                new AppUser("admin2", "Alex Admin", hash, Role.ADMIN)));
        log.info("Seeded 3 demo users (owner, admin, admin2)");
    }
}
