package com.agenticpilot.ui;

import com.agenticpilot.common.CurrentUser;
import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.governance.ApprovalService;
import com.agenticpilot.governance.ApprovalService.Detail;
import com.agenticpilot.governance.ApprovalService.ReviewItem;
import com.agenticpilot.runtime.BackgroundExecutor;
import com.agenticpilot.skill.SkillTestService;
import com.agenticpilot.skill.TestOutcome;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.FormField;
import com.agenticpilot.ui.components.NativeTextArea;
import com.agenticpilot.ui.components.Sheet;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.icon.VaadinIcon;
import org.springframework.security.access.AccessDeniedException;

/**
 * Review of a submitted skill or agent version: who wants what and why, every changed field (old to new), warnings, and for
 * skills the latest test results. A different Admin approves, or rejects with a comment.
 */
class VersionReviewSheet extends Sheet {

    private final ApprovalService approvals;
    private final SkillTestService tests;
    private final BackgroundExecutor executor;
    private final CurrentUser currentUser;
    private final ReviewItem item;
    private final Runnable onDone;
    private final NativeTextArea comment = new NativeTextArea("review-comment", "", 3, 500);

    VersionReviewSheet(ApprovalService approvals, SkillTestService tests, BackgroundExecutor executor, CurrentUser currentUser, ReviewItem item, Runnable onDone) {
        super(UI.getCurrent().getTranslation("review.title", item.name(), item.versionNumber()),
                UI.getCurrent().getTranslation("review.sub." + item.type().name(), item.author().getDisplayName()));
        this.approvals = approvals;
        this.tests = tests;
        this.executor = executor;
        this.currentUser = currentUser;
        this.item = item;
        this.onDone = onDone;

        Detail detail = approvals.detail(item.type(), item.versionId());
        Div body = Dom.div("ap-sg");
        if (item.note() != null && !item.note().isBlank()) {
            body.add(Dom.div("ap-banner", Dom.span(null, getTranslation("review.note", item.author().getDisplayName(), item.note()))));
        }
        for (String key : detail.warnings()) {
            body.add(Dom.div("ap-banner ap-warn", Dom.span(null, getTranslation(key))));
        }
        body.add(new H3(getTranslation(item.isNew() ? "review.newItem" : "review.changes")), new DiffView(detail.changes()));
        if (item.type() == ApprovalService.ItemType.SKILL) {
            body.add(testResults());
        }
        boolean mine = item.mine();
        boolean admin = currentUser.isAdmin();
        if (mine) {
            body.add(Dom.div("ap-banner ap-warn", Dom.span(null, getTranslation("review.mine"))));
        } else if (!admin) {
            body.add(Dom.div("ap-banner", Dom.span(null, getTranslation("review.adminOnly"))));
        } else {
            body.add(new FormField(getTranslation("review.comment"), "review-comment", comment, getTranslation("review.comment.hint")));
        }
        setBody(body);
        NativeButton close = Dom.button("ap-btn ap-ghost", getTranslation("action.close"), this::close);
        if (!mine && admin) {
            setFooter(close, Dom.button("ap-btn", VaadinIcon.CLOSE_SMALL, getTranslation("review.reject"), () -> decide(false)),
                    Dom.button("ap-btn ap-primary", VaadinIcon.CHECK, getTranslation("review.approve"), () -> decide(true)));
        } else {
            setFooter(close);
        }
    }

    private Div testResults() {
        Div box = Dom.div("ap-card ap-panel");
        box.add(new H3(getTranslation("review.tests")));
        Div rows = Dom.div("ap-rows");
        var cases = tests.cases(item.itemId(), item.versionNumber());
        for (SkillTestService.CaseView cv : cases) {
            var r = cv.latest();
            Div row = Dom.div("ap-row", Dom.div("ap-row-main", Dom.span(null, cv.testCase().getName()),
                    r == null || r.getDetail().isBlank() ? new Div() : Dom.span("ap-hint", r.getDetail())),
                    r == null ? Dom.span("ap-pill", getTranslation("tests.neverRun"))
                            : Dom.span("ap-pill" + (r.getOutcome() == TestOutcome.PASSED ? " ap-ok" : r.getOutcome() == TestOutcome.FAILED ? " ap-bad" : " ap-warn"),
                                    getTranslation("tests.outcome." + r.getOutcome().name())));
            rows.add(row);
        }
        if (cases.isEmpty()) {
            rows.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("review.noTests"))));
        } else {
            NativeButton run = Dom.button("ap-btn ap-sm", VaadinIcon.PLAY, getTranslation("tests.runAll"), null);
            run.addClickListener(e -> {
                run.setEnabled(false);
                UI ui = UI.getCurrent();
                executor.submit(() -> {
                    String error = null;
                    try {
                        tests.runAll(item.itemId(), true);
                    } catch (InvalidInputException ex) {
                        error = ui.getTranslation(ex.getMessage(), ex.getArgs());
                    } catch (RuntimeException ex) {
                        error = ui.getTranslation("chat.error");
                    }
                    String shown = error;
                    ui.access(() -> {
                        if (shown != null) {
                            Feedback.error(shown);
                        }
                        close();
                        new VersionReviewSheet(approvals, tests, executor, currentUser, item, onDone).open();
                    });
                });
            });
            rows.add(run);
        }
        box.add(rows);
        return box;
    }

    private void decide(boolean approve) {
        try {
            if (approve) {
                approvals.approve(item.type(), item.versionId(), comment.getValue());
            } else {
                approvals.reject(item.type(), item.versionId(), comment.getValue());
            }
        } catch (InvalidInputException e) {
            Feedback.error(getTranslation(e.getMessage(), e.getArgs()));
            return;
        } catch (AccessDeniedException e) {
            Feedback.error(getTranslation("error.denied"));
            return;
        }
        close();
        Feedback.saved(this, approve ? "review.approved" : "review.rejected");
        onDone.run();
    }
}
