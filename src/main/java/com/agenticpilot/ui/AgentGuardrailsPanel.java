package com.agenticpilot.ui;

import com.agenticpilot.guardrail.AgentGuardrail;
import com.agenticpilot.guardrail.AgentGuardrailService;
import com.agenticpilot.guardrail.ApprovalAction;
import com.agenticpilot.guardrail.Control;
import com.agenticpilot.guardrail.CustomRule;
import com.agenticpilot.guardrail.CustomRuleService;
import com.agenticpilot.guardrail.GuardrailScore;
import com.agenticpilot.guardrail.PersonalDataMode;
import com.agenticpilot.guardrail.Region;
import com.agenticpilot.guardrail.Retention;
import com.agenticpilot.guardrail.RuleScope;
import com.agenticpilot.guardrail.SpecialDataMode;
import com.agenticpilot.guardrail.WorkspacePolicy;
import com.agenticpilot.guardrail.WorkspacePolicyService;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.FormField;
import com.agenticpilot.ui.components.NativeTextArea;
import com.agenticpilot.ui.components.Segmented;
import com.agenticpilot.ui.components.SwitchButton;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/** The Guardrails tab of an agent: score, built-in controls and the custom rules that apply to the agent. */
class AgentGuardrailsPanel extends Div {

    private final UUID agentId;
    private final String agentName;
    private final boolean editable;
    private final AgentGuardrailService guardrails;
    private final CustomRuleService rules;
    private final WorkspacePolicyService policies;
    private final RuleSheets ruleSheets;
    private final Runnable showTestTab;

    AgentGuardrailsPanel(UUID agentId, String agentName, boolean editable, AgentGuardrailService guardrails, CustomRuleService rules,
                         WorkspacePolicyService policies, RuleSheets ruleSheets, Runnable showTestTab) {
        this.agentId = agentId;
        this.agentName = agentName;
        this.editable = editable;
        this.guardrails = guardrails;
        this.rules = rules;
        this.policies = policies;
        this.ruleSheets = ruleSheets;
        this.showTestTab = showTestTab;
        addClassNames("ap-col");
        refresh();
    }

    void refresh() {
        removeAll();
        AgentGuardrail g = guardrails.get(agentId);
        WorkspacePolicy pol = policies.current();
        GuardrailScore score = GuardrailScore.of(g.toSettings());
        add(checkPanel(score), purposePanel(g), personalPanel(g, pol), actionsPanel(g), locationPanel(g, pol), safetyPanel(g), customPanel());
    }

    /** Applies a change; on success confirms and redraws, on failure shows the message and redraws to the stored state. */
    private void apply(Runnable change) {
        if (Feedback.run(this, change)) {
            Feedback.saved(this, "saved");
        }
        refresh();
    }

