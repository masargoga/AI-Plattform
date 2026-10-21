package com.agenticpilot.guardrail;

import com.agenticpilot.audit.AuditOutcome;
import java.util.List;

/** Result of checking a request before the model is called. */
public sealed interface InputResult {

    List<Decision> decisions();

    /**
     * The request stops here: the model is never called. For BLOCKED the reply is what the agent says; for NEEDS_APPROVAL
     * the reply tells the user it waits for a person and pendingDescription is what the approver sees.
     */
    record Stop(AuditOutcome outcome, String reply, String pendingDescription, List<Decision> decisions) implements InputResult {
    }

    /** The request may go on with the (possibly masked) text. Notes are appended to the answer later. */
    record Proceed(String text, List<String> notes, List<Decision> decisions) implements InputResult {
    }
}
