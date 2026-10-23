package com.agenticpilot.run;

/** Why a run ended. DONE and CANNOT_COMPLETE are the model's own words; the rest are the program stopping the run. */
public enum StopReason {
    DONE, CANNOT_COMPLETE, STEP_LIMIT, TIME_LIMIT, TOKEN_LIMIT, STOPPED_BY_USER, REFUSED_TOO_OFTEN, BLOCKED, DENIED, FAILED, INTERRUPTED
}
