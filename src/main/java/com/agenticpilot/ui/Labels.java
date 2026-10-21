package com.agenticpilot.ui;

import com.agenticpilot.agent.Autonomy;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.guardrail.ApprovalAction;
import com.agenticpilot.guardrail.Control;
import com.agenticpilot.guardrail.GuardrailScore;
import com.agenticpilot.guardrail.PersonalDataMode;
import com.agenticpilot.guardrail.Region;
import com.agenticpilot.guardrail.Retention;
import com.agenticpilot.guardrail.RuleAction;
import com.agenticpilot.guardrail.SpecialDataMode;
import com.agenticpilot.agent.ModelTier;
import com.agenticpilot.skill.OutputFormat;
import com.agenticpilot.skill.SkillType;
import com.agenticpilot.skill.Tool;
import com.agenticpilot.source.SourceKind;
import com.vaadin.flow.component.Component;
import com.vaadin.flow.component.icon.VaadinIcon;
import java.time.Duration;
import java.time.Instant;

/** Texts and icons for enum values, looked up in the message bundle. */
public final class Labels {

    private Labels() {
    }

    public static String of(Component c, SkillType v) { return c.getTranslation("skillType." + v.name()); }
    public static String hint(Component c, SkillType v) { return c.getTranslation("skillType." + v.name() + ".hint"); }
    public static String of(Component c, OutputFormat v) { return c.getTranslation("output." + v.name()); }
    public static String of(Component c, Tool v) { return c.getTranslation("tool." + v.name()); }
    public static String of(Component c, Autonomy v) { return c.getTranslation("autonomy." + v.name()); }
    public static String hint(Component c, Autonomy v) { return c.getTranslation("autonomy." + v.name() + ".hint"); }
    public static String of(Component c, ModelTier v) { return c.getTranslation("model." + v.name()); }
    public static String of(Component c, SourceKind v) { return c.getTranslation("kind." + v.name()); }
    public static String hint(Component c, SourceKind v) { return c.getTranslation("kind." + v.name() + ".hint"); }
    public static String unit(Component c, SourceKind v) { return c.getTranslation("unit." + v.unit()); }

    public static String of(Component c, PersonalDataMode v) { return c.getTranslation("pii." + v.name()); }
    public static String of(Component c, SpecialDataMode v) { return c.getTranslation("special." + v.name()); }
    public static String of(Component c, Region v) { return c.getTranslation("region." + v.name()); }
    public static String of(Component c, Retention v) { return c.getTranslation("retention." + v.name()); }
    public static String of(Component c, ApprovalAction v) { return c.getTranslation("approval." + v.name()); }
    public static String of(Component c, RuleAction v) { return c.getTranslation("ruleAction." + v.name()); }
    public static String of(Component c, AuditOutcome v) { return c.getTranslation("outcome." + v.name()); }
    public static String gap(Component c, Control v) { return c.getTranslation("control." + v.name()); }

    /** Pill colour class for a score. */
    public static String levelClass(GuardrailScore score) {
        return switch (score.level()) {
            case GREEN -> "ap-ok";
            case AMBER -> "ap-warn";
            case RED -> "ap-bad";
        };
    }

    public static String pillClass(RuleAction a) {
        return switch (a) {
            case BLOCK -> "ap-bad";
            case APPROVE -> "ap-warn";
            case MASK, NOTE -> "";
        };
    }

    public static String pillClass(AuditOutcome o) {
        return switch (o) {
            case ALLOWED -> "ap-ok";
            case MASKED -> "";
            case BLOCKED -> "ap-bad";
            case NEEDS_APPROVAL -> "ap-warn";
        };
    }

    public static VaadinIcon icon(SourceKind kind) {
        return switch (kind) {
            case WEBSITE -> VaadinIcon.GLOBE;
            case FOLDER -> VaadinIcon.FOLDER;
            case WIKI -> VaadinIcon.FILE_TEXT;
            case UPLOAD -> VaadinIcon.UPLOAD;
            case DATABASE -> VaadinIcon.DATABASE;
            case API -> VaadinIcon.CODE;
        };
    }

    /** "4 min ago", "2 h ago", "6 d ago"; null means it never happened. */
    public static String ago(Component c, Instant when, String neverKey) {
        if (when == null) {
            return c.getTranslation(neverKey);
        }
        Duration d = Duration.between(when, Instant.now());
        if (d.toMinutes() < 1) {
            return c.getTranslation("time.now");
        }
        if (d.toHours() < 1) {
            return c.getTranslation("time.minutesAgo", d.toMinutes());
        }
        if (d.toDays() < 1) {
            return c.getTranslation("time.hoursAgo", d.toHours());
        }
        return c.getTranslation("time.daysAgo", d.toDays());
    }
}
