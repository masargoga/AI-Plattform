package com.agenticpilot.ui;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentKind;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.api.ApiKeyService;
import com.agenticpilot.agent.AgentStatus;
import com.agenticpilot.agent.Autonomy;
import com.agenticpilot.agent.ModelTier;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.governance.ApprovalService;
import com.agenticpilot.governance.GovernanceWarnings;
import com.agenticpilot.governance.Lifecycle;
import com.agenticpilot.guardrail.AgentGuardrailService;
import com.agenticpilot.guardrail.CustomRuleService;
import com.agenticpilot.guardrail.WorkspacePolicyService;
import com.agenticpilot.run.RunService;
import com.agenticpilot.runtime.AgentChatService;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillService;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceService;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Emblem;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.FormField;
import com.agenticpilot.ui.components.NativeSelect;
import com.agenticpilot.ui.components.NativeTextArea;
import com.agenticpilot.ui.components.PickerSheet;
import com.agenticpilot.ui.components.Segmented;
import com.agenticpilot.ui.components.SwitchButton;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteAlias;
import com.vaadin.flow.router.RouteParameters;
import jakarta.annotation.security.RolesAllowed;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import com.agenticpilot.runtime.BackgroundExecutor;

/** Agent page: Configure, Guardrails and Test. The guardrails and test panels arrive in M2 and M3. */
@Route(value = "agents/:agentId", layout = MainLayout.class)
@RouteAlias(value = "agents/:agentId/guardrails", layout = MainLayout.class)
@PageTitle("AgenticPilot")
@RolesAllowed({"ADMIN", "AGENT_OWNER"})
public class AgentView extends Div implements BeforeEnterObserver {

    private enum Tab { CONFIG, GUARD, TEST }

    private final AgentService agents;
    private final SkillService skills;
    private final DataSourceService sources;
    private final AgentGuardrailService guardrails;
    private final CustomRuleService rules;
    private final WorkspacePolicyService policies;
    private final RuleSheets ruleSheets;
    private final AgentChatService chat;
    private final BackgroundExecutor executor;
    private final ApiKeyService apiKeys;
    private final ApprovalService approvals;
    private final RunService runService;
    private final GovernanceWarnings warnings;
    private final CurrentUser currentUser;

    private AgentService.Content cfg;
    private boolean canEditAgent;
    private java.util.Map<UUID, Skill> skillsById = java.util.Map.of();
    private java.util.Map<UUID, DataSource> sourcesById = java.util.Map.of();
    private RunPanel runPanel;
    private NativeTextArea goal;
    private Input inputLabel;
    private Segmented<Boolean> workMode;
    private boolean showRun;
    private AgentTestPanel testPanel;
    private UUID testPanelAgent;
    private UUID agentId;
    private Agent agent;
    private boolean editable;
    private Tab tab = Tab.CONFIG;

    private Input name;
    private Input job;
    private NativeTextArea instructions;
    private Segmented<Autonomy> autonomy;
    private NativeSelect model;
    private Div layout;
    private Segmented<Tab> mobileTabs;

    public AgentView(AgentService agents, SkillService skills, DataSourceService sources, AgentGuardrailService guardrails,
                     CustomRuleService rules, WorkspacePolicyService policies, CurrentUser currentUser, AgentChatService chat,
                     BackgroundExecutor executor, ApiKeyService apiKeys, ApprovalService approvals, GovernanceWarnings warnings, RunService runService) {
        this.runService = runService;
        this.approvals = approvals;
        this.warnings = warnings;
        this.currentUser = currentUser;
        this.apiKeys = apiKeys;
        this.chat = chat;
        this.executor = executor;
        this.agents = agents;
        this.skills = skills;
        this.sources = sources;
        this.guardrails = guardrails;
        this.rules = rules;
        this.policies = policies;
        this.ruleSheets = new RuleSheets(rules, agents, currentUser);
        addClassName("ap-page");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        try {
            agentId = UUID.fromString(event.getRouteParameters().get("agentId").orElse(""));
            agent = agents.get(agentId);
        } catch (IllegalArgumentException | com.agenticpilot.common.NotFoundException e) {
            event.rerouteToError(com.vaadin.flow.router.NotFoundException.class);
            return;
        }
        tab = event.getLocation().getPath().endsWith("/guardrails") ? Tab.GUARD : Tab.CONFIG;
        build();
    }

    private void reload() {
        agent = agents.get(agentId);
        build();
    }