    private Div card(String titleKey, String subKey, com.vaadin.flow.component.Component... content) {
        Div head = Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation(titleKey)),
                subKey == null ? new Div() : para(getTranslation(subKey))));
        Div card = Dom.div("ap-card ap-panel", head);
        card.add(content);
        return card;
    }

    private static Paragraph para(String text) {
        return new Paragraph(text);
    }

    private static Paragraph hint(String text) {
        Paragraph p = new Paragraph(text);
        p.addClassName("ap-hint");
        return p;
    }

    private Div checkPanel(GuardrailScore score) {
        Div head = Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("guard.check")),
                para(getTranslation("guard.check.sub", score.met(), GuardrailScore.TOTAL))),
                Dom.span("ap-pill " + Labels.levelClass(score), score.met() + "/" + GuardrailScore.TOTAL));
        Div meter = Dom.div("ap-meter " + Labels.levelClass(score));
        meter.getElement().setAttribute("aria-hidden", "true");
        for (int i = 0; i < GuardrailScore.TOTAL; i++) {
            meter.getElement().appendChild(new com.vaadin.flow.dom.Element("i").setAttribute("class", i < score.met() ? "ap-on" : ""));
        }
        Div card = Dom.div("ap-card ap-panel", head, meter);
        if (score.gaps().isEmpty()) {
            card.add(hint(getTranslation("guard.check.allOn")));
        } else {
            Div rows = Dom.div("ap-rows");
            for (Control c : score.gaps()) {
                Div main = Dom.div("ap-row-main", Dom.span(null, Labels.gap(this, c)));
                main.getChildren().findFirst().ifPresent(x -> x.getElement().getStyle().set("font-weight", "600"));
                Div row = Dom.div("ap-row", Dom.span("ap-ico", Dom.icon(VaadinIcon.WARNING, 18)), main);
                if (editable) {
                    row.add(c == Control.PURPOSE
                            ? Dom.button("ap-btn ap-sm", getTranslation("guard.writeIt"), () -> getElement().executeJs("document.getElementById('g-purpose')?.focus()"))
                            : Dom.button("ap-btn ap-sm", getTranslation("guard.fix"), () -> apply(() -> guardrails.fix(agentId, c))));
                }
                rows.add(row);
            }
            card.add(rows);
        }
        NativeButton test = Dom.button("ap-btn ap-sm ap-mob-only", VaadinIcon.PLAY, getTranslation("guard.testInChat"), showTestTab);
        card.add(test);
        return card;
    }

    private Div purposePanel(AgentGuardrail g) {
        NativeTextArea purpose = new NativeTextArea("g-purpose", g.getPurpose(), 3, AgentGuardrail.PURPOSE_MAX);
        purpose.getElement().setAttribute("placeholder", getTranslation("guard.purpose.placeholder"));
        purpose.setEnabled(editable);
        purpose.addValueChangeListener(e -> {
            if (e.isFromClient() && !e.getValue().equals(g.getPurpose())) {
                apply(() -> guardrails.setPurpose(agentId, e.getValue()));
            }
        });
        return card("guard.purpose", "guard.purpose.sub", new FormField(getTranslation("guard.purpose"), "g-purpose", purpose, null));
    }

    private Div personalPanel(AgentGuardrail g, WorkspacePolicy pol) {
        List<Segmented.Option<PersonalDataMode>> pii = new ArrayList<>(List.of(
                new Segmented.Option<>(PersonalDataMode.MASK, Labels.of(this, PersonalDataMode.MASK)),
                new Segmented.Option<>(PersonalDataMode.BLOCK, Labels.of(this, PersonalDataMode.BLOCK))));
        if (!pol.isPersonalDataOn()) {
            pii.add(new Segmented.Option<>(PersonalDataMode.ALLOW, Labels.of(this, PersonalDataMode.ALLOW)));
        }
        Segmented<PersonalDataMode> piiSeg = new Segmented<>(pii, g.getPersonalData());
        piiSeg.setEnabled(editable);
        piiSeg.onChange(v -> apply(() -> guardrails.setPersonalData(agentId, v)));

        List<Segmented.Option<SpecialDataMode>> sp = new ArrayList<>(List.of(
                new Segmented.Option<>(SpecialDataMode.BLOCK, Labels.of(this, SpecialDataMode.BLOCK)),
                new Segmented.Option<>(SpecialDataMode.REVIEW, Labels.of(this, SpecialDataMode.REVIEW))));
        if (!pol.isSpecialDataOn()) {
            sp.add(new Segmented.Option<>(SpecialDataMode.ALLOW, Labels.of(this, SpecialDataMode.ALLOW)));
        }
        Segmented<SpecialDataMode> spSeg = new Segmented<>(sp, g.getSpecialData());
        spSeg.setEnabled(editable);
        spSeg.onChange(v -> apply(() -> guardrails.setSpecialData(agentId, v)));

        Div piiField = new FormField(getTranslation("guard.pii.label"), null, Dom.div(null, piiSeg), getTranslation("guard.pii.hint"));
        if (pol.isPersonalDataOn()) {
            piiField.add(Dom.lock(getTranslation("guard.pii.lock")));
        }
        Div spField = new FormField(getTranslation("guard.special.label"), null, Dom.div(null, spSeg), getTranslation("guard.special.hint"));
        if (pol.isSpecialDataOn()) {
            spField.add(Dom.lock(getTranslation("guard.special.lock")));
        }
        return card("guard.personal", "guard.personal.sub", piiField, spField);
    }

    private Div actionsPanel(AgentGuardrail g) {
        Div chips = Dom.div("ap-tchips");
        for (ApprovalAction a : ApprovalAction.values()) {
            boolean on = g.getApprovals().contains(a);
            NativeButton chip = Dom.button("ap-tchip" + (on ? " ap-on" : ""), null, () -> apply(() -> guardrails.setApproval(agentId, a, !on)));
            chip.getElement().setAttribute("aria-pressed", String.valueOf(on));
            if (on) {
                chip.add(Dom.icon(VaadinIcon.CHECK, 14));
            }
            chip.add(new com.vaadin.flow.component.html.Span(Labels.of(this, a)));
            chip.setEnabled(editable);
            chips.add(chip);
        }
        Segmented<Integer> limit = new Segmented<>(List.of(new Segmented.Option<>(10, getTranslation("guard.limit.n", 10)),
                new Segmented.Option<>(50, getTranslation("guard.limit.n", 50)), new Segmented.Option<>(200, getTranslation("guard.limit.n", 200))),
                g.getMaxRecords());
        limit.setEnabled(editable);
        limit.onChange(v -> apply(() -> guardrails.setMaxRecords(agentId, v)));
        return card("guard.actions", "guard.actions.sub", chips,
                new FormField(getTranslation("guard.limit.label"), null, Dom.div(null, limit), getTranslation("guard.limit.hint")));
    }

    private Div locationPanel(AgentGuardrail g, WorkspacePolicy pol) {
        List<Segmented.Option<Region>> regions = new ArrayList<>(List.of(new Segmented.Option<>(Region.EU, Labels.of(this, Region.EU))));
        if (!pol.isEuOnlyOn()) {
            regions.add(new Segmented.Option<>(Region.ANY, Labels.of(this, Region.ANY)));
        }
        Segmented<Region> region = new Segmented<>(regions, g.getRegion());
        region.setEnabled(editable);
        region.onChange(v -> apply(() -> guardrails.setRegion(agentId, v)));
        Segmented<Retention> retention = new Segmented<>(Arrays.stream(Retention.values())
                .map(r -> new Segmented.Option<>(r, Labels.of(this, r))).toList(), g.getRetention());
        retention.setEnabled(editable);
        retention.onChange(v -> apply(() -> guardrails.setRetention(agentId, v)));

        Div regionField = new FormField(getTranslation("guard.region.label"), null, Dom.div(null, region), null);
        if (pol.isEuOnlyOn()) {
            regionField.add(Dom.lock(getTranslation("guard.region.lock")));
        }
        return card("guard.location", "guard.location.sub", regionField,
                new FormField(getTranslation("guard.retention.label"), null, Dom.div(null, retention), getTranslation("guard.retention.hint")));
    }

    private Div safetyPanel(AgentGuardrail g) {
        SwitchButton injection = new SwitchButton(g.isHiddenInstructionProtection(), getTranslation("guard.injection"),
                on -> apply(() -> guardrails.setHiddenInstructionProtection(agentId, on)));
        injection.setEnabled(editable);
        SwitchButton review = new SwitchButton(g.isHumanReview(), getTranslation("guard.review"),
                on -> apply(() -> guardrails.setHumanReview(agentId, on)));
        review.setEnabled(editable);
        SwitchButton audit = new SwitchButton(true, getTranslation("guard.audit"), null);
        audit.setEnabled(false);
        Div ctls = Dom.div("ap-ctls",
                Dom.control(getTranslation("guard.injection"), getTranslation("guard.injection.sub"), injection),
                Dom.control(getTranslation("guard.review"), getTranslation("guard.review.sub"), review),
                Dom.control(getTranslation("guard.audit"), getTranslation("guard.audit.sub"), audit));
        return card("guard.safety", null, ctls);
    }

    private Div customPanel() {
        List<CustomRule> mine = rules.forAgent(agentId);
        Div rows = Dom.div("ap-rows");
        for (CustomRule r : mine) {
            boolean pending = rules.pending(r.getId()).isPresent();
            NativeButton name = Dom.button("ap-linkish", r.getName(), () -> ruleSheets.open(r.getId(), this::refresh));
            Div row = Dom.div("ap-row ap-cr", Dom.span("ap-ico", Dom.icon(VaadinIcon.SHIELD, 18)),
                    Dom.div("ap-row-main", name, Dom.span("ap-hint", getTranslation("rules.versionCatches", r.getLiveVersion(), r.getCatches()))));
            if (r.getLiveVersion() == 0) {
                row.add(Dom.span("ap-pill ap-warn", getTranslation("rules.notLive")));
            } else if (!r.isEnabled()) {
                row.add(Dom.span("ap-pill", getTranslation("rules.off")));
            }
            if (pending && r.getLiveVersion() > 0) {
                row.add(Dom.span("ap-pill ap-warn", getTranslation("rules.changePending")));
            }
            row.add(Dom.span("ap-pill " + Labels.pillClass(r.getAction()), Labels.of(this, r.getAction())));
            if (r.getScope() == RuleScope.ALL) {
                row.add(Dom.span("ap-pill", getTranslation("rules.allAgents")));
            } else if (rules.canEdit(r) && !pending) {
                row.add(Dom.iconButton("ap-iconbtn", VaadinIcon.CLOSE_SMALL, getTranslation("rules.removeFromAgent", r.getName()), () -> {
                    if (Feedback.run(this, () -> rules.proposeAgentAssignment(r.getId(), agentId, false))) {
                        Feedback.saved(this, "rules.sentForApproval");
                    }
                    refresh();
                }));
            }
            rows.add(row);
        }
        if (mine.isEmpty()) {
            rows.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("rules.agentEmpty"))));
        }
        Div head = Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("rules.custom.title")), para(getTranslation("rules.custom.sub"))));
        if (editable) {
            head.add(Dom.button("ap-btn ap-sm", VaadinIcon.PLUS, getTranslation("rules.addGuardrail"),
                    () -> ruleSheets.openPicker(agentId, agentName, this::refresh)));
        }
        return Dom.div("ap-card ap-panel", head, rows);
    }
}
