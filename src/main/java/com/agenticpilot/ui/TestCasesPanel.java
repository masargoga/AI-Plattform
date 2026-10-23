package com.agenticpilot.ui;

import com.agenticpilot.runtime.BackgroundExecutor;
import com.agenticpilot.skill.CheckType;
import com.agenticpilot.skill.SkillCheck;
import com.agenticpilot.runtime.SkillRunService;
import com.agenticpilot.skill.SkillTestCase;
import com.agenticpilot.skill.SkillTestResult;
import com.agenticpilot.skill.SkillTestService;
import com.agenticpilot.skill.TestOutcome;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.FormField;
import com.agenticpilot.ui.components.NativeSelect;
import com.agenticpilot.ui.components.NativeTextArea;
import com.agenticpilot.ui.components.Sheet;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Test cases of a skill: sample input with checks on the answer, run against the draft or the approved version. */
class TestCasesPanel extends Div {

    private final UUID skillId;
    private final SkillTestService tests;
    private final BackgroundExecutor executor;
    private final BooleanSupplier draftMode;
    private final boolean editable;

    TestCasesPanel(UUID skillId, SkillTestService tests, BackgroundExecutor executor, BooleanSupplier draftMode, boolean editable) {
        this.skillId = skillId;
        this.tests = tests;
        this.executor = executor;
        this.draftMode = draftMode;
        this.editable = editable;
        addClassNames("ap-card", "ap-panel");
        refresh();
    }

