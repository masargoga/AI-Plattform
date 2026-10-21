package com.agenticpilot.ui;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentKind;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillService;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceService;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.FormField;
import com.agenticpilot.ui.components.Segmented;
import com.agenticpilot.ui.components.Sheet;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.icon.VaadinIcon;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Three steps: template, name and job; data sources; skills. */
class NewAgentSheet extends Sheet {

    private final AgentService agents;
    private final SkillService skills;
    private final DataSourceService sources;

    private int step = 1;
    private AgentTemplate template = AgentTemplate.BLANK;
    private AgentKind kind = AgentKind.MANAGED;
    private final Input name = FormField.textInput("na-name", "", AgentService.NAME_MAX);
    private final Input job = FormField.textInput("na-job", "", AgentService.JOB_MAX);
    private final Set<UUID> sourceIds = new LinkedHashSet<>();
    private final Set<UUID> skillIds = new LinkedHashSet<>();
    private final List<Skill> allSkills;
    private final List<DataSource> allSources;

    NewAgentSheet(AgentService agents, SkillService skills, DataSourceService sources) {
        super(UI.getCurrent().getTranslation("agent.new.title"), UI.getCurrent().getTranslation("agent.new.subtitle"));
        this.agents = agents;
        this.skills = skills;
        this.sources = sources;
        this.allSkills = skills.all();
        this.allSources = sources.all();
        render();
    }

    private void render() {
        Div steps = Dom.div("ap-steps");
        int last = kind == AgentKind.EXTERNAL ? 1 : 3;
        for (int i = 1; i <= last; i++) {
            steps.getElement().appendChild(new com.vaadin.flow.dom.Element("i").setAttribute("class", i <= step ? "ap-on" : ""));
        }
        steps.getElement().setAttribute("aria-label", getTranslation("agent.new.step", step, last));
        Div content = switch (step) {
            case 1 -> stepOne();
            case 2 -> stepSources();
            default -> stepSkills();
        };
        setBody(steps, content);

        NativeButton cancel = Dom.button("ap-btn ap-ghost", getTranslation(step == 1 ? "action.cancel" : "action.back"),
                step == 1 ? this::close : () -> { step--; render(); });
        NativeButton next = step < last
                ? Dom.button("ap-btn ap-primary", getTranslation("action.next"), this::next)
                : Dom.button("ap-btn ap-primary", getTranslation("agent.new.create"), this::create);
        setFooter(cancel, next);
    }

    private Div stepOne() {
        Div tpls = Dom.div("ap-tpls");
        for (AgentTemplate t : AgentTemplate.values()) {
            NativeButton b = Dom.button("ap-tpl", null, () -> pick(t));
            b.add(Dom.span(null, getTranslation("agent.template." + t.key)),
                    Dom.span(null, getTranslation("agent.template." + t.key + ".hint")));
            b.getChildren().findFirst().ifPresent(c -> c.getElement().getStyle().set("font-weight", "600").set("font-size", "14px"));
            b.setClassName("ap-on", t == template);
            b.getElement().setAttribute("aria-pressed", String.valueOf(t == template));
            tpls.add(b);
        }
        Segmented<AgentKind> kindSeg = new Segmented<>(List.of(new Segmented.Option<>(AgentKind.MANAGED, getTranslation("agent.kind.MANAGED")),
                new Segmented.Option<>(AgentKind.EXTERNAL, getTranslation("agent.kind.EXTERNAL"))), kind);
        kindSeg.onChange(k -> {
            kind = k;
            String n = name.getValue();
            String j = job.getValue();
            render();
            name.setValue(n);
            job.setValue(j);
        });
        Div templates = new FormField(getTranslation("agent.template"), null, tpls, null);
        templates.setVisible(kind == AgentKind.MANAGED);
        return Dom.div("ap-sg",
                new FormField(getTranslation("agent.kind"), null, Dom.div(null, kindSeg), getTranslation("agent.kind." + kind.name() + ".hint")),
                templates,
                new FormField(getTranslation("agent.name"), "na-name", name, null),
                new FormField(getTranslation("agent.job"), "na-job", job, null));
    }

