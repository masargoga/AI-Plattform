package com.agenticpilot.ui;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.audit.AuditEntry;
import com.agenticpilot.governance.ApprovalService;
import com.agenticpilot.runtime.BackgroundExecutor;
import com.agenticpilot.skill.SkillTestService;
import com.agenticpilot.ui.components.NativeSelect;
import com.agenticpilot.ui.components.SearchBox;
import com.vaadin.flow.component.html.Input;
import com.agenticpilot.audit.AuditOutcome;
import com.agenticpilot.audit.AuditService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.compliance.ComplianceRecord;
import com.agenticpilot.compliance.ComplianceService;
import com.agenticpilot.compliance.RecordStatus;
import com.agenticpilot.compliance.SubjectRequest;
import com.agenticpilot.guardrail.AgentGuardrailService;
import com.agenticpilot.guardrail.CustomRule;
import com.agenticpilot.guardrail.CustomRuleService;
import com.agenticpilot.guardrail.GuardrailScore;
import com.agenticpilot.guardrail.Retention;
import com.agenticpilot.guardrail.RuleVersion;
import com.agenticpilot.guardrail.WorkspacePolicy;
import com.agenticpilot.guardrail.WorkspacePolicyService;
import com.agenticpilot.guardrail.WorkspacePolicyVersion;
import com.agenticpilot.runtime.PendingAction;
import com.agenticpilot.runtime.PendingActionService;
import com.agenticpilot.runtime.PendingActionStatus;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Emblem;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.FormField;
import com.agenticpilot.ui.components.Segmented;
import com.agenticpilot.ui.components.SwitchButton;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.router.RouteParameters;
import com.vaadin.flow.server.StreamResource;
import jakarta.annotation.security.RolesAllowed;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Policies: workspace rules, custom rules, audit log and compliance records. One route per tab. */
@Route(value = "policies/:tab?", layout = MainLayout.class)
@PageTitle("AgenticPilot")
@RolesAllowed({"ADMIN", "AGENT_OWNER"})
public class PoliciesView extends Div implements BeforeEnterObserver {

    private enum Tab {
        RULES("rules"), CUSTOM("custom"), APPROVALS("approvals"), AUDIT("audit"), RECORDS("records");

        final String path;

        Tab(String path) {
            this.path = path;
        }

