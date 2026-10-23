package com.agenticpilot.ui;

import com.agenticpilot.governance.ApprovalService;
import com.agenticpilot.governance.ApprovalService.ItemType;
import com.agenticpilot.governance.GovernedService;
import com.agenticpilot.governance.ItemVersion;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.Sheet;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

/** The versions of a skill or agent: number, state, author, date, approver, note, and what changed. */
class HistoryPanel extends Div {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    HistoryPanel(GovernedService<?, ?, ?> service, ApprovalService approvals, ItemType type, UUID itemId) {
        addClassNames("ap-card", "ap-panel");
        add(Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("history.title")), new com.vaadin.flow.component.html.Paragraph(getTranslation("history.sub")))));
        Div rows = Dom.div("ap-rows");
        List<? extends ItemVersion> versions = service.history(itemId);
        for (ItemVersion v : versions) {
            String who = v.getAuthor().getDisplayName();
            StringBuilder meta = new StringBuilder(getTranslation("history.meta", who, DAY.format(v.getCreatedAt().atZone(ZoneId.systemDefault()))));
            if (v.getApprover() != null) {
                meta.append(" · ").append(getTranslation("history.decidedBy", v.getApprover().getDisplayName()));
            }
            if (v.getNote() != null && !v.getNote().isBlank()) {
                meta.append(" · “").append(v.getNote()).append('”');
            }
            if (v.getDecisionComment() != null) {
                meta.append(" · ").append(getTranslation("history.comment", v.getDecisionComment()));
            }
            Div row = Dom.div("ap-row ap-cr", Dom.div("ap-row-main", Dom.span(null, "v" + v.getVersionNumber()), Dom.span("ap-hint", meta.toString())),
                    Dom.span("ap-pill" + tone(v), getTranslation("version.status." + v.getStatus().name())));
            row.add(Dom.button("ap-btn ap-sm", getTranslation("history.changes"), () -> showChanges(approvals, type, itemId, v)));
            rows.add(row);
        }
        if (versions.isEmpty()) {
            rows.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("history.empty"))));
        }
        add(rows);
    }

    private static String tone(ItemVersion v) {
        return switch (v.getStatus()) {
            case APPROVED -> " ap-ok";
            case REJECTED -> " ap-bad";
            case DRAFT, PENDING -> " ap-warn";
            case WITHDRAWN -> "";
        };
    }

    private void showChanges(ApprovalService approvals, ItemType type, UUID itemId, ItemVersion v) {
        Sheet sheet = new Sheet(getTranslation("history.changesTitle", v.getVersionNumber()), getTranslation("history.changesSub"));
        try {
            sheet.setBody(new DiffView(approvals.compare(type, itemId, v.getId())));
        } catch (RuntimeException e) {
            Feedback.error(getTranslation("chat.error"));
            return;
        }
        sheet.setFooter(Dom.button("ap-btn ap-primary", getTranslation("action.close"), sheet::close));
        sheet.open();
    }
}
