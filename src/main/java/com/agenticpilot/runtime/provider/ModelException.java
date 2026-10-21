package com.agenticpilot.runtime.provider;

/** A failed model call. The message never contains prompt text or the API key. */
public class ModelException extends RuntimeException {

    public ModelException(String message) {
        super(message);
    }

    public ModelException(String message, Throwable cause) {
        super(message, cause);
    }
}
