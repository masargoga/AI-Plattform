package com.agenticpilot.ui;

import com.agenticpilot.governance.ApprovalService.Change;
import com.agenticpilot.ui.components.Dom;
import com.vaadin.flow.component.html.Div;
import java.util.List;

/** Changed fields as "label: old value to new value". An empty old value reads as a new value. */
class DiffView extends Div {

    DiffView(List<Change> changes) {
        addClassName("ap-diff");
        if (changes.isEmpty()) {
            add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("diff.none"))));
            return;
        }
        for (Change c : changes) {
            Div row = Dom.div("ap-diff-row", Dom.span("ap-diff-label", getTranslation("diff.field." + c.field())));
            Div values = Dom.div("ap-diff-values");
            if (!c.before().isEmpty()) {
                values.add(Dom.span("ap-diff-old", show(c.before())));
            }
            values.add(Dom.span("ap-diff-new", c.after().isEmpty() ? getTranslation("diff.empty") : show(c.after())));
            row.add(values);
            add(row);
        }
    }

    private String show(String value) {
        return switch (value) {
            case "true" -> getTranslation("diff.yes");
            case "false" -> getTranslation("diff.no");
            default -> value;
        };
    }
}
