package com.agenticpilot.ui;

import com.agenticpilot.run.AgentRun;
import com.agenticpilot.run.AgentRunStep;
import com.agenticpilot.run.RunService;
import com.agenticpilot.run.RunStatus;
import com.agenticpilot.ui.components.Dom;
import com.agenticpilot.ui.components.Feedback;
import com.agenticpilot.ui.components.FormField;
import com.agenticpilot.ui.components.Segmented;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Input;
import com.vaadin.flow.component.html.NativeButton;
import com.vaadin.flow.component.html.Paragraph;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.shared.Registration;
import java.util.List;
import java.util.UUID;

/**
 * Run an agent on an input and watch it work. The page polls the database while a run is going, so the timeline stays
 * live and another browser tab or replica shows the same run. A run keeps going when the page is closed.
 */
class RunPanel extends Div {

    private static final int POLL_MILLIS = 1000;

    private final UUID agentId;
    private final RunService runService;

    private String inputLabel = "Task";
    private String goal = "";
    private boolean canRun;
    private boolean draft;
    private final Segmented<Boolean> modeSwitch;
    private final Div draftBanner = Dom.div("ap-banner ap-warn", Dom.span(null, ""));
    private final Div form = Dom.div("ap-sg");
    private final Input input = new Input(com.vaadin.flow.data.value.ValueChangeMode.ON_CHANGE);
    private final NativeButton start;
    private final Div current = Dom.div("ap-run-current");
    private final Div history = Dom.div("ap-rows");
    private UUID shownRun;
    private Registration poll;

    RunPanel(UUID agentId, RunService runService) {
        this.agentId = agentId;
        this.runService = runService;
        addClassNames("ap-card", "ap-test-card", "ap-run-panel");

        modeSwitch = new Segmented<>(List.of(new Segmented.Option<>(false, getTranslation("chat.mode.approved")),
                new Segmented.Option<>(true, getTranslation("chat.mode.draft"))), false).onChange(v -> {
            draft = v;
            draftBanner.setVisible(v);
        });
        modeSwitch.setVisible(false);
        draftBanner.getChildren().findFirst().ifPresent(c -> c.getElement().setText(getTranslation("run.draftBanner")));
        draftBanner.setVisible(false);

        input.setId("run-input");
        input.getElement().setAttribute("maxlength", "1000");
        input.getElement().addEventListener("keydown", e -> startRun()).setFilter("event.key === 'Enter'");
        start = Dom.button("ap-btn ap-primary", VaadinIcon.PLAY, getTranslation("run.start"), this::startRun);
        add(Dom.div("ap-tc-h", Dom.div(null, new H2(getTranslation("run.title")))), Dom.div("ap-tc-mode", modeSwitch), draftBanner, form, current,
                Dom.div("ap-run-history", new H2(getTranslation("run.history")), history));
        showLatest();
    }

    /** Called when the page is built or rebuilt: the label and goal come from the agent's current configuration. */
    void configure(String inputLabel, String goal, boolean canRun, boolean approvedAvailable, boolean draftAvailable) {
        this.inputLabel = inputLabel == null || inputLabel.isBlank() ? "Task" : inputLabel;
        this.goal = goal == null ? "" : goal;
        this.canRun = canRun;
        draft = draftAvailable && (!approvedAvailable || draft);
        modeSwitch.setVisible(approvedAvailable && draftAvailable);
        modeSwitch.setValue(draft);
        draftBanner.setVisible(draft);
        form.removeAll();
        Paragraph goalText = new Paragraph(this.goal.isBlank() ? getTranslation("run.noGoal") : getTranslation("run.goal", this.goal));
        goalText.addClassName("ap-hint");
        input.setPlaceholder(getTranslation("run.input.placeholder"));
        input.setEnabled(canRun);
        start.setEnabled(canRun);
        form.add(goalText, new FormField(this.inputLabel, "run-input", input, canRun ? null : getTranslation("run.cannotRun")), start);
        refreshHistory();
    }

