package com.agenticpilot;

import static org.assertj.core.api.Assertions.assertThat;

import com.agenticpilot.agent.Autonomy;
import com.agenticpilot.agent.ModelTier;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.compliance.RecordStatus;
import com.agenticpilot.compliance.SubjectKind;
import com.agenticpilot.guardrail.ApprovalAction;
import com.agenticpilot.guardrail.Control;
import com.agenticpilot.guardrail.PersonalDataMode;
import com.agenticpilot.guardrail.Region;
import com.agenticpilot.guardrail.Retention;
import com.agenticpilot.guardrail.RuleAction;
import com.agenticpilot.guardrail.SpecialDataMode;
import com.agenticpilot.skill.OutputFormat;
import com.agenticpilot.skill.SkillType;
import com.agenticpilot.skill.Tool;
import com.agenticpilot.source.SourceKind;
import com.agenticpilot.user.Role;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

/** Every text key used in the UI code, and every enum label, must exist in the message bundle. */
class MessageBundleTest {

    private static Properties bundle() throws IOException {
        Properties p = new Properties();
        try (InputStream in = MessageBundleTest.class.getResourceAsStream("/vaadin-i18n/translations.properties")) {
            p.load(new java.io.InputStreamReader(in, java.nio.charset.StandardCharsets.UTF_8));
        }
        return p;
    }

    @Test
    void literalKeysUsedInCodeExist() throws IOException {
        Properties p = bundle();
        Pattern key = Pattern.compile("\"((?:[a-z]+\\.)+[A-Za-z0-9_]+|saved)\"");
        List<String> missing = new ArrayList<>();
        try (Stream<Path> files = Files.walk(Path.of("src/main/java/com/agenticpilot/ui"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                String src = Files.readString(f);
                Matcher m = Pattern.compile("(?:getTranslation|Feedback\\.saved|Labels\\.ago)\\([^;]*?" + key.pattern()).matcher(src);
                while (m.find()) {
                    if (!p.containsKey(m.group(1))) {
                        missing.add(f.getFileName() + ": " + m.group(1));
                    }
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    @Test
    void enumLabelsExist() throws IOException {
        Properties p = bundle();
        List<String> missing = new ArrayList<>();
        for (SkillType v : SkillType.values()) check(p, missing, "skillType." + v, "skillType." + v + ".hint");
        for (OutputFormat v : OutputFormat.values()) check(p, missing, "output." + v);
        for (Tool v : Tool.values()) check(p, missing, "tool." + v);
        for (Autonomy v : Autonomy.values()) check(p, missing, "autonomy." + v, "autonomy." + v + ".hint");
        for (ModelTier v : ModelTier.values()) check(p, missing, "model." + v);
        for (Role v : Role.values()) check(p, missing, "role." + v);
        for (SourceKind v : SourceKind.values()) check(p, missing, "kind." + v, "kind." + v + ".hint", "kind." + v + ".placeholder", "unit." + v.unit());
        for (PersonalDataMode v : PersonalDataMode.values()) check(p, missing, "pii." + v);
        for (SpecialDataMode v : SpecialDataMode.values()) check(p, missing, "special." + v);
        for (Region v : Region.values()) check(p, missing, "region." + v);
        for (Retention v : Retention.values()) check(p, missing, "retention." + v);
        for (ApprovalAction v : ApprovalAction.values()) check(p, missing, "approval." + v);
        for (RuleAction v : RuleAction.values()) check(p, missing, "ruleAction." + v, "rules.message." + v);
        for (AuditOutcome v : AuditOutcome.values()) check(p, missing, "outcome." + v);
        for (Control v : Control.values()) check(p, missing, "control." + v);
        for (RecordStatus v : RecordStatus.values()) check(p, missing, "record." + v);
        for (SubjectKind v : SubjectKind.values()) check(p, missing, "request." + v);
        assertThat(missing).isEmpty();
    }

    @Test
    void errorKeysThrownByServicesExist() throws IOException {
        Properties p = bundle();
        List<String> missing = new ArrayList<>();
        Pattern key = Pattern.compile("InvalidInputException\\(\"([\\w.]+)\"");
        try (Stream<Path> files = Files.walk(Path.of("src/main/java"))) {
            for (Path f : files.filter(x -> x.toString().endsWith(".java")).toList()) {
                Matcher m = key.matcher(Files.readString(f));
                while (m.find()) {
                    if (!p.containsKey(m.group(1))) {
                        missing.add(f.getFileName() + ": " + m.group(1));
                    }
                }
            }
        }
        assertThat(missing).isEmpty();
    }

    private static void check(Properties p, List<String> missing, String... keys) {
        for (String k : keys) {
            if (!p.containsKey(k)) {
                missing.add(k);
            }
        }
    }
}
