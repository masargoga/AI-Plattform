package com.agenticpilot.ui;

import java.util.List;

/** Starting points in the new-agent sheet. Skills and sources are matched by name when they exist. */
enum AgentTemplate {
    SUPPORT("support", "Support Helper", "Answers customer questions from your help content.",
            List.of("Answer from docs", "Escalate to human"), List.of("Help Center")),
    RESEARCH("research", "Research Helper", "Builds short briefs from reports and the web.",
            List.of("Web research brief", "Summarize thread"), List.of("Market reports")),
    CONTRACTS("contracts", "Contract Helper", "Flags risky clauses and explains them simply.",
            List.of("Clause check", "Plain-language rewrite"), List.of("Contracts 2026")),
    BLANK("blank", "", "", List.of(), List.of());

    final String key;
    final String name;
    final String job;
    final List<String> skillNames;
    final List<String> sourceNames;

    AgentTemplate(String key, String name, String job, List<String> skillNames, List<String> sourceNames) {
        this.key = key;
        this.name = name;
        this.job = job;
        this.skillNames = skillNames;
        this.sourceNames = sourceNames;
    }
}
