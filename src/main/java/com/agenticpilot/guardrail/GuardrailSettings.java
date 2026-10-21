package com.agenticpilot.guardrail;

import java.util.Set;

/** A plain snapshot of an agent's built-in controls. Pure data, no database or UI dependency. */
public record GuardrailSettings(PersonalDataMode personalData, SpecialDataMode specialData, Region region, Retention retention,
                                boolean hiddenInstructionProtection, Set<ApprovalAction> approvals, String purpose) {
}
