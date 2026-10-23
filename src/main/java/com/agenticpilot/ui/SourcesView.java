package com.agenticpilot.ui;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.source.DataSource;
import com.agenticpilot.source.DataSourceService;
import com.agenticpilot.source.OnFailure;
import com.agenticpilot.source.SourceKind;
import com.agenticpilot.source.TrustLevel;
import com.agenticpilot.ui.components.Segmented;
import com.agenticpilot.source.AllowedDomain;
import com.agenticpilot.source.AllowedDomainService;
import com.agenticpilot.source.SourceDocument;
import com.agenticpilot.source.SourceSyncService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.source.SyncStatus;
import com.agenticpilot.source.ingest.IngestionService;
import com.agenticpilot.source.ingest.TextExtractor;
import com.agenticpilot.source.ingest.UploadProperties;
import com.agenticpilot.source.ingest.UploadRejectedException;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Emblem;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.FormField;
import com.agenticpilot.ui.components.Sheet;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.server.streams.UploadHandler;
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
    private final IngestionService ingestion;
    private final UploadProperties uploadLimits;
    private final SourceSyncService syncService;
    private final AllowedDomainService allowedDomains;
    private final CurrentUser currentUser;
    private final Div list = Dom.div("ap-list");
    private final Div domainsCard = Dom.div("ap-card ap-panel");

    public SourcesView(DataSourceService sources, AgentService agents, IngestionService ingestion, UploadProperties uploadLimits,
                       SourceSyncService syncService, AllowedDomainService allowedDomains, CurrentUser currentUser) {
        this.syncService = syncService;
        this.allowedDomains = allowedDomains;
        this.currentUser = currentUser;
        this.sources = sources;
        this.agents = agents;
        this.ingestion = ingestion;
        this.uploadLimits = uploadLimits;
        addClassName("ap-page");
        Paragraph sub = new Paragraph(getTranslation("sources.sub"));
        sub.addClassName("ap-sub");
        add(Dom.div("ap-ph", Dom.div(null, new H1(getTranslation("nav.sources")), sub),
                Dom.button("ap-btn ap-primary", VaadinIcon.PLUS, getTranslation("sources.connect"), this::openConnect)), list, domainsCard);
        refresh();
    }

    private void refresh() {
        paintDomains();
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
        side.add(Dom.span("ap-pill" + (s.getTrustLevel() == TrustLevel.AUTHORITATIVE ? " ap-ok" : ""), getTranslation("trust." + s.getTrustLevel().name())));
        if (s.isReviewOverdue(java.time.LocalDate.now())) {
            Span overdue = Dom.span("ap-pill ap-warn", getTranslation("sources.reviewOverdue", s.getReviewBy().toString()));
            side.add(overdue);
        }
        side.add(Dom.button("ap-btn ap-sm", VaadinIcon.COG, getTranslation("sources.settings"), () -> openSettings(s)));
        if (s.getKind() == SourceKind.UPLOAD) {
            side.add(Dom.button("ap-btn ap-sm", VaadinIcon.UPLOAD, getTranslation("sources.files"), () -> openFiles(s)));
            row.add(side);
            return row;
        }
        NativeButton sync = Dom.button("ap-btn ap-sm", VaadinIcon.REFRESH, getTranslation("sources.sync"), () -> {
            UI ui = UI.getCurrent();
            if (Feedback.run(this, () -> syncService.start(s.getId(), done -> ui.access(this::refresh)))) {
                Feedback.saved(this, "sources.syncStarted");
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

    private void openSettings(DataSource s) {
        boolean canEdit = sources.canEdit(s);
        Sheet sheet = new Sheet(getTranslation("sources.settings.title", s.getName()), getTranslation("sources.settings.sub"));
        Input owner = FormField.textInput("src-owner", s.getOwnerName(), 80);
        owner.setEnabled(canEdit);
        Segmented<TrustLevel> trust = new Segmented<>(List.of(new Segmented.Option<>(TrustLevel.AUTHORITATIVE, getTranslation("trust.AUTHORITATIVE")),
                new Segmented.Option<>(TrustLevel.INFORMATIONAL, getTranslation("trust.INFORMATIONAL"))), s.getTrustLevel());
        trust.setEnabled(canEdit);
        Segmented<OnFailure> failure = new Segmented<>(List.of(new Segmented.Option<>(OnFailure.USE_LAST_COPY, getTranslation("onFailure.USE_LAST_COPY")),
                new Segmented.Option<>(OnFailure.STOP_AND_REPORT, getTranslation("onFailure.STOP_AND_REPORT"))), s.getOnFailure());
        failure.setEnabled(canEdit);
        Input maxAge = FormField.textInput("src-maxage", String.valueOf(s.getMaxAgeDays()), 4);
        maxAge.setType("number");
        maxAge.getElement().setAttribute("min", "1");
        maxAge.setEnabled(canEdit);
        Input reviewBy = new Input(com.vaadin.flow.data.value.ValueChangeMode.ON_CHANGE);
        reviewBy.setId("src-review");
        reviewBy.setType("date");
        reviewBy.setValue(s.getReviewBy() == null ? "" : s.getReviewBy().toString());
        reviewBy.setEnabled(canEdit);
        Input info = FormField.textInput("src-info", s.getVersionInfo(), 200);
        info.setEnabled(canEdit);
        sheet.setBody(Dom.div("ap-sg",
                new FormField(getTranslation("sources.owner"), "src-owner", owner, getTranslation("sources.owner.hint")),
                new FormField(getTranslation("sources.trust"), null, Dom.div(null, trust), getTranslation("sources.trust.hint")),
                new FormField(getTranslation("sources.onFailure"), null, Dom.div(null, failure), getTranslation("sources.onFailure.hint")),
                new FormField(getTranslation("sources.maxAge"), "src-maxage", maxAge, getTranslation("sources.maxAge.hint")),
                new FormField(getTranslation("sources.reviewBy"), "src-review", reviewBy, getTranslation("sources.reviewBy.hint")),
                new FormField(getTranslation("sources.versionInfo"), "src-info", info, getTranslation("sources.versionInfo.hint"))));
        if (!canEdit) {
            sheet.setFooter(Dom.button("ap-btn ap-primary", getTranslation("action.close"), sheet::close));
        } else {
            sheet.setFooter(Dom.button("ap-btn ap-ghost", getTranslation("action.cancel"), sheet::close),
                    Dom.button("ap-btn ap-primary", getTranslation("action.save"), () -> {
                        int days;
                        try {
                            days = Integer.parseInt(maxAge.getValue().strip());
                        } catch (NumberFormatException e) {
                            Feedback.error(getTranslation("error.source.maxAge"));
                            return;
                        }
                        java.time.LocalDate review;
                        try {
                            review = reviewBy.getValue() == null || reviewBy.getValue().isBlank() ? null : java.time.LocalDate.parse(reviewBy.getValue());
                        } catch (java.time.format.DateTimeParseException e) {
                            Feedback.error(getTranslation("error.invalidChoice"));
                            return;
                        }
                        if (Feedback.run(sheet, () -> sources.updateSettings(s.getId(), new DataSourceService.Settings(owner.getValue(), trust.getValue(),
                                failure.getValue(), days, review, info.getValue())))) {
                            sheet.close();
                            Feedback.saved(this, "sources.settings.saved");
                            refresh();
                        }
                    }));
        }
        sheet.open();
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

    // ----- allowed domains for website sources -----

    private void paintDomains() {
        boolean admin = currentUser.isAdmin();
        domainsCard.removeAll();
        domainsCard.getStyle().set("margin-top", "20px");
        domainsCard.add(Dom.div("ap-panel-h", Dom.div(null, new com.vaadin.flow.component.html.H2(getTranslation("domains.title")),
                para(getTranslation("domains.sub")))));
        Div rows = Dom.div("ap-rows");
        List<AllowedDomain> list = allowedDomains.all();
        if (list.isEmpty()) {
            rows.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation(admin ? "domains.empty.admin" : "domains.empty"))));
        }
        for (AllowedDomain d : list) {
            Span name = Dom.span(null, d.getDomain());
            name.getStyle().set("font-weight", "600");
            Div row = Dom.div("ap-row", Dom.span("ap-ico", Dom.icon(VaadinIcon.GLOBE, 18)), Dom.div("ap-row-main", name,
                    Dom.span("ap-hint", getTranslation("domains.addedBy", d.getAddedBy().getDisplayName()))));
            if (admin) {
                row.add(Dom.iconButton("ap-iconbtn", VaadinIcon.TRASH, getTranslation("action.remove", d.getDomain()), () -> {
                    if (Feedback.run(this, () -> allowedDomains.remove(d.getId()))) {
                        Feedback.saved(this, "domains.removed");
                        paintDomains();
                    }
                }));
            }
            rows.add(row);
        }
        domainsCard.add(rows);
        if (admin) {
            Input domain = FormField.textInput("allow-domain", "", 253);
            domain.setPlaceholder(getTranslation("domains.placeholder"));
            Div field = Dom.div("ap-field", domain);
            field.getStyle().set("flex", "1").set("min-width", "0");
            Div add = Dom.div(null, field, Dom.button("ap-btn", VaadinIcon.PLUS, getTranslation("domains.add"), () -> {
                if (Feedback.run(this, () -> allowedDomains.add(domain.getValue()))) {
                    Feedback.saved(this, "domains.added");
                    paintDomains();
                }
            }));
            add.getStyle().set("display", "flex").set("gap", "8px");
            domainsCard.add(add);
        } else {
            domainsCard.add(Dom.lock(getTranslation("domains.adminOnly")));
        }
    }

    private static Paragraph para(String text) {
        return new Paragraph(text);
    }

    // ----- files of an upload source -----

    private void openFiles(DataSource source) {
        Sheet sheet = new Sheet(getTranslation("files.title", source.getName()), getTranslation("files.sub"));
        boolean canEdit = sources.canEdit(source);
        Div docs = Dom.div("ap-rows");
        Div body = Dom.div("ap-sg");
        UI ui = UI.getCurrent();
        Runnable paint = new Runnable() {
            @Override
            public void run() {
                docs.removeAll();
                List<SourceDocument> list = ingestion.documents(source.getId());
                if (list.isEmpty()) {
                    docs.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("files.empty"))));
                }
                for (SourceDocument d : list) {
                    Span title = Dom.span(null, d.getTitle());
                    title.getStyle().set("font-weight", "600");
                    Div row = Dom.div("ap-row", Dom.span("ap-ico", Dom.icon(VaadinIcon.FILE_TEXT, 18)), Dom.div("ap-row-main", title));
                    if (canEdit) {
                        row.add(Dom.iconButton("ap-iconbtn", VaadinIcon.TRASH, getTranslation("action.remove", d.getTitle()), () -> {
                            if (Feedback.run(sheet, () -> ingestion.removeDocument(d.getId()))) {
                                Feedback.saved(sheet, "files.removed");
                                run();
                                refresh();
                            }
                        }));
                    }
                    docs.add(row);
                }
            }
        };
        paint.run();

        if (canEdit) {
            Upload upload = new Upload(UploadHandler.inMemory((metadata, data) -> {
                try {
                    ingestion.addFile(source.getId(), metadata.fileName(), data);
                    ui.access(() -> {
                        Feedback.saved(sheet, "files.added");
                        paint.run();
                        refresh();
                    });
                } catch (UploadRejectedException e) {
                    ui.access(() -> Feedback.error(metadata.fileName() + ": " + ui.getTranslation(e.getMessage(), e.getArgs())));
                } catch (RuntimeException e) {
                    ui.access(() -> Feedback.error(ui.getTranslation("error.denied")));
                }
            }));
            upload.setAcceptedFileTypes(".pdf", ".docx", ".xlsx", ".csv", ".txt", ".md");
            upload.setMaxFileSize((int) Math.min(uploadLimits.maxBytes(), Integer.MAX_VALUE));
            upload.setId("source-upload");
            Paragraph hint = new Paragraph(getTranslation("files.hint", uploadLimits.maxBytes() / (1024 * 1024)));
            hint.addClassName("ap-hint");
            body.add(upload, hint);
        } else {
            body.add(Dom.lock(getTranslation("files.readOnly")));
        }
        body.add(docs);
        sheet.setBody(body);
        sheet.setFooter(Dom.button("ap-btn ap-primary", getTranslation("action.close"), sheet::close));
        sheet.open();
    }
}