    private void build() {
        removeAll();
        cfg = agents.editable(agentId);
        canEditAgent = agents.canEdit(agent);
        editable = canEditAgent && !LifecycleBar.contentLocked(agents, agent);
        skillsById = skills.all().stream().collect(java.util.stream.Collectors.toMap(Skill::getId, x -> x));
        sourcesById = sources.all().stream().collect(java.util.stream.Collectors.toMap(DataSource::getId, x -> x));
        boolean on = agent.getStatus() == AgentStatus.ACTIVE;

        NativeButton back = Dom.button("ap-back", VaadinIcon.ANGLE_LEFT, getTranslation("nav.agents"), () -> UI.getCurrent().navigate(AgentsView.class));
        add(back);

        name = FormField.textInput("agent-name", cfg.name(), AgentService.NAME_MAX);
        name.addClassName("ap-title-input");
        name.getElement().setAttribute("aria-label", getTranslation("agent.name"));
        name.setEnabled(editable);
        String sub = agent.getRunCount() > 0
                ? getTranslation("agent.runs", agent.getRunCount(), Labels.ago(this, agent.getLastRun(), "time.never"))
                : getTranslation("agent.noRuns");
        sub += " · " + getTranslation("agent.owner", agent.getOwner().getDisplayName());
        if (agent.getKind() == AgentKind.EXTERNAL) {
            sub += " · " + getTranslation("agent.kind.EXTERNAL");
        }
        Div main = Dom.div("ap-ah-main", name, para("ap-sub", sub));

        Div actions = Dom.div("ap-ah-actions");
        if (!agent.isUsable()) {
            actions.add(Dom.span("ap-pill" + LifecycleBar.statusTone(agent.getLifecycle()), getTranslation("lifecycle." + agent.getLifecycle().name())));
            if (editable) {
                actions.add(Dom.button("ap-btn ap-primary", VaadinIcon.CHECK, getTranslation("agent.saveDraft"), this::save));
            }
        } else if (canEditAgent) {
            SwitchButton sw = new SwitchButton(on, getTranslation("agent.statusSwitch"), checked -> {
                if (Feedback.run(this, () -> agents.setStatus(agentId, checked ? AgentStatus.ACTIVE : AgentStatus.PAUSED))) {
                    Feedback.saved(this, checked ? "agent.activated" : "agent.paused");
                    reload();
                }
            });
            actions.add(Dom.span("ap-status-toggle", sw, Dom.span(null, getTranslation(on ? "status.active" : "status.paused"))));
            if (editable) {
                actions.add(Dom.button("ap-btn ap-primary", VaadinIcon.CHECK, getTranslation("agent.saveDraft"), this::save));
            }
        } else {
            actions.add(Dom.span("ap-status-toggle", Dom.span("ap-dot" + (on ? "" : " ap-paused")), Dom.span(null, getTranslation(on ? "status.active" : "status.paused"))));
        }
        add(Dom.div("ap-ah", new Emblem(agent, "lg"), main, actions));
        add(new LifecycleBar(agents, agent, canEditAgent, currentUser.require().isAdmin(), currentUser.require().getId(), "agent",
                this::reload, () -> UI.getCurrent().navigate(AgentsView.class)));

        if (!canEditAgent) {
            add(Dom.div("ap-readonly-note", Dom.span(null, getTranslation("agent.readonly", agent.getOwner().getDisplayName()))));
        }

        mobileTabs = tabs(true);
        Segmented<Tab> desktop = tabs(false);
        add(Dom.div("ap-mtabs", mobileTabs), Dom.div("ap-dtabs", desktop));

        layout = Dom.div("ap-ag-layout", configColumn(), testColumn());
        applyTab();
        add(layout);
    }

    private Segmented<Tab> tabs(boolean withTest) {
        var options = withTest
                ? List.of(new Segmented.Option<>(Tab.CONFIG, getTranslation("tab.configure")),
                        new Segmented.Option<>(Tab.GUARD, getTranslation("tab.guardrails")),
                        new Segmented.Option<>(Tab.TEST, getTranslation("tab.test")))
                : List.of(new Segmented.Option<>(Tab.CONFIG, getTranslation("tab.configure")),
                        new Segmented.Option<>(Tab.GUARD, getTranslation("tab.guardrails")));
        Tab initial = !withTest && tab == Tab.TEST ? Tab.CONFIG : tab;
        return new Segmented<>(options, initial).onChange(t -> {
            tab = t;
            applyTab();
            String path = "agents/" + agentId + (t == Tab.GUARD ? "/guardrails" : "");
            UI.getCurrent().getPage().getHistory().replaceState(null, path);
        });
    }

