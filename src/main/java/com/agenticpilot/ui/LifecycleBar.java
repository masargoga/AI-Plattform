package com.agenticpilot.ui;

import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.governance.ChangeStatus;
import com.agenticpilot.governance.Governed;
import com.agenticpilot.governance.GovernedService;
import com.agenticpilot.governance.ItemVersion;
import com.agenticpilot.governance.Lifecycle;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.FormField;
import com.agenticpilot.ui.components.NativeTextArea;
import com.agenticpilot.ui.components.Sheet;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.icon.VaadinIcon;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The status of a skill or agent in the approval lifecycle and what the person looking at it can do next: submit a draft,
 * withdraw, discard, retire, restore, delete. Shown at the top of the editor.
 */
class LifecycleBar extends Div {

    private final GovernedService<?, ?, ?> service;
    private final Governed item;
    private final UUID id;
    private final boolean editable;
    private final boolean admin;
    private final UUID meId;
    private final String kind;
    private final Runnable reload;
    private final Runnable afterDelete;

    LifecycleBar(GovernedService<?, ?, ?> service, Governed item, boolean editable, boolean admin, UUID meId, String kind,
                 Runnable reload, Runnable afterDelete) {
        this.service = service;
        this.item = item;
        this.id = item.getId();
        this.editable = editable;
        this.admin = admin;
        this.meId = meId;
        this.kind = kind;
        this.reload = reload;
        this.afterDelete = afterDelete;
        addClassName("ap-lifecycle");
        paint();
    }

    /** Whether the editor may change the content right now: not while a change is in review and not when retired. */
    static boolean contentLocked(GovernedService<?, ?, ?> service, Governed item) {
        return item.getLifecycle() == Lifecycle.RETIRED
                || service.openVersion(item.getId()).map(v -> v.getStatus() == ChangeStatus.PENDING).orElse(false);
    }

    private void paint() {
        Lifecycle state = item.getLifecycle();
        Optional<? extends ItemVersion> open = service.openVersion(id);
        String kindText = getTranslation("lifecycle.kind." + kind);
        Div actions = Dom.div("ap-lifecycle-actions");
        String text = null;
        String tone = "";

        if (state == Lifecycle.RETIRED) {
            text = getTranslation("lifecycle.banner.retired", kindText);
            tone = " ap-bad";
            if (admin) {
                actions.add(Dom.button("ap-btn ap-sm", VaadinIcon.REFRESH, getTranslation("lifecycle.restore"), () -> run(() -> service.restore(id), "lifecycle.restored")));
            }
            if (editable) {
                actions.add(confirmButton("lifecycle.delete", () -> run(() -> service.delete(id), "lifecycle.deleted", afterDelete)));
            }
        } else if (open.isPresent() && open.get().getStatus() == ChangeStatus.PENDING) {
            ItemVersion v = open.get();
            text = getTranslation("lifecycle.banner.pending", v.getVersionNumber(), v.getAuthor().getDisplayName());
            tone = " ap-warn";
            if (v.getAuthor().getId().equals(meId)) {
                actions.add(Dom.button("ap-btn ap-sm", getTranslation("lifecycle.withdraw"), () -> run(() -> service.withdraw(id), "lifecycle.withdrawnDone")));
            } else if (admin) {
                actions.add(Dom.button("ap-btn ap-sm ap-primary", getTranslation("lifecycle.review"), () -> UI.getCurrent().navigate("policies/approvals")));
            }
        } else if (open.isPresent()) {
            ItemVersion v = open.get();
            text = item.getLiveVersion() == 0 ? getTranslation("lifecycle.banner.newDraft", kindText)
                    : getTranslation("lifecycle.banner.draft", v.getVersionNumber(), item.getLiveVersion());
            tone = " ap-warn";
            if (editable) {
                actions.add(Dom.button("ap-btn ap-sm ap-primary", VaadinIcon.PAPERPLANE, getTranslation("lifecycle.submit"), this::openSubmit));
                if (item.getLiveVersion() > 0) {
                    actions.add(confirmButton("lifecycle.discard", () -> run(() -> service.discardDraft(id), "lifecycle.discarded")));
                } else {
                    actions.add(confirmButton("lifecycle.delete", () -> run(() -> service.delete(id), "lifecycle.deleted", afterDelete)));
                }
            }
        } else {
            List<? extends ItemVersion> history = service.history(id);
            if (!history.isEmpty() && history.get(0).getVersionNumber() > item.getLiveVersion()
                    && (history.get(0).getStatus() == ChangeStatus.REJECTED || history.get(0).getStatus() == ChangeStatus.WITHDRAWN)) {
                ItemVersion v = history.get(0);
                text = v.getStatus() == ChangeStatus.REJECTED
                        ? getTranslation("lifecycle.banner.rejected", v.getVersionNumber(), v.getApprover() == null ? "" : v.getApprover().getDisplayName(),
                                v.getDecisionComment() == null ? "" : v.getDecisionComment())
                        : getTranslation("lifecycle.banner.withdrawn", v.getVersionNumber());
                tone = v.getStatus() == ChangeStatus.REJECTED ? " ap-bad" : "";
            } else if (state == Lifecycle.DRAFT) {
                // A restored or never-started draft without an open version: the next save starts one
                text = getTranslation("lifecycle.banner.newDraft", kindText);
                tone = " ap-warn";
                if (editable && item.getLiveVersion() == 0) {
                    actions.add(confirmButton("lifecycle.delete", () -> run(() -> service.delete(id), "lifecycle.deleted", afterDelete)));
                }
            }
            if (state == Lifecycle.APPROVED && admin) {
                actions.add(confirmButton("lifecycle.retire", () -> run(() -> service.retire(id), "lifecycle.retiredDone")));
            }
        }

        Div status = Dom.div("ap-lifecycle-h", Dom.span("ap-pill" + statusTone(state), getTranslation("lifecycle." + state.name())));
        if (text != null) {
            Div banner = Dom.div("ap-banner" + tone, Dom.span(null, text));
            add(status, banner);
        } else {
            add(status);
        }
        if (actions.getChildren().findAny().isPresent()) {
            add(actions);
        }
    }

