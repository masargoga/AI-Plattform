package com.agenticpilot.ui;

import com.agenticpilot.agent.AgentKind;
import com.agenticpilot.api.ApiKey;
import com.agenticpilot.api.ApiKeyService;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.FormField;
import com.agenticpilot.ui.components.Sheet;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Pre;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** API keys of an agent: create (shown once), list and revoke, plus a call example. */
class ApiAccessPanel extends Div {

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final UUID agentId;
    private final AgentKind kind;
    private final ApiKeyService keys;

    ApiAccessPanel(UUID agentId, AgentKind kind, ApiKeyService keys) {
        this.agentId = agentId;
        this.kind = kind;
        this.keys = keys;
        addClassNames("ap-card", "ap-panel");
        refresh();
    }

    private void refresh() {
        removeAll();
        Div head = Dom.div("ap-panel-h", Dom.div(null, new H2(getTranslation("api.title")),
                new Paragraph(getTranslation(kind == AgentKind.EXTERNAL ? "api.sub.external" : "api.sub.managed"))),
                Dom.button("ap-btn ap-sm", VaadinIcon.PLUS, getTranslation("api.create"), this::openCreate));
        add(head);

        List<ApiKey> list = keys.list(agentId);
        Div rows = Dom.div("ap-rows");
        if (list.isEmpty()) {
            rows.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("api.empty"))));
        }
        for (ApiKey k : list) {
            Span name = Dom.span(null, k.getName());
            name.getStyle().set("font-weight", "600");
            String used = k.getLastUsedAt() == null ? getTranslation("api.neverUsed")
                    : getTranslation("api.lastUsed", Labels.ago(this, k.getLastUsedAt(), "time.never"));
            Span meta = Dom.span("ap-hint", "ap_" + k.getPrefix() + "… · " + getTranslation("api.created", DAY.format(k.getCreatedAt().atZone(ZoneId.systemDefault()))) + " · " + used);
            Div row = Dom.div("ap-row ap-cr", Dom.span("ap-ico", Dom.icon(VaadinIcon.KEY, 18)), Dom.div("ap-row-main", name, meta));
            if (k.isRevoked()) {
                row.add(Dom.span("ap-pill ap-bad", getTranslation("api.revoked")));
            } else {
                NativeButton revoke = Dom.button("ap-btn ap-sm", getTranslation("api.revoke"), null);
                boolean[] armed = {false};
                revoke.addClickListener(e -> {
                    if (!armed[0]) {
                        armed[0] = true;
                        revoke.setText(getTranslation("api.revoke.confirm"));
                        return;
                    }
                    if (Feedback.run(this, () -> keys.revoke(k.getId()))) {
                        Feedback.saved(this, "api.revoked.done");
                    }
                    refresh();
                });
                row.add(revoke);
            }
            rows.add(row);
        }
        add(rows, example(null));
    }

    private void openCreate() {
        Sheet sheet = new Sheet(getTranslation("api.create.title"), getTranslation("api.create.sub"));
        Input name = FormField.textInput("api-key-name", "", 80);
        name.setPlaceholder(getTranslation("api.create.placeholder"));
        sheet.setBody(Dom.div("ap-sg", new FormField(getTranslation("api.create.name"), "api-key-name", name, null)));
        sheet.setFooter(Dom.button("ap-btn ap-ghost", getTranslation("action.cancel"), sheet::close),
                Dom.button("ap-btn ap-primary", getTranslation("api.create.action"), () -> {
                    ApiKeyService.CreatedKey[] created = new ApiKeyService.CreatedKey[1];
                    if (Feedback.run(sheet, () -> created[0] = keys.create(agentId, name.getValue()))) {
                        showToken(sheet, created[0].token());
                        refresh();
                    }
                }));
        sheet.open();
    }

    /** The only time the key is visible. It cannot be read again; a lost key is replaced by creating a new one. */
    private void showToken(Sheet sheet, String token) {
        Input value = new Input();
        value.setValue(token);
        value.getElement().setAttribute("readonly", true);
        value.setId("api-key-token");
        value.getElement().setAttribute("aria-label", getTranslation("api.token.label"));
        value.getStyle().set("flex", "1").set("min-width", "0").set("font-family", "var(--ap-font-mono)");
        NativeButton copy = Dom.button("ap-btn", VaadinIcon.COPY, getTranslation("api.token.copy"), null);
        copy.addClickListener(e -> {
            copy.getElement().executeJs("navigator.clipboard && navigator.clipboard.writeText($0)", token);
            Feedback.saved(sheet, "api.token.copied");
        });
        Div line = Dom.div(null, value, copy);
        line.getStyle().set("display", "flex").set("gap", "8px");
        sheet.setBody(Dom.div("ap-sg", Dom.lock(getTranslation("api.token.once")), new FormField(getTranslation("api.token.label"), "api-key-token", line, null),
                new FormField(getTranslation("api.example"), null, example(token), null)));
        sheet.setFooter(Dom.button("ap-btn ap-primary", getTranslation("api.token.done"), sheet::close));
    }

    private Div example(String token) {
        String key = token == null ? "<API key>" : token;
        String call = kind == AgentKind.EXTERNAL
                ? "curl -X POST {ORIGIN}/api/v1/guardrails/check \\\n  -H \"Authorization: Bearer " + key + "\" \\\n  -H \"Content-Type: application/json\" \\\n  -d '{\"agentId\":\"" + agentId + "\",\"text\":\"Text to check\"}'"
                : "curl -X POST {ORIGIN}/api/v1/agents/" + agentId + "/messages \\\n  -H \"Authorization: Bearer " + key + "\" \\\n  -H \"Content-Type: application/json\" \\\n  -d '{\"message\":\"Hello\"}'";
        Pre pre = new Pre(call);
        pre.addClassName("ap-result");
        pre.getStyle().set("font-family", "var(--ap-font-mono)").set("font-size", "12.5px").set("overflow-x", "auto").set("margin", "0");
        pre.getElement().executeJs("this.textContent = this.textContent.replaceAll('{ORIGIN}', location.origin)");
        Anchor docs = new Anchor("/v3/api-docs", getTranslation("api.docs"));
        docs.setTarget("_blank");
        docs.getElement().setAttribute("rel", "noopener");
        return Dom.div("ap-field", pre, docs);
    }
}
