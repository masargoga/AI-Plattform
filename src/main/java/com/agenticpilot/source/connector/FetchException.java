package com.agenticpilot.source.connector;

/** A page could not be fetched safely. The message is safe to show to users. */
public class FetchException extends RuntimeException {

    public FetchException(String message) {
        super(message);
    }

    public FetchException(String message, Throwable cause) {
        super(message, cause);
    }
}
