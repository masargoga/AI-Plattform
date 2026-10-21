package com.agenticpilot.guardrail;

/** How long conversations are kept. NONE means they are not stored. */
public enum Retention {
    NONE(0), DAYS_30(30), DAYS_90(90), YEAR_1(365), UNLIMITED(-1);

    private final int days;

    Retention(int days) {
        this.days = days;
    }

    /** Number of days, 0 for not stored, -1 for unlimited. */
    public int days() {
        return days;
    }
}
