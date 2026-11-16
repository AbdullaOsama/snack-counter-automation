package snackcounter.core;

/**
 * Someone tried to act on a refill they do not hold (or that is in the wrong state), e.g. a second
 * person accepting a refill that is already committed. The chat bot shows the message to the user.
 */
public class RefillConflictException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public RefillConflictException(String message) {
        super(message);
    }
}
