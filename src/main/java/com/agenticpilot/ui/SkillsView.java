package com.agenticpilot.ui;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.skill.Skill;
import com.agenticpilot.skill.SkillService;
import com.agenticpilot.skill.SkillType;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Emblem;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.SearchBox;
import com.agenticpilot.ui.components.Segmented;
import com.agenticpilot.ui.components.Sheet;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import jakarta.annotation.security.RolesAllowed;
import java.util.List;

@Route(value = "skills", layout = MainLayout.class)
@PageTitle("AgenticPilot")
@RolesAllowed({"ADMIN", "AGENT_OWNER"})
public class SkillsView extends Div {

    private final SkillService skills;
    private final AgentService agents;

    private SkillType filter;
    private String query = "";
    private final Div grid = Dom.div("ap-grid");

    public SkillsView(SkillService skills, AgentService agents) {
        this.skills = skills;
        this.agents = agents;
        addClassName("ap-page");

        Div header = Dom.div("ap-ph", Dom.div(null, new H1(getTranslation("nav.skills")), para(getTranslation("skills.sub"))),
                Dom.button("ap-btn ap-primary", VaadinIcon.PLUS, getTranslation("skills.new"), this::openTemplates));
        List<Segmented.Option<SkillType>> options = new java.util.ArrayList<>();
        options.add(new Segmented.Option<>(null, getTranslation("filter.all")));
        for (SkillType t : SkillType.values()) {
            options.add(new Segmented.Option<>(t, Labels.of(this, t)));
        }
        Segmented<SkillType> seg = new Segmented<>(options, null).onChange(t -> { filter = t; refresh(); });
        add(header, Dom.div("ap-toolbar", seg, new SearchBox("skill-search", getTranslation("skills.search"),
                getTranslation("skills.search"), q -> { query = q; refresh(); })), grid);
        refresh();
    }

    private void refresh() {
        grid.removeAll();
        List<Skill> list = skills.search(filter, query);
        if (list.isEmpty()) {
            grid.add(Dom.div("ap-empty", Dom.span(null, getTranslation(skills.all().isEmpty() ? "skills.empty" : "skills.noMatch"))));
        }
        list.forEach(s -> grid.add(card(s)));
        NativeButton tile = Dom.button("ap-newtile", null, this::openTemplates);
        tile.add(Dom.icon(VaadinIcon.PLUS, 24), Dom.span(null, getTranslation("skills.define")));
        grid.add(tile);
    }

    private Div card(Skill s) {
        Div card = Dom.div("ap-card ap-link ap-scard");
        card.getElement().setAttribute("tabindex", "0");
        card.getElement().setAttribute("role", "link");
        card.getElement().setAttribute("aria-label", getTranslation("skills.edit", s.getName()));
        Runnable open = () -> UI.getCurrent().navigate(SkillView.class, new RouteParameters("skillId", s.getId().toString()));
        card.addClickListener(e -> open.run());
        card.getElement().addEventListener("keydown", e -> open.run()).setFilter("event.key === 'Enter'");

        H3 title = new H3(s.getName());
        title.getStyle().set("margin", "0");
        card.add(Dom.div("ap-sc-top", Dom.span("ap-badge ap-t-" + s.getType().name().charAt(0) + s.getType().name().substring(1).toLowerCase(),
                        Labels.of(this, s.getType())), Dom.span("ap-num", "v" + s.getRevision())),
                Dom.div(null, title, para(s.getDescription().isEmpty() ? getTranslation("skills.noDescription") : s.getDescription())));

        List<Agent> users = agents.usingSkill(s.getId());
        Div foot = Dom.div("ap-ac-foot");
        if (users.isEmpty()) {
            foot.add(Dom.span("ap-hint", getTranslation("skills.unused")));
        } else {
            Div stack = Dom.div("ap-stack");
            users.stream().limit(4).forEach(a -> stack.add(new Emblem(a, "sm")));
            foot.add(stack, Dom.span(null, getTranslation("skills.usedBy", users.size())));
        }
        card.add(foot);
        return card;
    }

    private void openTemplates() {
        Sheet sheet = new Sheet(getTranslation("skills.new.title"), getTranslation("skills.new.sub"));
        Div tpls = Dom.div("ap-tpls");
        for (SkillTemplate t : SkillTemplate.values()) {
            NativeButton b = Dom.button("ap-tpl", null, () -> {
                Skill[] created = new Skill[1];
                if (Feedback.run(this, () -> created[0] = skills.create(t.content(this::getTranslation)))) {
                    sheet.close();
                    Feedback.saved(this, "skill.created");
                    UI.getCurrent().navigate(SkillView.class, new RouteParameters("skillId", created[0].getId().toString()));
                }
            });
            Div label = Dom.div(null, Dom.span(null, getTranslation("skill.template." + t.key + ".label")));
            label.getChildren().findFirst().ifPresent(c -> c.getElement().getStyle().set("font-weight", "600").set("font-size", "14px"));
            b.add(label, Dom.span(null, getTranslation("skill.template." + t.key + ".hint")));
            tpls.add(b);
        }
        sheet.setBody(tpls);
        sheet.setFooter(Dom.button("ap-btn ap-ghost", getTranslation("action.cancel"), sheet::close));
        sheet.open();
    }

    private static Paragraph para(String text) {
        Paragraph p = new Paragraph(text);
        p.addClassName("ap-sub");
        return p;
    }
}
