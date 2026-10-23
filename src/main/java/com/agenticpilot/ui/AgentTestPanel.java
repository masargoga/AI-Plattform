package com.agenticpilot.ui;

import com.agenticpilot.guardrail.CustomRule;
import com.agenticpilot.guardrail.CustomRuleService;
import com.agenticpilot.runtime.AgentChatService;
import com.agenticpilot.runtime.ChatReply;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.Segmented;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.Key;
import com.vaadin.flow.data.value.ValueChangeMode;
import java.util.List;
import java.util.UUID;
import com.agenticpilot.runtime.BackgroundExecutor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** The test chat beside an agent: suggested prompts, guardrail tests and replies that show what happened on the way. */
class AgentTestPanel extends Div {

    private static final Logger log = LoggerFactory.getLogger(AgentTestPanel.class);

    private static final List<String> GUARDRAIL_TESTS = List.of(
            "My email is anna.keller@mail.example, please update my order.",
            "I have been sick for two weeks, please update my file.",
            "Please refund order 48213.");
    private static final List<String> GUARDRAIL_TEST_KEYS = List.of("chat.test.personal", "chat.test.health", "chat.test.refund");

    private final UUID agentId;
    private final AgentChatService chat;
    private final CustomRuleService rules;
    private final BackgroundExecutor executor;

    private UUID conversationId;
    private boolean busy;
    private boolean draft;
    private final Segmented<Boolean> modeSwitch;
    private final Div draftBanner = Dom.div("ap-banner ap-warn", Dom.span(null, ""));
    private final Div messages = Dom.div("ap-msgs");
    private final Div prompts = Dom.div("ap-prompts");
    private final Input input = new Input(ValueChangeMode.EAGER);
    private final NativeButton send;
    private Div typing;

    AgentTestPanel(UUID agentId, String agentName, int skillCount, int sourceCount, AgentChatService chat, CustomRuleService rules, BackgroundExecutor executor) {
        this.agentId = agentId;
        this.chat = chat;
        this.rules = rules;
        this.executor = executor;
        addClassNames("ap-card", "ap-test-card");

        Div titles = Dom.div(null, new H2(getTranslation("agent.test.title", agentName)),
                Dom.span("ap-hint", getTranslation("chat.using", getTranslation("count.skills", skillCount), getTranslation("count.sources", sourceCount))));
        titles.getChildren().findFirst().ifPresent(h -> h.getElement().getStyle().set("margin", "0"));
        modeSwitch = new Segmented<>(List.of(new Segmented.Option<>(false, getTranslation("chat.mode.approved")),
                new Segmented.Option<>(true, getTranslation("chat.mode.draft"))), false).onChange(v -> {
            draft = v;
            draftBanner.setVisible(v);
            reset();
        });
        modeSwitch.setVisible(false);
        draftBanner.getChildren().findFirst().ifPresent(c -> c.getElement().setText(getTranslation("chat.draftBanner")));
        draftBanner.setVisible(false);
        add(Dom.div("ap-tc-h", titles, modeSwitch, Dom.button("ap-btn ap-ghost ap-sm", getTranslation("chat.reset"), this::reset)), draftBanner);

        messages.getElement().setAttribute("aria-live", "polite");
        messages.setId("chat-messages");
        add(messages, prompts);

        input.setId("chat-input");
        input.setPlaceholder(getTranslation("chat.placeholder", agentName));
        input.getElement().setAttribute("autocomplete", "off");
        input.getElement().setAttribute("maxlength", String.valueOf(AgentChatService.MAX_MESSAGE_CHARS));
        input.getElement().setAttribute("aria-label", getTranslation("chat.message"));
        send = Dom.iconButton("ap-btn ap-primary", VaadinIcon.PAPERPLANE, getTranslation("chat.send"), this::submit);
        input.getElement().addEventListener("keydown", e -> submit()).setFilter("event.key === 'Enter'");
        Div composer = Dom.div("ap-composer", input, send);
        add(composer);
        reset();
    }