    void refresh() {
        removeAll();
        Div head = Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("tests.title")), new Paragraph(getTranslation("tests.sub"))));
        List<SkillTestService.CaseView> cases = tests.cases(skillId, tests.currentVersionNumber(skillId, draftMode.getAsBoolean()));
        if (editable) {
            NativeButton run = Dom.button("ap-btn ap-sm ap-primary", VaadinIcon.PLAY, getTranslation("tests.runAll"), null);
            run.setEnabled(!cases.isEmpty());
            run.addClickListener(e -> runAll(run));
            add(head, Dom.div("ap-ah-actions", run, Dom.button("ap-btn ap-sm", VaadinIcon.PLUS, getTranslation("tests.add"), () -> openCase(null))));
        } else {
            add(head);
        }
        Div rows = Dom.div("ap-rows");
        for (SkillTestService.CaseView cv : cases) {
            SkillTestCase c = cv.testCase();
            SkillTestResult r = cv.latest();
            Div main = Dom.div("ap-row-main", Dom.span(null, c.getName()), Dom.span("ap-hint", summary(c)));
            main.getChildren().findFirst().ifPresent(x -> x.getElement().getStyle().set("font-weight", "600"));
            Div row = Dom.div("ap-row ap-cr", main);
            if (r != null) {
                Div result = Dom.div(null, Dom.span("ap-pill" + tone(r.getOutcome()), getTranslation("tests.outcome." + r.getOutcome().name())));
                row.add(result);
                if (!r.getDetail().isBlank()) {
                    main.add(Dom.span("ap-hint", r.getDetail()));
                }
            } else {
                row.add(Dom.span("ap-pill", getTranslation("tests.neverRun")));
            }
            if (editable) {
                row.add(Dom.iconButton("ap-iconbtn", VaadinIcon.EDIT, getTranslation("tests.edit", c.getName()), () -> openCase(c)));
                row.add(Dom.iconButton("ap-iconbtn", VaadinIcon.TRASH, getTranslation("tests.delete", c.getName()), () -> {
                    if (Feedback.run(this, () -> tests.deleteCase(c.getId()))) {
                        Feedback.saved(this, "tests.deleted");
                    }
                    refresh();
                }));
            }
            rows.add(row);
        }
        if (cases.isEmpty()) {
            rows.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("tests.empty"))));
        }
        add(rows);
    }

    private String summary(SkillTestCase c) {
        StringBuilder sb = new StringBuilder();
        for (SkillCheck check : c.getChecks()) {
            if (sb.length() > 0) {
                sb.append(" · ");
            }
            sb.append(getTranslation("tests.check." + check.type().name())).append(" “").append(check.value()).append('”');
        }
        return sb.toString();
    }

    private static String tone(TestOutcome o) {
        return switch (o) {
            case PASSED -> " ap-ok";
            case FAILED -> " ap-bad";
            case NOT_RUN -> " ap-warn";
        };
    }

    private void runAll(NativeButton run) {
        run.setEnabled(false);
        run.setText(getTranslation("tests.running"));
        UI ui = UI.getCurrent();
        boolean draft = draftMode.getAsBoolean();
        executor.submit(() -> {
            String error = null;
            try {
                tests.runAll(skillId, draft);
            } catch (com.agenticpilot.common.InvalidInputException ex) {
                error = ui.getTranslation(ex.getMessage(), ex.getArgs());
            } catch (RuntimeException ex) {
                error = ui.getTranslation("chat.error");
            }
            String shown = error;
            ui.access(() -> {
                if (shown != null) {
                    Feedback.error(shown);
                }
                refresh();
            });
        });
    }

    private void openCase(SkillTestCase existing) {
        Sheet sheet = new Sheet(getTranslation(existing == null ? "tests.add.title" : "tests.edit.title"), getTranslation("tests.add.sub"));
        Input name = FormField.textInput("tc-name", existing == null ? "" : existing.getName(), SkillTestService.NAME_MAX);
        NativeTextArea input = new NativeTextArea("tc-input", existing == null ? "" : existing.getInput(), 4, SkillRunService.MAX_INPUT_CHARS);
        List<NativeSelect> types = new ArrayList<>();
        List<Input> values = new ArrayList<>();
        List<SkillCheck> current = existing == null ? List.of() : existing.getChecks();
        Div checks = Dom.div("ap-sg");
        for (int i = 0; i < SkillTestService.MAX_CHECKS; i++) {
            SkillCheck c = i < current.size() ? current.get(i) : null;
            List<NativeSelect.Choice> choices = new ArrayList<>();
            choices.add(new NativeSelect.Choice("", getTranslation("tests.check.none")));
            for (CheckType t : CheckType.values()) {
                choices.add(new NativeSelect.Choice(t.name(), getTranslation("tests.check." + t.name())));
            }
            NativeSelect type = new NativeSelect("tc-type-" + i, choices, c == null ? "" : c.type().name());
            Input value = FormField.textInput("tc-value-" + i, c == null ? "" : c.value(), SkillCheck.MAX_VALUE);
            value.setPlaceholder(getTranslation("tests.check.placeholder"));
            types.add(type);
            values.add(value);
            checks.add(Dom.div("ap-check-row", type, value));
        }
        sheet.setBody(Dom.div("ap-sg",
                new FormField(getTranslation("tests.name"), "tc-name", name, null),
                new FormField(getTranslation("tests.input"), "tc-input", input, null),
                new FormField(getTranslation("tests.checks"), null, checks, getTranslation("tests.checks.hint"))));
        sheet.setFooter(Dom.button("ap-btn ap-ghost", getTranslation("action.cancel"), sheet::close),
                Dom.button("ap-btn ap-primary", getTranslation("action.save"), () -> {
                    List<SkillCheck> list = new ArrayList<>();
                    for (int i = 0; i < types.size(); i++) {
                        String t = types.get(i).getValue();
                        if (t != null && !t.isEmpty()) {
                            list.add(new SkillCheck(CheckType.valueOf(t), values.get(i).getValue() == null ? "" : values.get(i).getValue().strip()));
                        }
                    }
                    boolean ok = Feedback.run(sheet, () -> {
                        if (existing == null) {
                            tests.addCase(skillId, name.getValue(), input.getValue(), list);
                        } else {
                            tests.updateCase(existing.getId(), name.getValue(), input.getValue(), list);
                        }
                    });
                    if (ok) {
                        sheet.close();
                        Feedback.saved(this, "tests.saved");
                        refresh();
                    }
                }));
        sheet.open();
    }
}
