package com.agenticpilot.ui;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.skill.OutputFormat;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillService;
import com.agenticpilot.skill.SkillType;
import com.agenticpilot.skill.Tool;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Emblem;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.FormField;
import com.agenticpilot.ui.components.NativeTextArea;
import com.agenticpilot.ui.components.Segmented;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import jakarta.annotation.security.RolesAllowed;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Skill editor with the Try-it panel (running a skill needs the model, which arrives in M3). */
@Route(value = "skills/:skillId", layout = MainLayout.class)
@PageTitle("AgenticPilot")
@RolesAllowed({"ADMIN", "AGENT_OWNER"})
public class SkillView extends Div implements BeforeEnterObserver {

    private enum Tab { EDIT, TRY }

    private final SkillService skills;
    private final AgentService agents;

    private UUID skillId;
    private Skill skill;
    private boolean editable;
    private Tab tab = Tab.EDIT;

    private Input name;
    private Input description;
    private NativeTextArea trigger;
    private NativeTextArea instructions;
    private Segmented<SkillType> type;
    private Segmented<OutputFormat> output;
    private final Set<Tool> tools = EnumSet.noneOf(Tool.class);
    private Div layout;

    public SkillView(SkillService skills, AgentService agents) {
        this.skills = skills;
        this.agents = agents;
        addClassName("ap-page");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        try {
            skillId = UUID.fromString(event.getRouteParameters().get("skillId").orElse(""));
            skill = skills.get(skillId);
        } catch (IllegalArgumentException | com.agenticpilot.common.NotFoundException e) {
            event.rerouteToError(com.vaadin.flow.router.NotFoundException.class);
            return;
        }
        build();
    }

    private void reload() {
        skill = skills.get(skillId);
        build();
    }

    private void build() {
        removeAll();
        editable = skills.canEdit(skill);
        tools.clear();
        tools.addAll(skill.getTools());
        List<Agent> users = agents.usingSkill(skillId);

        add(Dom.button("ap-back", VaadinIcon.ANGLE_LEFT, getTranslation("nav.skills"), () -> UI.getCurrent().navigate(SkillsView.class)));

        name = FormField.textInput("skill-name", skill.getName(), SkillService.NAME_MAX);
        name.addClassName("ap-title-input");
        name.getElement().setAttribute("aria-label", getTranslation("skill.name"));
        name.setEnabled(editable);
        Paragraph sub = new Paragraph(getTranslation("skill.version", skill.getRevision()) + " · "
                + (users.isEmpty() ? getTranslation("skills.unused") : getTranslation("skills.usedBy", users.size()))
                + " · " + getTranslation("skill.createdBy", skill.getCreatedBy().getDisplayName()));
        sub.addClassName("ap-sub");
        Span emb = new Span(Dom.icon(VaadinIcon.MAGIC, 26));
        emb.addClassNames("ap-emb", "ap-lg", "ap-sk");
        Div actions = Dom.div("ap-ah-actions");
        if (editable) {
            actions.add(Dom.button("ap-btn ap-primary", VaadinIcon.CHECK, getTranslation("skill.save"), this::save));
        }
        add(Dom.div("ap-ah", emb, Dom.div("ap-ah-main", name, sub), actions));
        if (!editable) {
            add(Dom.div("ap-readonly-note", Dom.span(null, getTranslation("skill.readonly", skill.getCreatedBy().getDisplayName()))));
        }

        Segmented<Tab> tabs = new Segmented<>(List.of(new Segmented.Option<>(Tab.EDIT, getTranslation("skill.tab.edit")),
                new Segmented.Option<>(Tab.TRY, getTranslation("skill.tab.try"))), tab).onChange(t -> {
            tab = t;
            layout.getElement().setAttribute("data-tab", t == Tab.TRY ? "test" : "config");
        });
        add(Dom.div("ap-mtabs", tabs));

        layout = Dom.div("ap-ag-layout", editColumn(), sideColumn(users));
        layout.getElement().setAttribute("data-tab", tab == Tab.TRY ? "test" : "config");
        add(layout);
    }

