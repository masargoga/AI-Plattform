package com.agenticpilot.ui;

import com.agenticpilot.governance.ApprovalService.Change;
import com.agenticpilot.ui.components.Dom;
import com.vaadin.flow.component.html.Div;
import java.util.List;

/** Changed fields as "label: old value to new value". An empty old value reads as a new value. */
class DiffView extends Div {

    DiffView(List<Change> changes) {
        addClassName("ap-changes");
        if (changes.isEmpty()) {
            add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("diff.none"))));
            return;
        }
        for (Change c : changes) {
            Div row = Dom.div("ap-diff-row", Dom.span("ap-diff-label", getTranslation("diff.field." + c.field())));
            Div values = Dom.div("ap-diff-values");
            if (!c.before().isEmpty()) {
                values.add(Dom.span("ap-diff-old", show(c.field(), c.before())));
            }
            values.add(Dom.span("ap-diff-new", c.after().isEmpty() ? getTranslation("diff.empty") : show(c.field(), c.after())));
            row.add(values);
            add(row);
        }
    }

    /** Enum names become the words the screens use elsewhere. */
    private String show(String field, String value) {
        String prefix = switch (field) {
            case "type" -> "skillType.";
            case "format" -> "output.";
            case "autonomy" -> "autonomy.";
            case "model" -> "model.";
            case "action" -> "ruleAction.";
            case "tools" -> "tool.";
            default -> null;
        };
        if (prefix != null) {
            StringBuilder out = new StringBuilder();
            for (String part : value.split(",\\s*")) {
                if (out.length() > 0) {
                    out.append(", ");
                }
                String text = getTranslation(prefix + part.strip());
                out.append(text.startsWith("!") && text.endsWith("!") ? part.strip() : text);
            }
            return out.toString();
        }
        if (field.equals("scope")) {
            return getTranslation("ALL".equals(value) ? "rules.allAgents" : "diff.scope.selected");
        }
        return switch (value) {
            case "true" -> getTranslation("diff.yes");
            case "false" -> getTranslation("diff.no");
            default -> value;
        };
    }
}
