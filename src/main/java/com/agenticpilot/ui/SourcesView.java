package com.agenticpilot.ui;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceService;
import com.agenticpilot.source.SourceKind;
import com.agenticpilot.source.SyncStatus;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Emblem;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.FormField;
import com.agenticpilot.ui.components.Sheet;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import jakarta.annotation.security.RolesAllowed;
import java.time.Instant;
import java.util.List;

@Route(value = "sources", layout = MainLayout.class)
@PageTitle("AgenticPilot")
@RolesAllowed({"ADMIN", "AGENT_OWNER"})
public class SourcesView extends Div {

    private final DataSourceService sources;
    private final AgentService agents;
    private final Div list = Dom.div("ap-list");

    public SourcesView(DataSourceService sources, AgentService agents) {
        this.sources = sources;
        this.agents = agents;
        addClassName("ap-page");
        Paragraph sub = new Paragraph(getTranslation("sources.sub"));
        sub.addClassName("ap-sub");
        add(Dom.div("ap-ph", Dom.div(null, new H1(getTranslation("nav.sources")), sub),
                Dom.button("ap-btn ap-primary", VaadinIcon.PLUS, getTranslation("sources.connect"), this::openConnect)), list);
        refresh();
    }

    private void refresh() {
        list.removeAll();
        List<DataSource> all = sources.all();
        if (all.isEmpty()) {
            list.add(Dom.div("ap-empty", Dom.span(null, getTranslation("sources.empty"))));
        }
        all.forEach(s -> list.add(row(s)));
    }

    private Div row(DataSource s) {
        Div row = Dom.div("ap-card ap-srow");
        H3 title = new H3(s.getName());
        title.getStyle().set("margin", "0");
        Paragraph where = new Paragraph(Labels.of(this, s.getKind()) + " · " + s.getLocation());
        where.addClassNames("ap-hint");
        where.getStyle().set("margin", "0").set("overflow-wrap", "anywhere");
        row.add(Dom.span("ap-ico", Dom.icon(Labels.icon(s.getKind()), 18)), Dom.div(null, title, where));
        row.getChildren().skip(1).findFirst().ifPresent(c -> c.getElement().getStyle().set("min-width", "0"));

        Div side = Dom.div("ap-srow-side", Dom.span("ap-num", getTranslation("source.meta", s.getItemCount(), Labels.unit(this, s.getKind()))), status(s));
        List<Agent> users = agents.usingSource(s.getId());
        if (users.isEmpty()) {
            side.add(Dom.span("ap-hint", getTranslation("sources.unused")));
        } else {
            Div stack = Dom.div("ap-stack");
            users.stream().limit(4).forEach(a -> stack.add(new Emblem(a, "sm")));
            stack.getElement().setAttribute("title", String.join(", ", users.stream().map(Agent::getName).toList()));
            side.add(stack);
        }
        NativeButton sync = Dom.button("ap-btn ap-sm", VaadinIcon.REFRESH, getTranslation("sources.sync"), () -> {
            if (Feedback.run(this, () -> sources.sync(s.getId()))) {
                Feedback.saved(this, "sources.syncRecorded");
                refresh();
            }
        });
        sync.setEnabled(sources.canEdit(s) && s.getStatus() != SyncStatus.SYNCING);
        if (!sources.canEdit(s)) {
            sync.getElement().setAttribute("title", getTranslation("sources.syncDenied"));
        }
        side.add(sync);
        row.add(side);
        return row;
    }

    private com.vaadin.flow.component.html.Span status(DataSource s) {
        return switch (s.getStatus()) {
            case SYNCING -> Dom.span("ap-pill", Dom.span("ap-spin"), Dom.span(null, getTranslation("sources.status.syncing")));
            case ERROR -> Dom.span("ap-pill ap-bad", Dom.span(null, getTranslation("sources.status.error", s.getLastError() == null ? "" : s.getLastError())));
            case SYNCED -> {
                boolean stale = s.isStale(Instant.now());
                String text = s.getLastSynced() == null ? getTranslation("sources.status.never")
                        : getTranslation("sources.status.synced", Labels.ago(this, s.getLastSynced(), "time.never"));
                yield Dom.span("ap-pill " + (stale ? "ap-warn" : "ap-ok"), Dom.span(null, text));
            }
        };
    }

    private void openConnect() {
        Sheet sheet = new Sheet(getTranslation("sources.connect.title"), getTranslation("sources.connect.sub"));
        showKinds(sheet);
        sheet.open();
    }

    private void showKinds(Sheet sheet) {
        Div kinds = Dom.div("ap-kinds");
        for (SourceKind k : SourceKind.values()) {
            NativeButton b = Dom.button("ap-kind", null, () -> showDetails(sheet, k));
            b.add(Dom.span("ap-ico", Dom.icon(Labels.icon(k), 18)), Dom.span(null, Labels.of(this, k)), Dom.span(null, Labels.hint(this, k)));
            b.getChildren().skip(1).findFirst().ifPresent(c -> c.getElement().getStyle().set("font-weight", "600").set("font-size", "14px"));
            if (!k.isAvailable()) {
                b.setEnabled(false);
                b.add(Dom.span("ap-pill", Dom.span(null, getTranslation("sources.planned"))));
            }
            kinds.add(b);
        }
        sheet.setBody(kinds);
        sheet.setFooter(Dom.button("ap-btn ap-ghost", getTranslation("action.cancel"), sheet::close));
    }

    private void showDetails(Sheet sheet, SourceKind kind) {
        Input name = FormField.textInput("src-name", "", DataSourceService.NAME_MAX);
        Input location = FormField.textInput("src-location", "", 500);
        location.setPlaceholder(getTranslation("kind." + kind.name() + ".placeholder"));
        if (kind == SourceKind.WEBSITE) {
            location.setType("url");
        }
        Div form = Dom.div("ap-sg",
                new FormField(getTranslation("sources.name"), "src-name", name, null),
                new FormField(getTranslation(kind == SourceKind.WEBSITE ? "sources.address" : "sources.collection"), "src-location", location,
                        getTranslation(kind == SourceKind.WEBSITE ? "sources.address.hint" : "sources.collection.hint")),
                Dom.div("ap-hint", Dom.span(null, getTranslation("sources.readOnly"))));
        sheet.setBody(Dom.div(null, Dom.span("ap-badge ap-t-Instruction", Labels.of(this, kind))), form);
        sheet.setFooter(Dom.button("ap-btn ap-ghost", getTranslation("action.back"), () -> showKinds(sheet)),
                Dom.button("ap-btn ap-primary", getTranslation("sources.connect.action"), () -> {
                    if (Feedback.run(this, () -> sources.create(name.getValue(), kind, location.getValue()))) {
                        sheet.close();
                        Feedback.saved(this, "sources.connected");
                        refresh();
                    }
                }));
    }
}