    // ----- starting and stopping -----

    private void startRun() {
        if (!canRun || input.getValue() == null || input.getValue().isBlank()) {
            if (canRun) {
                Feedback.error(getTranslation("run.error.inputRequired"));
            }
            return;
        }
        UUID[] id = new UUID[1];
        if (Feedback.run(this, () -> id[0] = runService.start(agentId, input.getValue(), draft))) {
            input.clear();
            shownRun = id[0];
            Feedback.saved(this, "run.started");
            render();
            startPolling();
        }
    }

    // ----- showing -----

    private void showLatest() {
        try {
            List<AgentRun> latest = runService.forAgent(agentId, 1);
            if (!latest.isEmpty()) {
                shownRun = latest.get(0).getId();
            }
        } catch (RuntimeException e) {
            shownRun = null; // not allowed to see runs of this agent: the panel stays empty
        }
        render();
    }

    private void render() {
        current.removeAll();
        if (shownRun == null) {
            current.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("run.empty"))));
            refreshHistory();
            return;
        }
        AgentRun run;
        List<AgentRunStep> steps;
        try {
            run = runService.get(shownRun);
            steps = runService.steps(shownRun);
        } catch (RuntimeException e) {
            shownRun = null;
            render();
            return;
        }
        boolean active = run.getStatus() != RunStatus.FINISHED;
        Div head = Dom.div("ap-run-head", statusPill(run),
                Dom.span("ap-hint", getTranslation("run.progress", run.getStepCount(), run.getMaxSteps(), run.getTokensIn() + run.getTokensOut(), run.getMaxTokens())));
        if (active) {
            head.add(Dom.button("ap-btn ap-sm", VaadinIcon.STOP, getTranslation("run.stop"), () -> {
                if (Feedback.run(this, () -> runService.stop(run.getId()))) {
                    Feedback.saved(this, "run.stopRequested");
                }
            }));
        }
        current.add(head);
        if (run.isDraft()) {
            current.add(Dom.span("ap-pill ap-warn", getTranslation("run.wasDraft")));
        }
        if (run.getInput() != null) {
            current.add(Dom.div("ap-hint", Dom.span(null, getTranslation("run.inputWas", inputLabel, run.getInput()))));
        }
        if (run.getStatus() == RunStatus.WAITING_FOR_APPROVAL) {
            Div banner = Dom.div("ap-banner ap-warn", Dom.span(null, getTranslation("run.waiting")),
                    Dom.button("ap-btn ap-sm", getTranslation("run.openAudit"), () -> UI.getCurrent().navigate("policies/audit")));
            current.add(banner);
        }
        Div timeline = Dom.div("ap-timeline");
        for (AgentRunStep s : steps) {
            timeline.add(stepRow(s));
        }
        if (steps.isEmpty()) {
            timeline.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation(active ? "run.working" : "run.noSteps"))));
        }
        current.add(timeline);
        if (run.isFinished()) {
            Div result = Dom.div("ap-run-result");
            if (run.getResult() != null) {
                result.add(new H2(getTranslation("run.result")), Dom.div("ap-run-text", Dom.span(null, run.getResult())));
            }
            if (run.getDetail() != null && !run.getDetail().isBlank()) {
                result.add(Dom.div("ap-hint", Dom.span(null, run.getDetail())));
            }
            current.add(result);
        }
        refreshHistory();
    }

    private Span statusPill(AgentRun run) {
        if (run.getStatus() != RunStatus.FINISHED) {
            return Dom.span("ap-pill ap-warn", run.getStatus() == RunStatus.RUNNING ? Dom.span("ap-spin") : new Span(),
                    Dom.span(null, getTranslation("run.status." + run.getStatus().name())));
        }
        String tone = switch (run.getStopReason()) {
            case DONE -> " ap-ok";
            case CANNOT_COMPLETE, STOPPED_BY_USER -> "";
            default -> " ap-bad";
        };
        return Dom.span("ap-pill" + tone, getTranslation("run.stop." + run.getStopReason().name()));
    }

    private Div stepRow(AgentRunStep s) {
        String type = s.getType().name();
        VaadinIcon icon = switch (s.getType()) {
            case MODEL -> VaadinIcon.LIGHTBULB;
            case SKILL -> VaadinIcon.MAGIC;
            case TOOL_CALL -> VaadinIcon.COG;
            case TOOL_RESULT -> VaadinIcon.DATABASE;
            case REFUSED -> VaadinIcon.BAN;
            case GUARDRAIL -> VaadinIcon.SHIELD;
            case PENDING -> VaadinIcon.CLOCK;
            case FINISH -> VaadinIcon.CHECK;
        };
        String title = switch (s.getType()) {
            case TOOL_CALL, TOOL_RESULT, REFUSED, SKILL -> getTranslation("run.step." + type, s.getTool() == null ? "" : s.getTool());
            default -> getTranslation("run.step." + type);
        };
        Div body = Dom.div("ap-row-main", Dom.span(null, title));
        body.getChildren().findFirst().ifPresent(c -> c.getElement().getStyle().set("font-weight", "600"));
        String detail = s.getType() == com.agenticpilot.run.StepType.TOOL_CALL ? s.getArguments() : s.getContent();
        if (detail != null && !detail.isBlank()) {
            Span text = Dom.span(s.getType() == com.agenticpilot.run.StepType.TOOL_CALL ? "ap-run-code" : "ap-run-text-small", detail);
            body.add(text);
        }
        Div row = Dom.div("ap-row ap-step ap-step-" + type.toLowerCase().replace('_', '-'), Dom.span("ap-ico", Dom.icon(icon, 16)), body);
        if (s.getDurationMs() > 0 || s.getTokensIn() > 0) {
            row.add(Dom.span("ap-hint", getTranslation("run.stepMeta", s.getTokensIn() + s.getTokensOut(), s.getDurationMs())));
        }
        return row;
    }

    private void refreshHistory() {
        history.removeAll();
        List<AgentRun> latest;
        try {
            latest = runService.forAgent(agentId, 10);
        } catch (RuntimeException e) {
            return;
        }
        for (AgentRun r : latest) {
            NativeButton open = Dom.button("ap-linkish", r.getInput() == null ? getTranslation("run.noInputStored") : r.getInput(), () -> {
                shownRun = r.getId();
                render();
                if (r.getStatus() != RunStatus.FINISHED) {
                    startPolling();
                }
            });
            Div row = Dom.div("ap-row ap-cr", Dom.div("ap-row-main", open, Dom.span("ap-hint", getTranslation("run.historyMeta", r.getActor(),
                    Labels.ago(this, r.getStartedAt(), "time.never")))), statusPill(r));
            history.add(row);
        }
        if (latest.isEmpty()) {
            history.add(Dom.div("ap-rowempty", Dom.span(null, getTranslation("run.noHistory"))));
        }
    }

    // ----- live updates -----

    private void startPolling() {
        UI ui = UI.getCurrent();
        if (ui == null) {
            return;
        }
        ui.setPollInterval(POLL_MILLIS);
        if (poll == null) {
            poll = ui.addPollListener(e -> {
                render();
                if (shownRun == null || isFinished(shownRun)) {
                    stopPolling();
                }
            });
        }
    }

    private boolean isFinished(UUID id) {
        try {
            return runService.get(id).isFinished();
        } catch (RuntimeException e) {
            return true;
        }
    }

    private void stopPolling() {
        UI ui = UI.getCurrent();
        if (poll != null) {
            poll.remove();
            poll = null;
        }
        if (ui != null) {
            ui.setPollInterval(-1);
        }
    }

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        if (shownRun != null && !isFinished(shownRun)) {
            startPolling();
        }
    }

    @Override
    protected void onDetach(DetachEvent event) {
        stopPolling();
        super.onDetach(event);
    }
}