    private void pick(AgentTemplate t) {
        template = t;
        if (!t.name.isEmpty() && name.getValue().isBlank()) {
            name.setValue(t.name);
        }
        if (!t.job.isEmpty() && job.getValue().isBlank()) {
            job.setValue(t.job);
        }
        sourceIds.clear();
        skillIds.clear();
        allSources.stream().filter(s -> t.sourceNames.contains(s.getName())).forEach(s -> sourceIds.add(s.getId()));
        allSkills.stream().filter(s -> t.skillNames.contains(s.getName())).forEach(s -> skillIds.add(s.getId()));
        String n = name.getValue();
        String j = job.getValue();
        render();
        name.setValue(n);
        job.setValue(j);
    }

    private Div stepSources() {
        Div opts = Dom.div("ap-opts");
        if (allSources.isEmpty()) {
            opts.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("agent.new.noSources"))));
        }
        for (DataSource s : allSources) {
            opts.add(option(VaadinIcon.DATABASE, s.getName(), getTranslation("source.meta", s.getItemCount(),
                    Labels.unit(this, s.getKind())), sourceIds, s.getId()));
        }
        return Dom.div("ap-sg", new FormField(getTranslation("agent.new.sources"), null, opts, getTranslation("agent.new.sources.hint")));
    }

    private Div stepSkills() {
        Div opts = Dom.div("ap-opts");
        if (allSkills.isEmpty()) {
            opts.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("agent.new.noSkills"))));
        }
        for (Skill s : allSkills) {
            opts.add(option(VaadinIcon.MAGIC, s.getName(), s.getDescription(), skillIds, s.getId()));
        }
        return Dom.div("ap-sg", new FormField(getTranslation("agent.new.skills"), null, opts, getTranslation("agent.new.skills.hint")));
    }

    private NativeButton option(VaadinIcon icon, String title, String meta, Set<UUID> target, UUID id) {
        NativeButton opt = new NativeButton();
        opt.getElement().setAttribute("type", "button");
        opt.addClassName("ap-opt");
        opt.getElement().setAttribute("role", "checkbox");
        boolean on = target.contains(id);
        opt.setClassName("ap-on", on);
        opt.getElement().setAttribute("aria-checked", String.valueOf(on));
        Div tick = Dom.div("ap-tick");
        if (on) {
            tick.add(Dom.icon(VaadinIcon.CHECK, 14));
        }
        com.vaadin.flow.component.html.Span titleSpan = Dom.span(null, title);
        titleSpan.getStyle().set("font-weight", "600");
        opt.add(Dom.span("ap-ico", Dom.icon(icon, 18)), Dom.span("ap-opt-main", titleSpan, Dom.span("ap-hint", meta)), tick);
        opt.addClickListener(e -> {
            boolean now = target.add(id);
            if (!now) {
                target.remove(id);
            }
            opt.setClassName("ap-on", now);
            opt.getElement().setAttribute("aria-checked", String.valueOf(now));
            tick.removeAll();
            if (now) {
                tick.add(Dom.icon(VaadinIcon.CHECK, 14));
            }
        });
        return opt;
    }

    private void next() {
        if (step == 1 && name.getValue().isBlank()) {
            Feedback.error(getTranslation("error.nameRequired"));
            return;
        }
        step++;
        String n = name.getValue();
        String j = job.getValue();
        render();
        name.setValue(n);
        job.setValue(j);
    }

    private void create() {
        Agent[] created = new Agent[1];
        if (Feedback.run(this, () -> created[0] = agents.create(name.getValue(), job.getValue(), skillIds, sourceIds, kind))) {
            close();
            Feedback.saved(this, "agent.created");
            UI.getCurrent().navigate(AgentView.class, new com.vaadin.flow.router.RouteParameters("agentId", created[0].getId().toString()));
        }
    }
}