        static Tab of(String path) {
            return Arrays.stream(values()).filter(t -> t.path.equals(path)).findFirst().orElse(RULES);
        }
    }

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY_TIME = DateTimeFormatter.ofPattern("d MMM HH:mm", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final WorkspacePolicyService policies;
    private final AgentService agents;
    private final AgentGuardrailService guardrails;
    private final CustomRuleService rules;
    private final AuditService audit;
    private final ComplianceService compliance;
    private final CurrentUser currentUser;
    private final RuleSheets ruleSheets;
    private final PendingActionService pendingActions;
    private final ApprovalService approvalService;
    private final SkillTestService skillTests;
    private final BackgroundExecutor executor;

    private Tab tab = Tab.RULES;
    private AuditOutcome auditOutcome;
    private java.util.UUID auditAgent;
    private String auditText = "";
    private LocalDate auditFrom;
    private LocalDate auditTo;
    private final Div content = new Div();

    public PoliciesView(WorkspacePolicyService policies, AgentService agents, AgentGuardrailService guardrails, CustomRuleService rules,
                        AuditService audit, ComplianceService compliance, CurrentUser currentUser, PendingActionService pendingActions,
                        ApprovalService approvalService, SkillTestService skillTests, BackgroundExecutor executor) {
        this.approvalService = approvalService;
        this.skillTests = skillTests;
        this.executor = executor;
        this.pendingActions = pendingActions;
        this.policies = policies;
        this.agents = agents;
        this.guardrails = guardrails;
        this.rules = rules;
        this.audit = audit;
        this.compliance = compliance;
        this.currentUser = currentUser;
        this.ruleSheets = new RuleSheets(rules, agents, currentUser);
        addClassName("ap-page");
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        tab = Tab.of(event.getRouteParameters().get("tab").orElse("rules"));
        build();
    }

    private void build() {
        removeAll();
        Paragraph sub = new Paragraph(getTranslation("policies.sub"));
        sub.addClassName("ap-sub");
        add(Dom.div("ap-ph", Dom.div(null, new H1(getTranslation("nav.policies")), sub)));
        Segmented<Tab> seg = new Segmented<>(List.of(new Segmented.Option<>(Tab.RULES, getTranslation("policies.tab.rules")),
                new Segmented.Option<>(Tab.CUSTOM, getTranslation("policies.tab.custom")),
                new Segmented.Option<>(Tab.APPROVALS, getTranslation("policies.tab.approvals") + approvalCount()),
                new Segmented.Option<>(Tab.AUDIT, getTranslation("policies.tab.audit")),
                new Segmented.Option<>(Tab.RECORDS, getTranslation("policies.tab.records"))), tab).onChange(t -> {
            tab = t;
            UI.getCurrent().getPage().getHistory().replaceState(null, "policies/" + t.path);
            paint();
        });
        add(Dom.div("ap-toolbar", seg), content);
        paint();
    }

    private void paint() {
        content.removeAll();
        content.add(switch (tab) {
            case RULES -> rulesTab();
            case CUSTOM -> customTab();
            case APPROVALS -> approvalsTab();
            case AUDIT -> auditTab();
            case RECORDS -> recordsTab();
        });
    }

    // ----- workspace rules -----

    private Div rulesTab() {
        boolean admin = currentUser.isAdmin();
        WorkspacePolicy pol = policies.current();

        SwitchButton pii = policySwitch(pol.isPersonalDataOn(), "policy.pii", admin, on -> update(on, pol.isSpecialDataOn(), pol.isEuOnlyOn(), pol.getDefaultRetention()));
        SwitchButton special = policySwitch(pol.isSpecialDataOn(), "policy.special", admin, on -> update(pol.isPersonalDataOn(), on, pol.isEuOnlyOn(), pol.getDefaultRetention()));
        SwitchButton eu = policySwitch(pol.isEuOnlyOn(), "policy.eu", admin, on -> update(pol.isPersonalDataOn(), pol.isSpecialDataOn(), on, pol.getDefaultRetention()));
        SwitchButton auditSwitch = new SwitchButton(true, getTranslation("policy.audit"), null);
        auditSwitch.setEnabled(false);

        Segmented<Retention> retention = new Segmented<>(Arrays.stream(Retention.values())
                .map(r -> new Segmented.Option<>(r, Labels.of(this, r))).toList(), pol.getDefaultRetention());
        retention.setEnabled(admin);
        retention.onChange(r -> update(pol.isPersonalDataOn(), pol.isSpecialDataOn(), pol.isEuOnlyOn(), r));

        Div ctls = Dom.div("ap-ctls",
                Dom.control(getTranslation("policy.pii"), getTranslation("policy.pii.sub"), pii),
                Dom.control(getTranslation("policy.special"), getTranslation("policy.special.sub"), special),
                Dom.control(getTranslation("policy.eu"), getTranslation("policy.eu.sub"), eu),
                Dom.control(getTranslation("policy.audit"), getTranslation("policy.audit.sub"), auditSwitch));
        Div panel = Dom.div("ap-card ap-panel", Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("policy.title")),
                para(getTranslation("policy.sub")))));
        if (!admin) {
            panel.add(Dom.lock(getTranslation("policy.adminOnly")));
        }
        panel.add(ctls, new FormField(getTranslation("policy.retention"), null, Dom.div(null, retention), null));
        Paragraph version = new Paragraph(getTranslation("policy.version", pol.getRevision()));
        version.addClassName("ap-hint");
        panel.add(version);

        Div rows = Dom.div("ap-rows");
        for (Agent a : agents.search(null, "")) {
            GuardrailScore score = guardrails.score(a.getId());
            NativeButton name = Dom.button("ap-linkish", a.getName(), () -> UI.getCurrent().navigate(AgentView.class,
                    new RouteParameters("agentId", a.getId().toString())));
            Div row = Dom.div("ap-row", new Emblem(a, "sm"), Dom.div("ap-row-main", name,
                    Dom.span("ap-hint", score.gaps().isEmpty() ? getTranslation("policy.allOn") : getTranslation("policy.missing", score.gaps().size()))),
                    Dom.span("ap-pill " + Labels.levelClass(score), score.met() + "/" + GuardrailScore.TOTAL));
            rows.add(row);
        }
        Div status = Dom.div("ap-card ap-panel", Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("policy.status")),
                para(getTranslation("policy.status.sub")))), rows);

        Div history = Dom.div("ap-card ap-panel", Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("policy.history")),
                para(getTranslation("policy.history.sub")))));
        for (WorkspacePolicyVersion v : policies.history().stream().limit(8).toList()) {
            Span head = Dom.span(null, "v" + v.getRevision());
            head.getStyle().set("font-weight", "600");
            history.add(Dom.div("ap-hrow", head, Dom.span("ap-hint", " " + getTranslation("policy.history.entry",
                    v.getChangedBy() == null ? getTranslation("policy.history.initial") : v.getChangedBy().getDisplayName(),
                    DAY.format(v.getCreatedAt().atZone(ZoneId.systemDefault())),
                    getTranslation(v.isPersonalDataOn() ? "policy.on" : "policy.off"), getTranslation(v.isSpecialDataOn() ? "policy.on" : "policy.off"),
                    getTranslation(v.isEuOnlyOn() ? "policy.on" : "policy.off"), Labels.of(this, v.getDefaultRetention())))));
        }
        return Dom.div("ap-col", panel, status, history);
    }

    private SwitchButton policySwitch(boolean on, String labelKey, boolean admin, java.util.function.Consumer<Boolean> change) {
        SwitchButton sw = new SwitchButton(on, getTranslation(labelKey), change);
        sw.setEnabled(admin);
        if (!admin) {
            sw.getElement().setAttribute("title", getTranslation("policy.adminOnly"));
        }
        return sw;
    }

    private void update(boolean pii, boolean special, boolean eu, Retention retention) {
        try {
            var result = policies.update(pii, special, eu, retention);
            Feedback.saved(this, "saved");
            if (!result.adjustedAgents().isEmpty()) {
                Feedback.saved(this, "policy.adjusted", result.adjustedAgents().size(), String.join(", ", result.adjustedAgents()));
            }
        } catch (RuntimeException e) {
            Feedback.run(this, () -> {
                throw e;
            });
        }
        paint();
    }

    private static Paragraph para(String text) {
        return new Paragraph(text);
    }

    // ----- custom rules -----

    private Div customTab() {
        boolean admin = currentUser.isAdmin();
        Div col = Dom.div("ap-col");
        List<RuleVersion> pending = rules.allPending();
        if (!pending.isEmpty()) {
            Div rows = Dom.div("ap-rows");
            for (RuleVersion v : pending) {
                CustomRule rule = rules.get(v.getRuleId());
                Div row = Dom.div("ap-row ap-cr", Dom.div("ap-row-main", Dom.span(null, rule.getName()),
                        Dom.span("ap-hint", getTranslation("rules.pending.meta", v.getVersionNumber(), v.getAuthor().getDisplayName(),
                                formatTime(v.getCreatedAt().atZone(ZoneId.systemDefault()))))),
                        Dom.button("ap-btn ap-sm ap-primary", getTranslation("rules.review"), () -> ruleSheets.open(rule.getId(), this::paint)));
                row.getChildren().findFirst().ifPresent(c -> ((Div) c).getChildren().findFirst().ifPresent(t -> t.getElement().getStyle().set("font-weight", "600")));
                rows.add(row);
            }
            col.add(Dom.div("ap-card ap-panel", Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("rules.pending.title")),
                    para(getTranslation("rules.pending.sub")))), rows));
        }
        Div rows = Dom.div("ap-rows");
        for (CustomRule r : rules.all()) {
            boolean hasPending = pending.stream().anyMatch(v -> v.getRuleId().equals(r.getId()));
            NativeButton name = Dom.button("ap-linkish", r.getName(), () -> ruleSheets.open(r.getId(), this::paint));
            String scopeText = r.getScope() == com.agenticpilot.guardrail.RuleScope.ALL ? getTranslation("rules.allAgents")
                    : getTranslation("count.agents", r.getAgentIds().size());
            Div row = Dom.div("ap-row ap-cr", Dom.span("ap-ico", Dom.icon(VaadinIcon.SHIELD, 18)),
                    Dom.div("ap-row-main", name, Dom.span("ap-hint", getTranslation("rules.listMeta", r.getLiveVersion(), scopeText, r.getCatches()))));
            if (r.getLiveVersion() == 0) {
                row.add(Dom.span("ap-pill ap-warn", getTranslation("rules.notLive")));
            } else if (!r.isEnabled()) {
                row.add(Dom.span("ap-pill", getTranslation("rules.off")));
            }
            if (hasPending && r.getLiveVersion() > 0) {
                row.add(Dom.span("ap-pill ap-warn", getTranslation("rules.changePending")));
            }
            row.add(Dom.span("ap-pill " + Labels.pillClass(r.getAction()), Labels.of(this, r.getAction())));
            SwitchButton sw = new SwitchButton(r.isEnabled(), getTranslation("rules.toggle", r.getName()), on -> {
                Feedback.run(this, () -> rules.setEnabled(r.getId(), on));
                paint();
            });
            sw.setEnabled(admin);
            if (!admin) {
                sw.getElement().setAttribute("title", getTranslation("rules.toggle.adminOnly"));
            }
            row.add(sw);
            rows.add(row);
        }
        if (rules.all().isEmpty()) {
            rows.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("rules.empty"))));
        }
        Div head = Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("rules.custom.title")), para(getTranslation("rules.custom.longSub"))),
                Dom.button("ap-btn ap-primary ap-sm", VaadinIcon.PLUS, getTranslation("rules.newGuardrail"), () -> ruleSheets.openNew(null, this::paint)));
        Div card = Dom.div("ap-card ap-panel", head);
        if (!admin) {
            card.add(Dom.lock(getTranslation("rules.ownerNote")));
        }
        card.add(rows);
        col.add(card);
        return col;
    }

    // ----- approvals -----

    private String approvalCount() {
        int n = approvalService.pendingCount();
        return n == 0 ? "" : " (" + n + ")";
    }

    private Div approvalsTab() {
        Div rows = Dom.div("ap-rows");
        List<ApprovalService.ReviewItem> pending = approvalService.pending();
        for (ApprovalService.ReviewItem i : pending) {
            Span name = Dom.span(null, i.name() + " v" + i.versionNumber());
            name.getStyle().set("font-weight", "600");
            Div row = Dom.div("ap-row ap-cr", Dom.div("ap-row-main", name,
                    Dom.span("ap-hint", getTranslation("approvals.meta", i.author().getDisplayName(), formatTime(i.submittedAt().atZone(ZoneId.systemDefault())),
                            i.note() == null ? "" : i.note()))),
                    Dom.span("ap-pill", getTranslation("approvals.type." + i.type().name())));
            if (i.isNew()) {
                row.add(Dom.span("ap-pill ap-warn", getTranslation("approvals.new")));
            }
            if (i.mine()) {
                row.add(Dom.span("ap-pill", getTranslation("approvals.yours")));
            }
            row.add(Dom.button("ap-btn ap-sm" + (i.mine() ? "" : " ap-primary"), getTranslation("rules.review"), () -> review(i)));
            rows.add(row);
        }
        if (pending.isEmpty()) {
            rows.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("approvals.empty"))));
        }
        return Dom.div("ap-col", Dom.div("ap-card ap-panel", Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("approvals.title")),
                para(getTranslation("approvals.sub")))), rows));
    }

    private void review(ApprovalService.ReviewItem item) {
        if (item.type() == ApprovalService.ItemType.RULE) {
            ruleSheets.open(item.itemId(), this::build);
            return;
        }
        new VersionReviewSheet(approvalService, skillTests, executor, currentUser, item, this::build).open();
    }

    // ----- audit log -----

    private AuditService.Filter filter() {
        return new AuditService.Filter(auditOutcome, auditAgent, auditText, auditFrom, auditTo);
    }

    private Div auditTab() {
        Div card = Dom.div("ap-card ap-panel", Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("audit.title")), para(getTranslation("audit.sub"))),
                Dom.button("ap-btn ap-sm", getTranslation("audit.export"), this::export)));
        Div entries = new Div();
        Runnable fill = () -> fillAudit(entries);

        List<Segmented.Option<AuditOutcome>> options = new java.util.ArrayList<>();
        options.add(new Segmented.Option<>(null, getTranslation("filter.all")));
        for (AuditOutcome o : AuditOutcome.values()) {
            options.add(new Segmented.Option<>(o, Labels.of(this, o)));
        }
        Segmented<AuditOutcome> outcome = new Segmented<>(options, auditOutcome).onChange(f -> {
            auditOutcome = f;
            fill.run();
        });

        SearchBox search = new SearchBox("audit-search", getTranslation("audit.search"), getTranslation("audit.search"), q -> {
            auditText = q;
            fill.run();
        });
        List<NativeSelect.Choice> agentChoices = new java.util.ArrayList<>();
        agentChoices.add(new NativeSelect.Choice("", getTranslation("audit.allAgents")));
        for (Agent a : agents.search(null, "")) {
            agentChoices.add(new NativeSelect.Choice(a.getId().toString(), a.getName()));
        }
        NativeSelect agent = new NativeSelect("audit-agent", agentChoices, auditAgent == null ? "" : auditAgent.toString());
        agent.getElement().setAttribute("aria-label", getTranslation("audit.agent"));
        agent.addValueChangeListener(e -> {
            auditAgent = e.getValue() == null || e.getValue().isEmpty() ? null : java.util.UUID.fromString(e.getValue());
            fill.run();
        });
        Input from = dateInput("audit-from", getTranslation("audit.from"), auditFrom, d -> {
            auditFrom = d;
            fill.run();
        });
        Input to = dateInput("audit-to", getTranslation("audit.to"), auditTo, d -> {
            auditTo = d;
            fill.run();
        });
        card.add(outcome, Dom.div("ap-toolbar ap-audit-filters", search, new FormField(getTranslation("audit.agent"), "audit-agent", agent, null),
                new FormField(getTranslation("audit.from"), "audit-from", from, null),
                new FormField(getTranslation("audit.to"), "audit-to", to, null)));
        card.add(entries);
        fill.run();
        return Dom.div("ap-col", card);
    }

    private Input dateInput(String id, String label, LocalDate value, java.util.function.Consumer<LocalDate> onChange) {
        Input in = new Input(com.vaadin.flow.data.value.ValueChangeMode.ON_CHANGE);
        in.setId(id);
        in.setType("date");
        in.getElement().setAttribute("aria-label", label);
        in.setValue(value == null ? "" : value.toString());
        in.addValueChangeListener(e -> {
            try {
                onChange.accept(e.getValue() == null || e.getValue().isBlank() ? null : LocalDate.parse(e.getValue()));
            } catch (java.time.format.DateTimeParseException ex) {
                onChange.accept(null);
            }
        });
        return in;
    }

    private void fillAudit(Div entries) {
        entries.removeAll();
        List<AuditEntry> list = audit.search(filter(), 200);
        Map<java.util.UUID, PendingAction> actions = pendingActions.forAuditEntries(list.stream()
                .filter(x -> x.getOutcome() == AuditOutcome.NEEDS_APPROVAL).map(AuditEntry::getId).toList());
        if (list.isEmpty()) {
            entries.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("audit.empty"))));
        }
        for (AuditEntry e : list) {
            Span who = Dom.span("ap-hint", e.getAgentName() == null ? getTranslation("audit.workspace") : e.getAgentName());
            Span event = Dom.span(null, e.getEvent());
            event.getStyle().set("font-weight", "600");
            Div row = Dom.div("ap-logrow", Dom.span("ap-num", formatTime(e.getOccurredAt().atZone(ZoneId.systemDefault()))),
                    Dom.div("ap-row-main", event, who), Dom.span("ap-pill " + Labels.pillClass(e.getOutcome()), Labels.of(this, e.getOutcome())));
            PendingAction action = actions.get(e.getId());
            if (action != null) {
                Div side = Dom.div("ap-pending-actions");
                side.getStyle().set("grid-column", "2 / -1").set("display", "flex").set("gap", "8px").set("align-items", "center").set("flex-wrap", "wrap");
                if (action.getStatus() == PendingActionStatus.PENDING) {
                    side.add(Dom.span("ap-pill ap-warn", getTranslation("pending.open")));
                    if (currentUser.isAdmin()) {
                        side.add(Dom.button("ap-btn ap-sm ap-primary", VaadinIcon.CHECK, getTranslation("pending.approve"), () -> decide(action, true)));
                        side.add(Dom.button("ap-btn ap-sm", getTranslation("pending.deny"), () -> decide(action, false)));
                    } else {
                        side.add(Dom.span("ap-hint", getTranslation("pending.adminOnly")));
                    }
                } else {
                    side.add(Dom.span("ap-pill " + (action.getStatus() == PendingActionStatus.APPROVED ? "ap-ok" : "ap-bad"),
                            getTranslation("pending." + action.getStatus().name())));
                    side.add(Dom.span("ap-hint", getTranslation("pending.decidedBy", action.getDecidedBy() == null ? "" : action.getDecidedBy().getDisplayName())));
                }
                row.add(side);
            }
            entries.add(row);
        }
    }

    private void decide(PendingAction action, boolean approve) {
        if (Feedback.run(this, () -> {
            if (approve) {
                pendingActions.approve(action.getId());
            } else {
                pendingActions.deny(action.getId());
            }
        })) {
            Feedback.saved(this, approve ? "pending.approved" : "pending.denied");
        }
        paint();
    }

    private void export() {
        String csv = audit.exportCsv(filter());
        StreamResource resource = new StreamResource("audit-log.csv", () -> new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
        resource.setContentType("text/csv; charset=utf-8");
        Anchor link = new Anchor(resource, "");
        link.getElement().setAttribute("download", true);
        link.getStyle().set("display", "none");
        add(link);
        link.getElement().executeJs("this.click()").then(r -> link.removeFromParent());
    }

    private String formatTime(ZonedDateTime t) {
        return t.toLocalDate().equals(LocalDate.now()) ? TIME.format(t) : DAY_TIME.format(t);
    }

    // ----- records -----

    private Div recordsTab() {
        boolean admin = currentUser.isAdmin();
        Div recordRows = Dom.div("ap-rows");
        for (ComplianceRecord r : compliance.records()) {
            String date = r.getDueDate() == null ? "" : " " + DAY.format(r.getDueDate());
            Span title = Dom.span(null, r.getTitle());
            title.getStyle().set("font-weight", "600");
            Div row = Dom.div("ap-row", Dom.div("ap-row-main", title,
                    Dom.span("ap-hint", getTranslation("records.meta", r.getOwnerName(), r.getNote() + date))),
                    Dom.span("ap-pill " + recordClass(r.getStatus()), getTranslation("record." + r.getStatus().name())));
            if (r.getStatus() != RecordStatus.DONE && admin) {
                row.add(Dom.button("ap-btn ap-sm", getTranslation("records.done"), () -> {
                    if (Feedback.run(this, () -> compliance.completeRecord(r.getId()))) {
                        Feedback.saved(this, "saved");
                    }
                    paint();
                }));
            }
            recordRows.add(row);
        }
        Div records = Dom.div("ap-card ap-panel", Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("records.title")), para(getTranslation("records.sub")))), recordRows);

        Div requestRows = Dom.div("ap-rows");
        LocalDate today = LocalDate.now();
        for (SubjectRequest r : compliance.requests()) {
            long days = java.time.temporal.ChronoUnit.DAYS.between(today, r.getDueDate());
            Span title = Dom.span(null, getTranslation("request." + r.getKind().name(), r.getSubject()));
            title.getStyle().set("font-weight", "600");
            Div row = Dom.div("ap-row", Dom.div("ap-row-main", title, Dom.span("ap-hint",
                    r.isDone() ? getTranslation("requests.completed") : getTranslation("requests.due", days))));
            if (r.isDone()) {
                row.add(Dom.span("ap-pill ap-ok", getTranslation("requests.doneLabel")));
            } else {
                row.add(Dom.span("ap-pill " + (r.isUrgent(today) ? "ap-bad" : "ap-warn"), getTranslation("requests.open")));
                if (admin) {
                    row.add(Dom.button("ap-btn ap-sm", getTranslation("records.done"), () -> {
                        if (Feedback.run(this, () -> compliance.completeRequest(r.getId()))) {
                            Feedback.saved(this, "saved");
                        }
                        paint();
                    }));
                }
            }
            requestRows.add(row);
        }
        Div requests = Dom.div("ap-card ap-panel", Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("requests.title")), para(getTranslation("requests.sub")))),
                requestRows, hintPara(getTranslation("requests.findLater")));
        return Dom.div("ap-col", records, requests);
    }

    private static Paragraph hintPara(String text) {
        Paragraph p = new Paragraph(text);
        p.addClassName("ap-hint");
        return p;
    }

    private static String recordClass(RecordStatus s) {
        return switch (s) {
            case DONE -> "ap-ok";
            case IN_PROGRESS -> "ap-warn";
            case TODO -> "";
        };
    }
}
