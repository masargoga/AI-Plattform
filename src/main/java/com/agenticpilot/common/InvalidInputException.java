package com.agenticpilot.common;

/** A user input problem. The message is a message-bundle key so the UI can show it in the user's language. */
public class InvalidInputException extends RuntimeException {

    private final transient Object[] args;

    public InvalidInputException(String messageKey, Object... args) {
        super(messageKey);
        this.args = args;
    }

    public Object[] getArgs() {
        return args;
    }
}
