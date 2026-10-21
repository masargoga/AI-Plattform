package com.agenticpilot.ui;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.agent.AgentStatus;
import com.agenticpilot.agent.Autonomy;
import com.agenticpilot.agent.ModelTier;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.guardrail.AgentGuardrailService;
import com.agenticpilot.guardrail.CustomRuleService;
import com.agenticpilot.guardrail.WorkspacePolicyService;
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
                     CustomRuleService rules, WorkspacePolicyService policies, CurrentUser currentUser) {
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
        editable = agents.canEdit(agent);
        boolean on = agent.getStatus() == AgentStatus.ACTIVE;

        NativeButton back = Dom.button("ap-back", VaadinIcon.ANGLE_LEFT, getTranslation("nav.agents"), () -> UI.getCurrent().navigate(AgentsView.class));
        add(back);

        name = FormField.textInput("agent-name", agent.getName(), AgentService.NAME_MAX);
        name.addClassName("ap-title-input");
        name.getElement().setAttribute("aria-label", getTranslation("agent.name"));
        name.setEnabled(editable);
        String sub = agent.getRunCount() > 0
                ? getTranslation("agent.runs", agent.getRunCount(), Labels.ago(this, agent.getLastRun(), "time.never"))
                : getTranslation("agent.noRuns");
        sub += " · " + getTranslation("agent.owner", agent.getOwner().getDisplayName());
        Div main = Dom.div("ap-ah-main", name, para("ap-sub", sub));

        Div actions = Dom.div("ap-ah-actions");
        if (editable) {
            SwitchButton sw = new SwitchButton(on, getTranslation("agent.statusSwitch"), checked -> {
                if (Feedback.run(this, () -> agents.setStatus(agentId, checked ? AgentStatus.ACTIVE : AgentStatus.PAUSED))) {
                    Feedback.saved(this, checked ? "agent.activated" : "agent.paused");
                    reload();
                }
            });
            actions.add(Dom.span("ap-status-toggle", sw, Dom.span(null, getTranslation(on ? "status.active" : "status.paused"))));
            actions.add(Dom.button("ap-btn ap-primary", VaadinIcon.CHECK, getTranslation("action.save"), this::save));
        } else {
            actions.add(Dom.span("ap-status-toggle", Dom.span("ap-dot" + (on ? "" : " ap-paused")), Dom.span(null, getTranslation(on ? "status.active" : "status.paused"))));
        }
        add(Dom.div("ap-ah", new Emblem(agent, "lg"), main, actions));

        if (!editable) {
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

        instructions = new NativeTextArea("agent-instructions", agent.getInstructions(), 6, AgentService.INSTRUCTIONS_MAX);
        instructions.setEnabled(editable);
        job = FormField.textInput("agent-job", agent.getJob(), AgentService.JOB_MAX);
        job.setEnabled(editable);
        Paragraph autonomyHint = para("ap-hint", Labels.hint(this, agent.getAutonomy()));
        autonomy = new Segmented<>(Arrays.stream(Autonomy.values())
                .map(a -> new Segmented.Option<>(a, Labels.of(this, a))).toList(), agent.getAutonomy());
        autonomy.onChange(a -> autonomyHint.setText(Labels.hint(this, a)));
        autonomy.setEnabled(editable);
        model = new NativeSelect("agent-model", Arrays.stream(ModelTier.values())
                .map(m -> new NativeSelect.Choice(m.name(), Labels.of(this, m))).toList(), agent.getModelTier().name());
        model.setEnabled(editable);

        Div jobPanel = Dom.div("ap-card ap-panel",
                Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("agent.jobPanel")), para(null, getTranslation("agent.jobPanel.sub")))),
                new FormField(getTranslation("agent.jobLine"), "agent-job", job, null),
                new FormField(getTranslation("agent.instructions"), "agent-instructions", instructions, null),
                new FormField(getTranslation("agent.autonomy"), null, Dom.div("ap-field", autonomy, autonomyHint), null),
                new FormField(getTranslation("agent.model"), "agent-model", model, null));
        setup.add(jobPanel, skillsPanel(), sourcesPanel());

        Div config = Dom.div("ap-col ap-config", setup, guardColumn());
        return config;
    }

    private Div skillsPanel() {
        Div rows = Dom.div("ap-rows");
        List<Skill> list = agent.getSkills().stream().sorted(Comparator.comparing(Skill::getName)).toList();
        for (Skill s : list) {
            NativeButton open = Dom.button("ap-linkish", s.getName(), () -> UI.getCurrent().navigate(SkillView.class,
                    new RouteParameters("skillId", s.getId().toString())));
            Div main = Dom.div("ap-row-main", open, Dom.span("ap-hint", s.getDescription()));
            Div row = Dom.div("ap-row", Dom.span("ap-ico", Dom.icon(VaadinIcon.MAGIC, 18)), main,
                    Dom.span("ap-badge ap-t-" + capitalize(s.getType().name()), Labels.of(this, s.getType())));
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
        List<DataSource> list = agent.getSources().stream().sorted(Comparator.comparing(DataSource::getName)).toList();
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

    private void pickSkills() {
        var attached = agent.getSkills().stream().map(Skill::getId).collect(java.util.stream.Collectors.toSet());
        var items = skills.all().stream().filter(s -> !attached.contains(s.getId()))
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
        var attached = agent.getSources().stream().map(DataSource::getId).collect(java.util.stream.Collectors.toSet());
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
        return Dom.div("ap-col ap-test", Dom.div("ap-card ap-panel", new H2(getTranslation("agent.test.title", agent.getName())),
                para("ap-coming", getTranslation("agent.test.later"))));
    }

    private void save() {
        if (Feedback.run(this, () -> agents.update(agentId, name.getValue(), job.getValue(), instructions.getValue(),
                autonomy.getValue(), ModelTier.valueOf(model.getValue())))) {
            Feedback.saved(this, "saved");
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