    private void applyTab() {
        layout.getElement().setAttribute("data-tab", tab.name().toLowerCase());
    }

    private Div configColumn() {
        Div setup = Dom.div("ap-col ap-setup");

        instructions = new NativeTextArea("agent-instructions", cfg.instructions(), 6, AgentService.INSTRUCTIONS_MAX);
        instructions.setEnabled(editable);
        job = FormField.textInput("agent-job", cfg.job(), AgentService.JOB_MAX);
        job.setEnabled(editable);
        Paragraph autonomyHint = para("ap-hint", Labels.hint(this, cfg.autonomy()));
        autonomy = new Segmented<>(Arrays.stream(Autonomy.values())
                .map(a -> new Segmented.Option<>(a, Labels.of(this, a))).toList(), cfg.autonomy());
        autonomy.onChange(a -> autonomyHint.setText(Labels.hint(this, a)));
        autonomy.setEnabled(editable);
        model = new NativeSelect("agent-model", Arrays.stream(ModelTier.values())
                .map(m -> new NativeSelect.Choice(m.name(), Labels.of(this, m))).toList(), cfg.modelTier().name());
        model.setEnabled(editable);

        boolean external = agent.getKind() == AgentKind.EXTERNAL;
        goal = new NativeTextArea("agent-goal", cfg.goal(), 3, AgentService.GOAL_MAX);
        goal.setEnabled(editable);
        inputLabel = FormField.textInput("agent-input-label", cfg.inputLabel(), AgentService.INPUT_LABEL_MAX);
        inputLabel.setEnabled(editable);
        Div goalField = new FormField(getTranslation("agent.goal"), "agent-goal", goal, getTranslation("agent.goal.hint"));
        Div labelField = new FormField(getTranslation("agent.inputLabel"), "agent-input-label", inputLabel, getTranslation("agent.inputLabel.hint"));
        goalField.setVisible(agent.getKind() != AgentKind.EXTERNAL);
        labelField.setVisible(agent.getKind() != AgentKind.EXTERNAL);
        Div instructionsField = new FormField(getTranslation("agent.instructions"), "agent-instructions", instructions, null);
        Div autonomyField = new FormField(getTranslation("agent.autonomy"), null, Dom.div("ap-field", autonomy, autonomyHint), null);
        Div modelField = new FormField(getTranslation("agent.model"), "agent-model", model, null);
        instructionsField.setVisible(!external);
        autonomyField.setVisible(!external);
        modelField.setVisible(!external);
        Div jobPanel = Dom.div("ap-card ap-panel",
                Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("agent.jobPanel")), para(null, getTranslation("agent.jobPanel.sub")))),
                new FormField(getTranslation("agent.jobLine"), "agent-job", job, null),
                goalField, labelField, instructionsField, autonomyField, modelField);
        if (external) {
            setup.add(jobPanel);
        } else {
            for (String key : warnings.forAgent(cfg)) {
                setup.add(Dom.div("ap-banner ap-warn", Dom.span(null, getTranslation(key))));
            }
            setup.add(jobPanel, skillsPanel(), sourcesPanel(), limitsPanel());
        }
        if (canEditAgent) {
            setup.add(new ApiAccessPanel(agentId, agent.getKind(), apiKeys));
        }
        setup.add(new HistoryPanel(agents, approvals, ApprovalService.ItemType.AGENT, agentId));

        Div config = Dom.div("ap-col ap-config", setup, guardColumn());
        return config;
    }

    private Div skillsPanel() {
        Div rows = Dom.div("ap-rows");
        List<Skill> list = cfg.skillIds().stream().map(skillsById::get).filter(java.util.Objects::nonNull).sorted(Comparator.comparing(Skill::getName)).toList();
        for (Skill s : list) {
            NativeButton open = Dom.button("ap-linkish", s.getName(), () -> UI.getCurrent().navigate(SkillView.class,
                    new RouteParameters("skillId", s.getId().toString())));
            Div main = Dom.div("ap-row-main", open, Dom.span("ap-hint", s.getDescription()));
            Div row = Dom.div("ap-row", Dom.span("ap-ico", Dom.icon(VaadinIcon.MAGIC, 18)), main,
                    Dom.span("ap-badge ap-t-" + capitalize(s.getType().name()), Labels.of(this, s.getType())));
            if (!s.isUsable()) {
                row.add(Dom.span("ap-pill ap-warn", getTranslation("agent.skillNotAvailable")));
            }
            if (editable) {
                row.add(Dom.iconButton("ap-iconbtn", VaadinIcon.CLOSE_SMALL, getTranslation("action.remove", s.getName()), () -> {
                    if (Feedback.run(this, () -> agents.removeSkill(agentId, s.getId()))) {
                        Feedback.saved(this, "agent.skillRemoved");
                        reload();
                    }
                }));
            }
            rows.add(row);
        }
        if (list.isEmpty()) {
            rows.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("agent.noSkills"))));
        }
        Div head = Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("nav.skills")), para(null, getTranslation("agent.skills.sub"))));
        if (editable) {
            head.add(Dom.button("ap-btn ap-sm", VaadinIcon.PLUS, getTranslation("agent.addSkill"), this::pickSkills));
        }
        return Dom.div("ap-card ap-panel", head, rows);
    }

    private Div sourcesPanel() {
        Div rows = Dom.div("ap-rows");
        List<DataSource> list = cfg.sourceIds().stream().map(sourcesById::get).filter(java.util.Objects::nonNull).sorted(Comparator.comparing(DataSource::getName)).toList();
        for (DataSource s : list) {
            Div main = Dom.div("ap-row-main", Dom.span(null, s.getName()), Dom.span("ap-hint",
                    getTranslation("source.meta", s.getItemCount(), Labels.unit(this, s.getKind())) + " · "
                            + getTranslation("source.synced", Labels.ago(this, s.getLastSynced(), "time.never"))));
            main.getChildren().findFirst().ifPresent(c -> c.getElement().getStyle().set("font-weight", "600"));
            Div row = Dom.div("ap-row", Dom.span("ap-ico", Dom.icon(Labels.icon(s.getKind()), 18)), main);
            if (editable) {
                row.add(Dom.iconButton("ap-iconbtn", VaadinIcon.CLOSE_SMALL, getTranslation("action.remove", s.getName()), () -> {
                    if (Feedback.run(this, () -> agents.removeSource(agentId, s.getId()))) {
                        Feedback.saved(this, "agent.sourceRemoved");
                        reload();
                    }
                }));
            }
            rows.add(row);
        }
        if (list.isEmpty()) {
            rows.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("agent.noSources"))));
        }
        Div head = Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("nav.sources")), para(null, getTranslation("agent.sources.sub"))));
        if (editable) {
            head.add(Dom.button("ap-btn ap-sm", VaadinIcon.PLUS, getTranslation("agent.addSource"), this::pickSources));
        }
        return Dom.div("ap-card ap-panel", head, rows);
    }

    /** Limits of a run. They are not part of the approved version: they apply at once and are audited. */
    private Div limitsPanel() {
        Input steps = FormField.textInput("limit-steps", String.valueOf(agent.getMaxSteps()), 3);
        Input minutes = FormField.textInput("limit-minutes", String.valueOf(Math.max(1, agent.getMaxSeconds() / 60)), 3);
        Input tokens = FormField.textInput("limit-tokens", String.valueOf(agent.getMaxTokens()), 7);
        for (Input in : List.of(steps, minutes, tokens)) {
            in.setType("number");
            in.getElement().setAttribute("min", "1");
            in.setEnabled(canEditAgent);
        }
        Div panel = Dom.div("ap-card ap-panel",
                Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("agent.limits")), para(null, getTranslation("agent.limits.sub",
                        AgentService.RunLimitsCeiling.STEPS, AgentService.RunLimitsCeiling.SECONDS / 60, AgentService.RunLimitsCeiling.TOKENS)))),
                new FormField(getTranslation("agent.limits.steps"), "limit-steps", steps, null),
                new FormField(getTranslation("agent.limits.minutes"), "limit-minutes", minutes, null),
                new FormField(getTranslation("agent.limits.tokens"), "limit-tokens", tokens, null));
        if (canEditAgent) {
            panel.add(Dom.button("ap-btn ap-sm", getTranslation("agent.limits.save"), () -> {
                int s;
                int m;
                int t;
                try {
                    s = Integer.parseInt(steps.getValue().strip());
                    m = Integer.parseInt(minutes.getValue().strip());
                    t = Integer.parseInt(tokens.getValue().strip());
                } catch (NumberFormatException e) {
                    Feedback.error(getTranslation("error.invalidChoice"));
                    return;
                }
                if (Feedback.run(this, () -> agents.setLimits(agentId, s, m * 60, t))) {
                    Feedback.saved(this, "agent.limits.saved");
                    reload();
                }
            }));
        }
        return panel;
    }

    private void pickSkills() {
        var attached = cfg.skillIds();
        var items = skills.approved().stream().filter(s -> !attached.contains(s.getId()))
                .map(s -> new PickerSheet.Item(s.getId(), VaadinIcon.MAGIC, s.getName(), s.getDescription())).toList();
        new PickerSheet(getTranslation("agent.addSkill.title"), getTranslation("agent.addSkill.sub"), items,
                getTranslation("agent.addSkill.empty"), getTranslation("action.add"), ids -> {
            if (Feedback.run(this, () -> ids.forEach(id -> agents.addSkill(agentId, id)))) {
                Feedback.saved(this, "agent.skillAdded");
                reload();
            }
        }).open();
    }

    private void pickSources() {
        var attached = cfg.sourceIds();
        var items = sources.all().stream().filter(s -> !attached.contains(s.getId()))
                .map(s -> new PickerSheet.Item(s.getId(), Labels.icon(s.getKind()), s.getName(),
                        getTranslation("source.meta", s.getItemCount(), Labels.unit(this, s.getKind())))).toList();
        new PickerSheet(getTranslation("agent.addSource.title"), getTranslation("agent.addSource.sub"), items,
                getTranslation("agent.addSource.empty"), getTranslation("action.add"), ids -> {
            if (Feedback.run(this, () -> ids.forEach(id -> agents.addSource(agentId, id)))) {
                Feedback.saved(this, "agent.sourceAdded");
                reload();
            }
        }).open();
    }

    private Div guardColumn() {
        AgentGuardrailsPanel panel = new AgentGuardrailsPanel(agentId, agent.getName(), editable, guardrails, rules, policies, ruleSheets, () -> {
            tab = Tab.TEST;
            applyTab();
            mobileTabs.setValue(Tab.TEST);
        });
        panel.addClassName("ap-guard");
        return panel;
    }

    private Div testColumn() {
        if (agent.getKind() == AgentKind.EXTERNAL) {
            return Dom.div("ap-col ap-test", Dom.div("ap-card ap-panel", new H2(getTranslation("agent.external.title")),
                    para("ap-hint", getTranslation("agent.external.text"))));
        }
        if (testPanel == null || !agentId.equals(testPanelAgent)) {
            testPanel = new AgentTestPanel(agentId, cfg.name(), cfg.skillIds().size(), cfg.sourceIds().size(), chat, rules, executor);
            testPanelAgent = agentId;
        }
        boolean draftAvailable = canEditAgent && (agents.openVersion(agentId).isPresent() || !agent.isUsable());
        testPanel.setModes(agent.isUsable(), draftAvailable);
        if (runPanel == null || !agentId.equals(testPanelAgent)) {
            runPanel = new RunPanel(agentId, runService);
        }
        runPanel.configure(cfg.inputLabel(), cfg.goal(), canEditAgent, agent.isUsable(), draftAvailable);
        testPanel.setVisible(!showRun);
        runPanel.setVisible(showRun);
        workMode = new Segmented<>(List.of(new Segmented.Option<>(false, getTranslation("work.chat")), new Segmented.Option<>(true, getTranslation("work.run"))), showRun)
                .onChange(v -> {
                    showRun = v;
                    testPanel.setVisible(!v);
                    runPanel.setVisible(v);
                });
        return Dom.div("ap-col ap-test", Dom.div("ap-work-mode", workMode), testPanel, runPanel);
    }

    private void save() {
        if (Feedback.run(this, () -> agents.update(agentId, name.getValue(), job.getValue(), instructions.getValue(),
                autonomy.getValue(), ModelTier.valueOf(model.getValue()), goal.getValue(), inputLabel.getValue()))) {
            Feedback.saved(this, "agent.draftSaved");
            reload();
        }
    }

    private static Paragraph para(String classes, String text) {
        Paragraph p = new Paragraph(text);
        Dom.addClasses(p, classes);
        return p;
    }

    private static String capitalize(String enumName) {
        return enumName.charAt(0) + enumName.substring(1).toLowerCase();
    }
}
