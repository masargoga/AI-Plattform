package com.agenticpilot.user;

import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import java.util.Map;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.authentication.rememberme.PersistentTokenRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Lets a signed-in user change their own password. Other devices are signed out by deleting the user's remember-me tokens. */
@Service
public class AccountService {

    public static final int MIN_PASSWORD_LENGTH = 12;
    public static final int MAX_PASSWORD_LENGTH = 72; // BCrypt ignores everything after 72 bytes

    private final CurrentUser currentUser;
    private final AppUserRepository users;
    private final PasswordEncoder encoder;
    private final PersistentTokenRepository tokens;
    private final AuditService audit;

    public AccountService(CurrentUser currentUser, AppUserRepository users, PasswordEncoder encoder,
            PersistentTokenRepository tokens, AuditService audit) {
        this.currentUser = currentUser;
        this.users = users;
        this.encoder = encoder;
        this.tokens = tokens;
        this.audit = audit;
    }

    @Transactional
    public void changePassword(String current, String next, String confirmation) {
        AppUser me = currentUser.require();
        if (current == null || !encoder.matches(current, me.getPasswordHash())) {
            throw new InvalidInputException("account.password.error.current");
        }
        if (next == null || next.length() < MIN_PASSWORD_LENGTH || next.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_PASSWORD_LENGTH) {
            throw new InvalidInputException("account.password.error.length", MIN_PASSWORD_LENGTH, MAX_PASSWORD_LENGTH);
        }
        if (!next.equals(confirmation)) {
            throw new InvalidInputException("account.password.error.mismatch");
        }
        if (next.equals(current)) {
            throw new InvalidInputException("account.password.error.same");
        }
        me.changePasswordHash(encoder.encode(next));
        users.save(me);
        tokens.removeUserTokens(me.getUsername());
        audit.workspace("Password changed", AuditOutcome.ALLOWED, null, Map.of("user", me.getUsername()));
    }
}
