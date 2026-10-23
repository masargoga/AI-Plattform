package com.agenticpilot.run;

import com.agenticpilot.common.InvalidInputException;

/** A run could not be started. The code is for the API, the message key for screens. */
public class RunRefusedException extends InvalidInputException {

    private final String code;

    public RunRefusedException(String code, Object... args) {
        super("run.error." + code, args);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