    static String statusTone(Lifecycle state) {
        return switch (state) {
            case APPROVED -> " ap-ok";
            case IN_REVIEW, DRAFT -> " ap-warn";
            case RETIRED -> " ap-bad";
        };
    }

    private NativeButton confirmButton(String key, Runnable action) {
        NativeButton b = Dom.button("ap-btn ap-sm", getTranslation(key), null);
        boolean[] armed = {false};
        b.addClickListener(e -> {
            if (!armed[0]) {
                armed[0] = true;
                b.setText(getTranslation("lifecycle.confirm"));
                return;
            }
            action.run();
        });
        return b;
    }

    private void run(Runnable action, String doneKey) {
        run(action, doneKey, reload);
    }

    private void run(Runnable action, String doneKey, Runnable then) {
        if (Feedback.run(this, action)) {
            Feedback.saved(this, doneKey);
            then.run();
        } else {
            reload.run();
        }
    }

    private void openSubmit() {
        Sheet sheet = new Sheet(getTranslation("lifecycle.submit.title"), getTranslation("lifecycle.submit.sub"));
        NativeTextArea note = new NativeTextArea("lifecycle-note", "", 4, 500);
        sheet.setBody(Dom.div("ap-sg", new FormField(getTranslation("lifecycle.submit.note"), "lifecycle-note", note, getTranslation("lifecycle.submit.noteHint"))));
        com.vaadin.flow.component.html.Span error = Dom.span("ap-error", "");
        error.setVisible(false);
        sheet.setFooter(Dom.button("ap-btn ap-ghost", getTranslation("action.cancel"), sheet::close),
                Dom.button("ap-btn ap-primary", getTranslation("lifecycle.submit.action"), () -> {
                    try {
                        service.submit(id, note.getValue());
                    } catch (InvalidInputException e) {
                        Feedback.error(getTranslation(e.getMessage(), e.getArgs()));
                        return;
                    } catch (org.springframework.security.access.AccessDeniedException e) {
                        Feedback.error(getTranslation("error.denied"));
                        return;
                    }
                    sheet.close();
                    Feedback.saved(this, "lifecycle.submitted");
                    reload.run();
                }));
        sheet.open();
    }
}