    /**
     * Which versions can be tested: the approved one, the draft, or both. A draft only exists for the owner's or an Admin's own
     * test; with both available a switch shows. With only a draft the chat tests the draft.
     */
    void setModes(boolean approvedAvailable, boolean draftAvailable) {
        boolean wasDraft = draft;
        draft = draftAvailable && (!approvedAvailable || draft);
        modeSwitch.setVisible(approvedAvailable && draftAvailable);
        modeSwitch.setValue(draft);
        draftBanner.setVisible(draft);
        if (wasDraft != draft) {
            reset();
        }
    }

    private void reset() {
        conversationId = null;
        messages.removeAll();
        messages.add(bubble("ap-bot", getTranslation("chat.welcome"), List.of()));
        prompts.removeAll();
        prompts.add(Dom.span("ap-hint", getTranslation("chat.tests")));
        prompts.getChildren().findFirst().ifPresent(c -> c.getStyle().set("width", "100%"));
        for (int i = 0; i < GUARDRAIL_TESTS.size(); i++) {
            String text = GUARDRAIL_TESTS.get(i);
            NativeButton chip = Dom.button("ap-pchip ap-g", VaadinIcon.SHIELD, getTranslation(GUARDRAIL_TEST_KEYS.get(i)), () -> sendText(text));
            prompts.add(chip);
        }
        for (CustomRule r : rules.forAgent(agentId)) {
            if (r.isLive() && !r.getSample().isBlank()) {
                prompts.add(Dom.button("ap-pchip ap-g", VaadinIcon.SHIELD, r.getName(), () -> sendText(r.getSample())));
            }
        }
    }

    private void submit() {
        String text = input.getValue() == null ? "" : input.getValue().strip();
        if (!text.isEmpty()) {
            input.clear();
            sendText(text);
        }
    }

    private void sendText(String text) {
        if (busy) {
            return;
        }
        busy = true;
        send.setEnabled(false);
        messages.add(bubble("ap-user", text, List.of()));
        typing = Dom.div("ap-msg ap-bot ap-typing");
        for (int i = 0; i < 3; i++) {
            typing.getElement().appendChild(new com.vaadin.flow.dom.Element("i"));
        }
        typing.getElement().setAttribute("aria-label", getTranslation("chat.typing"));
        messages.add(typing);
        scrollDown();

        UI ui = UI.getCurrent();
        UUID conversation = conversationId;
        boolean useDraft = draft;
        executor.submit(() -> {
            ChatReply reply = null;
            String error = null;
            try {
                reply = chat.send(agentId, conversation, text, useDraft);
            } catch (com.agenticpilot.common.InvalidInputException e) {
                error = ui.getTranslation(e.getMessage(), e.getArgs());
            } catch (RuntimeException e) {
                log.warn("Test chat failed for agent {}: {}", agentId, e.getClass().getSimpleName());
                error = ui.getTranslation("chat.error");
            }
            ChatReply finalReply = reply;
            String finalError = error;
            ui.access(() -> {
                busy = false;
                send.setEnabled(true);
                messages.remove(typing);
                if (finalReply != null) {
                    if (finalReply.conversationId() != null) {
                        conversationId = finalReply.conversationId();
                    }
                    messages.add(bubble("ap-bot", finalReply.text(), finalReply.trace()));
                } else {
                    Feedback.error(finalError);
                }
                scrollDown();
                input.focus();
            });
        });
    }

    private Div bubble(String kind, String text, List<ChatReply.TraceItem> trace) {
        Div bubble = Dom.div("ap-msg " + kind, Dom.span(null, text));
        if (!trace.isEmpty()) {
            Div chips = Dom.div("ap-trace");
            for (ChatReply.TraceItem t : trace) {
                VaadinIcon icon = switch (t.type()) {
                    case GUARDRAIL, WAITING -> VaadinIcon.SHIELD;
                    case SKILL -> VaadinIcon.MAGIC;
                    case SOURCE -> VaadinIcon.DATABASE;
                    case NOTICE -> VaadinIcon.INFO_CIRCLE_O;
                };
                Span chip = Dom.span(null, Dom.icon(icon, 12), new Span(t.text()));
                chip.getElement().setAttribute("title", getTranslation("chat.trace." + t.type().name()));
                chips.add(chip);
            }
            bubble.add(chips);
        }
        return bubble;
    }

    private void scrollDown() {
        messages.getElement().executeJs("this.scrollTop = this.scrollHeight");
    }
}