    private Div editColumn() {
        Paragraph typeHint = new Paragraph(Labels.hint(this, skill.getType()));
        typeHint.addClassName("ap-hint");
        type = new Segmented<>(Arrays.stream(SkillType.values()).map(t -> new Segmented.Option<>(t, Labels.of(this, t))).toList(), skill.getType());
        type.onChange(t -> typeHint.setText(Labels.hint(this, t)));
        type.setEnabled(editable);

        description = FormField.textInput("skill-desc", skill.getDescription(), 500);
        description.setEnabled(editable);
        trigger = new NativeTextArea("skill-trigger", skill.getTriggerText(), 2, 1000);
        trigger.setEnabled(editable);
        instructions = new NativeTextArea("skill-instructions", skill.getInstructions(), 9, 8000).asMono();
        instructions.setEnabled(editable);

        Div toolChips = Dom.div("ap-tchips");
        for (Tool t : Tool.values()) {
            NativeButton chip = Dom.button("ap-tchip", null, null);
            Runnable paint = () -> {
                boolean on = tools.contains(t);
                chip.setClassName("ap-on", on);
                chip.getElement().setAttribute("aria-pressed", String.valueOf(on));
                chip.removeAll();
                if (on) {
                    chip.add(Dom.icon(VaadinIcon.CHECK, 14));
                }
                chip.add(new com.vaadin.flow.component.html.Span(Labels.of(this, t)));
            };
            paint.run();
            chip.setEnabled(editable);
            chip.addClickListener(e -> {
                if (!tools.add(t)) {
                    tools.remove(t);
                }
                paint.run();
            });
            toolChips.add(chip);
        }
        output = new Segmented<>(Arrays.stream(OutputFormat.values()).map(o -> new Segmented.Option<>(o, Labels.of(this, o))).toList(), skill.getOutputFormat());
        output.setEnabled(editable);

        Div panel = Dom.div("ap-card ap-panel",
                new FormField(getTranslation("skill.type"), null, Dom.div(null, type), null),
                new FormField(getTranslation("skill.desc"), "skill-desc", description, getTranslation("skill.desc.hint")),
                new FormField(getTranslation("skill.trigger"), "skill-trigger", trigger, getTranslation("skill.trigger.hint")),
                new FormField(getTranslation("skill.instructions"), "skill-instructions", instructions, getTranslation("skill.instructions.hint")),
                new FormField(getTranslation("skill.tools"), null, toolChips, getTranslation("skill.tools.hint")),
                new FormField(getTranslation("skill.output"), null, Dom.div(null, output), null));
        panel.getChildren().findFirst().ifPresent(c -> ((Div) c).add(typeHint));
        return Dom.div("ap-col ap-config", panel);
    }

    private Div sideColumn(List<Agent> users) {
        NativeTextArea sample = new NativeTextArea("skill-try-input", "", 4, 4000);
        NativeButton run = Dom.button("ap-btn ap-primary", VaadinIcon.PLAY, getTranslation("skill.run"), null);
        run.setEnabled(false);
        Div tryPanel = Dom.div("ap-card ap-panel",
                Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("skill.try.title")), para(getTranslation("skill.try.sub")))),
                new FormField(getTranslation("skill.try.input"), "skill-try-input", sample, null), run,
                para("ap-coming", getTranslation("skill.try.later")));

        Div rows = Dom.div("ap-rows");
        for (Agent a : users) {
            NativeButton link = Dom.button("ap-linkish", a.getName(), () -> UI.getCurrent().navigate(AgentView.class,
                    new RouteParameters("agentId", a.getId().toString())));
            rows.add(Dom.div("ap-row", new Emblem(a, "sm"), Dom.div("ap-row-main", link)));
        }
        Div usedBy = Dom.div("ap-card ap-panel", new H2(getTranslation("skill.usedBy")),
                users.isEmpty() ? Dom.div("ap-rowempty", Dom.span(null, getTranslation("skill.usedBy.empty"))) : rows);
        return Dom.div("ap-col ap-test", tryPanel, usedBy);
    }

    private void save() {
        var content = new SkillService.Content(name.getValue(), type.getValue(), description.getValue(), trigger.getValue(),
                instructions.getValue(), Set.copyOf(tools), output.getValue());
        if (Feedback.run(this, () -> skills.update(skillId, content))) {
            Feedback.saved(this, "skill.saved");
            reload();
        }
    }

    private static Paragraph para(String text) {
        return para(null, text);
    }

    private static Paragraph para(String classes, String text) {
        Paragraph p = new Paragraph(text);
        Dom.addClasses(p, classes);
        return p;
    }
}
