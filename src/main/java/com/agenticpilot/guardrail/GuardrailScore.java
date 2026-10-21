package com.agenticpilot.guardrail;

import java.util.Arrays;
import java.util.List;

/** Score out of 7: each recommended control that is met adds one. */
public record GuardrailScore(int met, List<Control> gaps) {

    public static final int TOTAL = Control.values().length;

    public enum Level { GREEN, AMBER, RED }

    public static GuardrailScore of(GuardrailSettings s) {
        List<Control> gaps = Arrays.stream(Control.values()).filter(c -> !isMet(c, s)).toList();
        return new GuardrailScore(TOTAL - gaps.size(), gaps);
    }

    public static boolean isMet(Control c, GuardrailSettings s) {
        return switch (c) {
            case PERSONAL_DATA -> s.personalData() != PersonalDataMode.ALLOW;
            case SPECIAL_DATA -> s.specialData() != SpecialDataMode.ALLOW;
            case REGION -> s.region() == Region.EU;
            case RETENTION -> s.retention() != Retention.UNLIMITED;
            case HIDDEN_INSTRUCTIONS -> s.hiddenInstructionProtection();
            case APPROVALS -> !s.approvals().isEmpty();
            case PURPOSE -> s.purpose() != null && !s.purpose().isBlank();
        };
    }

    /** Green at 7, amber at 5 or 6, red below 5. */
    public Level level() {
        return met == TOTAL ? Level.GREEN : met >= 5 ? Level.AMBER : Level.RED;
    }
}
