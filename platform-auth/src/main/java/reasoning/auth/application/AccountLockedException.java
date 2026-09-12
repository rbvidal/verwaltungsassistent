package reasoning.auth.application;

import org.springframework.security.core.AuthenticationException;

/**
 * Thrown when the submitted credentials are VALID but the account is locked
 * or disabled. It is only raised after the password check succeeded, so the
 * blocked state is never revealed to someone who does not know the password.
 */
public class AccountLockedException extends AuthenticationException {

    public static final String MESSAGE =
            "Ihr Benutzerkonto wurde gesperrt. Bitte wenden Sie sich an die Administration.";

    public AccountLockedException() {
        super(MESSAGE);
    }
}
