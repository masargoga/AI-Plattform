package com.agenticpilot.ui;

import com.agenticpilot.agent.Agent;
import com.agenticpilot.agent.AgentService;
import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.guardrail.CatchPattern;
import com.agenticpilot.guardrail.CustomRule;
import com.agenticpilot.guardrail.CustomRuleService;
import com.agenticpilot.guardrail.CustomRuleService.RuleFields;
import com.agenticpilot.guardrail.RuleAction;
import com.agenticpilot.guardrail.RuleCheck;
import com.agenticpilot.guardrail.RuleScope;
import com.agenticpilot.guardrail.RuleSnapshot;
import com.agenticpilot.guardrail.RuleVersion;
import com.agenticpilot.guardrail.VersionStatus;
import com.agenticpilot.user.AppUser;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.FormField;
import com.agenticpilot.ui.components.Segmented;
import com.agenticpilot.ui.components.Sheet;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.NativeLabel;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/** The rule editor, the review sheet for pending changes and the picker that adds rules to an agent. */
class RuleSheets {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH);

    private final CustomRuleService rules;
    private final AgentService agents;
    private final CurrentUser currentUser;

    RuleSheets(CustomRuleService rules, AgentService agents, CurrentUser currentUser) {
        this.rules = rules;
        this.agents = agents;
        this.currentUser = currentUser;
    }

    /** Opens the review sheet when a change is waiting, otherwise the editor. */
    void open(UUID ruleId, Runnable onChange) {
        if (rules.pending(ruleId).isPresent()) {
            openReview(ruleId, onChange);
        } else {
            openEditor(ruleId, null, onChange);
        }
    }

    void openNew(UUID preselectedAgent, Runnable onChange) {
        openEditor(null, preselectedAgent, onChange);
    }

    // ----- editor -----

    private void openEditor(UUID ruleId, UUID preselectedAgent, Runnable onChange) {
        boolean admin = currentUser.isAdmin();
        AppUser me = currentUser.require();
        CustomRule rule = ruleId == null ? null : rules.get(ruleId);
        boolean readOnly = rule != null && !rules.canEdit(rule);

        Sheet sheet = new Sheet(getTranslation(rule == null ? "rules.new.title" : "rules.edit.title"),
                getTranslation(rule == null ? "rules.new.sub" : rule.getLiveVersion() > 0 ? "rules.edit.subLive" : "rules.edit.subNotLive", rule == null ? 0 : rule.getLiveVersion()));

        RuleSnapshot base = rule != null ? rule.toSnapshot()
                : new RuleSnapshot("", "", RuleAction.BLOCK, "", admin && preselectedAgent == null ? RuleScope.ALL : RuleScope.SELECTED,
                preselectedAgent == null ? Set.of() : Set.of(preselectedAgent), "");

        Input name = FormField.textInput("r-name", base.name(), CustomRuleService.NAME_MAX);
        Input catches = FormField.textInput("r-catches", base.catches(), CatchPattern.MAX_LENGTH);
        Input message = FormField.textInput("r-message", base.message(), 500);
        Input sampleIn = FormField.textInput("r-sample", base.sample(), 500);
        Input note = FormField.textInput("r-note", "", CustomRuleService.NOTE_MAX);
        for (Input in : List.of(name, catches, message, sampleIn)) {
            in.setEnabled(!readOnly);
        }
        catches.setPlaceholder("lawsuit, sue, legal advice");
        name.setPlaceholder(getTranslation("rules.name.placeholder"));

        Segmented<RuleAction> action = new Segmented<>(Arrays.stream(RuleAction.values())
                .map(a -> new Segmented.Option<>(a, Labels.of(sheetOwner(sheet), a))).toList(), base.action());
        action.setEnabled(!readOnly);

        Div messageField = new Div();
        messageField.addClassName("ap-field");
        NativeLabel messageLabel = new NativeLabel();
        messageLabel.setFor("r-message");
        messageField.add(messageLabel, message);
        Runnable paintMessage = () -> {
            RuleAction a = action.getValue();
            messageField.setVisible(a != RuleAction.MASK);
            messageLabel.setText(getTranslation("rules.message." + a.name()));
        };
        paintMessage.run();

        Set<UUID> selected = new LinkedHashSet<>(base.agentIds());
        RuleScope[] scope = {base.scope()};
        Div agentChips = Dom.div("ap-tchips");
        Paragraph allHint = new Paragraph(getTranslation("rules.scope.allHint"));
        allHint.addClassName("ap-hint");
        Runnable paintScope = () -> {
            agentChips.setVisible(scope[0] == RuleScope.SELECTED);
            allHint.setVisible(scope[0] == RuleScope.ALL);
        };
        List<Agent> all = agents.search(null, "");
        for (Agent a : all) {
            boolean owned = a.getOwner().getId().equals(me.getId());
            NativeButton chip = Dom.button("ap-tchip", null, null);
            Runnable paint = () -> {
                boolean on = selected.contains(a.getId());
                chip.setClassName("ap-on", on);
                chip.getElement().setAttribute("aria-pressed", String.valueOf(on));
                chip.removeAll();
                if (on) {
                    chip.add(Dom.icon(VaadinIcon.CHECK, 14));
                }
                chip.add(new Span(a.getName()));
            };
            paint.run();
            chip.setEnabled(!readOnly && (admin || owned));
            if (!admin && !owned) {
                chip.getElement().setAttribute("title", getTranslation("rules.notYourAgent"));
            }
            chip.addClickListener(e -> {
                if (!selected.add(a.getId())) {
                    selected.remove(a.getId());
                }
                paint.run();
            });
            agentChips.add(chip);
        }
        Segmented<RuleScope> scopeSeg = admin
                ? new Segmented<>(List.of(new Segmented.Option<>(RuleScope.ALL, getTranslation("rules.scope.all")),
                new Segmented.Option<>(RuleScope.SELECTED, getTranslation("rules.scope.selected"))), scope[0])
                : new Segmented<>(List.of(new Segmented.Option<>(RuleScope.SELECTED, getTranslation("rules.scope.selected"))), RuleScope.SELECTED);
        scopeSeg.setEnabled(!readOnly && admin);
        scopeSeg.onChange(s -> {
            scope[0] = s;
            paintScope.run();
        });
        paintScope.run();
        action.onChange(a -> paintMessage.run());

        Div result = Dom.div("ap-result");
        result.setVisible(false);
        NativeButton check = Dom.button("ap-btn", getTranslation("rules.check"), () -> {
            RuleCheck.Result r = RuleCheck.evaluate(catches.getValue(), action.getValue(), message.getValue(), sampleIn.getValue());
            result.setText(describe(r));
            result.setVisible(true);
        });
        Div sampleRow = Dom.div(null, sampleIn, check);
        sampleRow.getStyle().set("display", "flex").set("gap", "8px");
        sampleIn.getStyle().set("flex", "1").set("min-width", "0");

        Paragraph error = new Paragraph();
        error.addClassName("ap-err");
        error.setVisible(false);

        Div scopeField = new FormField(getTranslation("rules.appliesTo"), null, Dom.div(null, scopeSeg), null);
        if (!admin) {
            scopeField.add(Dom.lock(getTranslation("rules.scope.adminOnly")));
        }
        scopeField.add(agentChips, allHint);

        Div body = Dom.div("ap-sg");
        if (readOnly) {
            body.add(Dom.lock(getTranslation(admin ? "rules.readOnly" : "rules.readOnly.owner")));
        } else {
            Paragraph intro = new Paragraph(getTranslation("rules.proposingAs", me.getDisplayName(),
                    getTranslation(admin ? "rules.secondAdmin" : "rules.anAdmin")));
            intro.addClassName("ap-hint");
            body.add(intro);
        }
        body.add(new FormField(getTranslation("rules.name"), "r-name", name, null),
                new FormField(getTranslation("rules.catches"), "r-catches", catches, getTranslation("rules.catches.hint")),
                new FormField(getTranslation("rules.whenMatches"), null, Dom.div(null, action), null),
                messageField, scopeField,
                new FormField(getTranslation("rules.sample"), "r-sample", sampleRow, null));
        body.add(result);
        if (!readOnly) {
            body.add(new FormField(getTranslation(rule == null ? "rules.note.new" : "rules.note.change"), "r-note", note, getTranslation("rules.note.hint")));
        }
        body.add(error);
        if (rule != null) {
            body.add(historyBlock(rule.getId()));
        }
        sheet.setBody(body);

        List<com.vaadin.flow.component.Component> footer = new ArrayList<>();
        if (readOnly) {
            footer.add(Dom.button("ap-btn ap-primary", getTranslation("action.close"), sheet::close));
        } else {
            if (rule != null && admin) {
                NativeButton delete = Dom.button("ap-btn ap-ghost", getTranslation("rules.delete"), null);
                delete.getStyle().set("margin-right", "auto").set("color", "var(--ap-bad)");
                boolean[] armed = {false};
                delete.addClickListener(e -> {
                    if (!armed[0]) {
                        armed[0] = true;
                        delete.setText(getTranslation("rules.delete.confirm"));
                        return;
                    }
                    if (Feedback.run(sheet, () -> rules.delete(rule.getId()))) {
                        sheet.close();
                        Feedback.saved(sheet, "rules.deleted");
                        onChange.run();
                    }
                });
                footer.add(delete);
            }
            footer.add(Dom.button("ap-btn ap-ghost", getTranslation("action.cancel"), sheet::close));
            footer.add(Dom.button("ap-btn ap-primary", VaadinIcon.CHECK, getTranslation("rules.submit"), () -> {
                RuleFields f = new RuleFields(name.getValue(), catches.getValue(), action.getValue(), message.getValue(), scope[0],
                        scope[0] == RuleScope.ALL ? Set.of() : Set.copyOf(selected), sampleIn.getValue());
                try {
                    rules.propose(ruleId, f, note.getValue());
                } catch (com.agenticpilot.common.InvalidInputException e) {
                    error.setText(getTranslation(e.getMessage(), e.getArgs()));
                    error.setVisible(true);
                    return;
                } catch (org.springframework.security.access.AccessDeniedException e) {
                    error.setText(getTranslation("error.denied"));
                    error.setVisible(true);
                    return;
                }
                sheet.close();
                Feedback.saved(sheet, "rules.sentForApproval");
                onChange.run();
            }));
        }
        sheet.setFooter(footer.toArray(new com.vaadin.flow.component.Component[0]));
        sheet.open();
    }

    private String describe(RuleCheck.Result r) {
        return switch (r.kind()) {
            case NO_CATCHES -> getTranslation("rules.check.noCatches");
            case INVALID_PATTERN -> getTranslation("rules.check.invalid");
            case NO_SAMPLE -> getTranslation("rules.check.noSample");
            case NO_MATCH -> getTranslation("rules.check.noMatch");
            case MATCH -> getTranslation("rules.check.match") + " " + switch (r.action()) {
                case BLOCK -> getTranslation("rules.check.block", r.text());
                case MASK -> getTranslation("rules.check.mask", r.text());
                case APPROVE -> getTranslation("rules.check.approve");
                case NOTE -> getTranslation("rules.check.note", r.text());
            };
        };
    }

    // ----- review -----

    private void openReview(UUID ruleId, Runnable onChange) {
        CustomRule rule = rules.get(ruleId);
        RuleVersion pending = rules.pending(ruleId).orElseThrow();
        RuleSnapshot proposed = rules.snapshot(pending);
        boolean isNew = rule.getLiveVersion() == 0;
        AppUser me = currentUser.require();
        boolean admin = me.isAdmin();
        boolean mine = pending.getAuthor().getId().equals(me.getId());

        Sheet sheet = new Sheet(isNew ? getTranslation("rules.review.new") : getTranslation("rules.review.change", rule.getName()),
                isNew ? getTranslation("rules.review.notLive")
                        : getTranslation("rules.review.stays", rule.getLiveVersion(), pending.getVersionNumber()));

        Div rows = Dom.div("ap-rows");
        rows.getStyle().set("margin-top", "8px");
        RuleSnapshot live = rule.toSnapshot();
        diff(rows, "rules.name", isNew, live.name(), proposed.name());
        diff(rows, "rules.catches", isNew, live.catches(), proposed.catches());
        diff(rows, "rules.whenMatches", isNew, Labels.of(sheetOwner(sheet), live.action()), Labels.of(sheetOwner(sheet), proposed.action()));
        if (!(Objects.equals(live.message(), proposed.message()) && !isNew) && !(proposed.message().isEmpty() && live.message().isEmpty())) {
            rows.add(diffRow("rules.message", isNew ? null : live.message(), proposed.message()));
        }
        diff(rows, "rules.appliesTo", isNew, scopeText(live.scope()), scopeText(proposed.scope()));
        if (proposed.scope() == RuleScope.SELECTED || live.scope() == RuleScope.SELECTED) {
            diff(rows, "rules.agents", isNew, agentNames(live.agentIds()), agentNames(proposed.agentIds()));
        }

        Div body = Dom.div("ap-sg",
                Dom.span("ap-hint", getTranslation("rules.review.by", pending.getAuthor().getDisplayName())),
                Dom.div("ap-quote", Dom.span(null, pending.getNote())),
                Dom.div(null, Dom.span("ap-lbl", getTranslation(isNew ? "rules.review.newLabel" : "rules.review.changes")), rows));
        if (rows.getChildren().findAny().isEmpty()) {
            rows.add(Dom.span("ap-hint", getTranslation("rules.review.sampleOnly")));
        }
        if (mine) {
            body.add(Dom.lock(getTranslation("rules.review.own", getTranslation(admin ? "rules.secondAdmin" : "rules.anAdmin"))));
        } else if (!admin) {
            body.add(Dom.lock(getTranslation("rules.review.adminOnly")));
        }
        body.add(historyBlock(ruleId));
        sheet.setBody(body);

        if (mine) {
            sheet.setFooter(Dom.button("ap-btn ap-ghost", getTranslation("action.close"), sheet::close),
                    Dom.button("ap-btn", getTranslation("rules.withdraw"), () -> {
                        if (Feedback.run(sheet, () -> rules.withdraw(ruleId))) {
                            sheet.close();
                            Feedback.saved(sheet, "rules.withdrawn");
                            onChange.run();
                        }
                    }));
        } else if (!admin) {
            sheet.setFooter(Dom.button("ap-btn ap-primary", getTranslation("action.close"), sheet::close));
        } else {
            sheet.setFooter(Dom.button("ap-btn", getTranslation("rules.reject"), () -> {
                        if (Feedback.run(sheet, () -> rules.reject(ruleId))) {
                            sheet.close();
                            Feedback.saved(sheet, "rules.rejected");
                            onChange.run();
                        }
                    }),
                    Dom.button("ap-btn ap-primary", VaadinIcon.CHECK, getTranslation("rules.approve", pending.getVersionNumber()), () -> {
                        if (Feedback.run(sheet, () -> rules.approve(ruleId))) {
                            sheet.close();
                            Feedback.saved(sheet, "rules.approved");
                            onChange.run();
                        }
                    }));
        }
        sheet.open();
    }

    private void diff(Div rows, String labelKey, boolean isNew, String oldValue, String newValue) {
        if (isNew || !Objects.equals(oldValue, newValue)) {
            rows.add(diffRow(labelKey, isNew ? null : oldValue, newValue));
        }
    }

    private Div diffRow(String labelKey, String oldValue, String newValue) {
        Span value = new Span();
        if (oldValue != null) {
            Span old = new Span(oldValue.isEmpty() ? getTranslation("rules.none") : oldValue);
            old.getStyle().set("text-decoration", "line-through").set("color", "var(--ap-ink-3)");
            value.add(old, new Span(" \u2192 "));
        }
        Span now = new Span(newValue.isEmpty() ? getTranslation("rules.none") : newValue);
        now.getStyle().set("font-weight", "600");
        value.add(now);
        return Dom.div("ap-diff", Dom.span("ap-lbl", getTranslation(labelKey)), value);
    }

    private String scopeText(RuleScope s) {
        return getTranslation(s == RuleScope.ALL ? "rules.scope.all" : "rules.scope.selected");
    }

    private String agentNames(Set<UUID> ids) {
        if (ids.isEmpty()) {
            return "";
        }
        return ids.stream().map(id -> {
            try {
                return agents.get(id).getName();
            } catch (RuntimeException e) {
                return id.toString();
            }
        }).collect(Collectors.joining(", "));
    }

    private Div historyBlock(UUID ruleId) {
        List<RuleVersion> history = rules.history(ruleId);
        Div block = new Div();
        if (history.isEmpty()) {
            return block;
        }
        block.add(Dom.span("ap-lbl", getTranslation("rules.history")));
        for (RuleVersion v : history) {
            boolean approved = v.getStatus() == VersionStatus.APPROVED;
            LocalDate day = v.getDecidedAt() == null ? null : v.getDecidedAt().atZone(ZoneId.systemDefault()).toLocalDate();
            Span head = Dom.span(null, "v" + v.getVersionNumber());
            head.getStyle().set("font-weight", "600");
            Span meta = Dom.span("ap-hint", " " + getTranslation(approved ? "rules.history.approved" : "rules.history.rejected",
                    v.getAuthor().getDisplayName(), day == null ? "" : DAY.format(day), v.getApprover() == null ? "" : v.getApprover().getDisplayName()));
            Paragraph noteText = new Paragraph(v.getNote());
            noteText.getStyle().set("font-size", "13px").set("margin", "2px 0 0");
            block.add(Dom.div("ap-hrow", head, meta, noteText));
        }
        return block;
    }

    // ----- picker for an agent -----

    void openPicker(UUID agentId, String agentName, Runnable onChange) {
        Sheet sheet = new Sheet(getTranslation("rules.picker.title"), getTranslation("rules.picker.sub", agentName));
        renderPicker(sheet, agentId, onChange);
        sheet.open();
    }

    private void renderPicker(Sheet sheet, UUID agentId, Runnable onChange) {
        Div opts = Dom.div("ap-opts");
        List<CustomRule> selectable = rules.all().stream().filter(r -> r.getScope() == RuleScope.SELECTED && r.getLiveVersion() > 0).toList();
        if (selectable.isEmpty()) {
            opts.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("rules.picker.empty"))));
        }
        for (CustomRule r : selectable) {
            boolean assigned = r.getAgentIds().contains(agentId);
            boolean pending = rules.pending(r.getId()).isPresent();
            NativeButton opt = new NativeButton();
            opt.getElement().setAttribute("type", "button");
            opt.addClassNames("ap-opt");
            opt.setClassName("ap-on", assigned);
            opt.getElement().setAttribute("role", "checkbox");
            opt.getElement().setAttribute("aria-checked", String.valueOf(assigned));
            Div tick = Dom.div("ap-tick");
            if (assigned) {
                tick.add(Dom.icon(VaadinIcon.CHECK, 14));
            }
            Span title = new Span(r.getName());
            title.getStyle().set("font-weight", "600");
            String meta = getTranslation("rules.picker.meta", Labels.of(sheet, r.getAction()), r.getCatches())
                    + (pending ? " · " + getTranslation("rules.changePending") : "");
            opt.add(Dom.span("ap-ico", Dom.icon(VaadinIcon.SHIELD, 18)), Dom.span("ap-opt-main", title, Dom.span("ap-hint", meta)), tick);
            opt.setEnabled(!pending && rules.canEdit(r));
            opt.addClickListener(e -> {
                if (Feedback.run(sheet, () -> rules.proposeAgentAssignment(r.getId(), agentId, !assigned))) {
                    Feedback.saved(sheet, "rules.sentForApproval");
                }
                renderPicker(sheet, agentId, onChange);
                onChange.run();
            });
            opts.add(opt);
        }
        Paragraph hint = new Paragraph(getTranslation("rules.picker.hint"));
        hint.addClassName("ap-hint");
        hint.getStyle().set("margin-top", "12px");
        sheet.setBody(opts, hint);
        sheet.setFooter(Dom.button("ap-btn ap-ghost", getTranslation("rules.newGuardrail"), () -> {
                    sheet.close();
                    openNew(agentId, onChange);
                }),
                Dom.button("ap-btn ap-primary", getTranslation("rules.done"), sheet::close));
    }

    private String getTranslation(String key, Object... args) {
        return com.vaadin.flow.component.UI.getCurrent().getTranslation(key, args);
    }

    private static com.vaadin.flow.component.Component sheetOwner(Sheet sheet) {
        return sheet;
    }
}
