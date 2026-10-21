package com.agenticpilot.ui;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.agent.AgentStatus;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillService;
import com.agenticpilot.source.DataSourceService;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Emblem;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.SearchBox;
import com.agenticpilot.ui.components.Segmented;
import com.agenticpilot.ui.components.SwitchButton;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Section;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.server.VaadinSession;
import jakarta.annotation.security.RolesAllowed;
import java.util.List;

@Route(value = "agents", layout = MainLayout.class)
@PageTitle("AgenticPilot")
@RolesAllowed({"ADMIN", "AGENT_OWNER"})
public class AgentsView extends Div {

    private static final String BANNER_HIDDEN = "ap.banner.hidden";

    private final AgentService agents;
    private final SkillService skills;
    private final DataSourceService sources;

    private AgentStatus filter;
    private String query = "";
    private final Div grid = Dom.div("ap-grid");
    private final Div header = new Div();

    public AgentsView(AgentService agents, SkillService skills, DataSourceService sources) {
        this.agents = agents;
        this.skills = skills;
        this.sources = sources;
        addClassName("ap-page");

        add(header);
        if (!Boolean.TRUE.equals(VaadinSession.getCurrent().getAttribute(BANNER_HIDDEN))) {
            add(banner());
        }
        Segmented<AgentStatus> seg = new Segmented<>(List.of(
                new Segmented.Option<>(null, getTranslation("filter.all")),
                new Segmented.Option<>(AgentStatus.ACTIVE, getTranslation("filter.active")),
                new Segmented.Option<>(AgentStatus.PAUSED, getTranslation("filter.paused"))), null)
                .onChange(v -> { filter = v; refresh(); });
        add(Dom.div("ap-toolbar", seg, new SearchBox("agent-search", getTranslation("agents.search"),
                getTranslation("agents.search"), q -> { query = q; refresh(); })), grid);
        refresh();
    }

    private Section banner() {
        Section how = new Section();
        how.addClassName("ap-how");
        how.getElement().setAttribute("aria-label", getTranslation("agents.how.title"));
        NativeButton hide = Dom.button("ap-btn ap-ghost ap-sm", getTranslation("action.hide"), () -> {
            VaadinSession.getCurrent().setAttribute(BANNER_HIDDEN, true);
            how.removeFromParent();
        });
        how.add(Dom.div("ap-how-h", Dom.span(null, getTranslation("agents.how.title")), hide));
        com.vaadin.flow.component.html.OrderedList ol = new com.vaadin.flow.component.html.OrderedList();
        for (int i = 1; i <= 3; i++) {
            com.vaadin.flow.component.html.ListItem li = new com.vaadin.flow.component.html.ListItem();
            li.add(Dom.span(null, String.valueOf(i)));
            li.getChildren().findFirst().ifPresent(c -> c.getElement().setAttribute("style", "font:500 12px var(--ap-font-mono)"));
            li.add(Dom.span(null, getTranslation("agents.how." + i)));
            ol.add(li);
        }
        how.add(ol);
        return how;
    }

    private void refresh() {
        List<Agent> list = agents.search(filter, query);
        long active = agents.search(AgentStatus.ACTIVE, "").size();
        long total = agents.search(null, "").size();
        header.removeAll();
        header.addClassName("ap-ph");
        Div titles = new Div(new H1(getTranslation("nav.agents")),
                para("ap-sub", getTranslation("agents.sub", total, active)));
        header.add(titles, Dom.button("ap-btn ap-primary", VaadinIcon.PLUS, getTranslation("agents.new"), this::openNew));

        grid.removeAll();
        if (list.isEmpty()) {
            grid.add(Dom.div("ap-empty", Dom.span(null, getTranslation(total == 0 ? "agents.empty" : "agents.noMatch"))));
        }
        list.forEach(a -> grid.add(card(a)));
        NativeButton tile = Dom.button("ap-newtile", null, this::openNew);
        tile.add(Dom.icon(VaadinIcon.PLUS, 24), Dom.span(null, getTranslation("agents.create")));
        grid.add(tile);
    }

    private static Paragraph para(String classes, String text) {
        Paragraph p = new Paragraph(text);
        Dom.addClasses(p, classes);
        return p;
    }

    private void openNew() {
        new NewAgentSheet(agents, skills, sources).open();
    }

    private Div card(Agent a) {
        boolean on = a.getStatus() == AgentStatus.ACTIVE;
        Div card = Dom.div("ap-card ap-link ap-acard");
        card.getElement().setAttribute("tabindex", "0");
        card.getElement().setAttribute("role", "link");
        card.getElement().setAttribute("aria-label", getTranslation("agents.open", a.getName()));
        Runnable open = () -> UI.getCurrent().navigate(AgentView.class, new RouteParameters("agentId", a.getId().toString()));
        card.addClickListener(e -> open.run());
        card.getElement().addEventListener("keydown", e -> open.run()).setFilter("event.key === 'Enter'");

        Div id = Dom.div("ap-ac-id", new H3(a.getName()), para(null, a.getJob()));
        id.getChildren().findFirst().ifPresent(h -> h.getElement().getStyle().set("margin", "0"));
        Div top = Dom.div("ap-ac-top", new Emblem(a, null), id);
        if (agents.canEdit(a)) {
            SwitchButton sw = new SwitchButton(on, getTranslation(on ? "agents.pause" : "agents.activate", a.getName()), checked -> {
                Feedback.run(this, () -> agents.setStatus(a.getId(), checked ? AgentStatus.ACTIVE : AgentStatus.PAUSED));
                refresh();
            });
            sw.getElement().executeJs("this.addEventListener('click', e => e.stopPropagation()); this.addEventListener('keydown', e => e.stopPropagation())");
            top.add(sw);
        }
        card.add(top);

        List<Skill> sk = List.copyOf(a.getSkills());
        Div chips = Dom.div("ap-chips");
        sk.stream().sorted(java.util.Comparator.comparing(Skill::getName)).limit(3).forEach(s -> chips.add(Dom.span("ap-chip", s.getName())));
        if (sk.size() > 3) {
            chips.add(Dom.span("ap-chip", "+" + (sk.size() - 3)));
        }
        if (sk.isEmpty()) {
            chips.add(Dom.span("ap-hint", getTranslation("agents.noSkills")));
        }
        card.add(chips);

        Div foot = Dom.div("ap-ac-foot",
                Dom.span(null, Dom.icon(VaadinIcon.MAGIC, 15), Dom.span(null, getTranslation("count.skills", sk.size()))),
                Dom.span(null, Dom.icon(VaadinIcon.DATABASE, 15), Dom.span(null, getTranslation("count.sources", a.getSources().size()))));
        Div grow = new Div();
        grow.getStyle().set("flex", "1");
        Div dot = Dom.div(on ? "ap-dot" : "ap-dot ap-paused");
        foot.add(grow, Dom.span(null, dot, Dom.span(null, getTranslation(on ? "status.active" : "status.paused") + " · "
                + Labels.ago(this, a.getLastRun(), "time.never"))));
        card.add(foot);
        return card;
    }
}
