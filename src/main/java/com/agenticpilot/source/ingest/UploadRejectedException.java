package com.agenticpilot.source.ingest;

/** An uploaded file was refused. The message is a message-bundle key. */
public class UploadRejectedException extends RuntimeException {

    private final transient Object[] args;

    public UploadRejectedException(String messageKey, Object... args) {
        super(messageKey);
        this.args = args;
    }

    public Object[] getArgs() {
        return args;
    }
}
