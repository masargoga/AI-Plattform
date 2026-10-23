package com.agenticpilot.run;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** Settings of agent runs. The per-agent limits live on the agent; these are the program-wide guards. */
@ConfigurationProperties("agenticpilot.run")
public record RunProperties(
        @DefaultValue("8000") int maxResultChars,
        @DefaultValue("3") int maxPerAgent,
        @DefaultValue("10") int maxTotal,
        @DefaultValue("3") int maxRefused,
        @DefaultValue("5") int staleMinutes,
        @DefaultValue("1000") long approvalPollMillis,
        @DefaultValue("5") int maxApprovalsPerRun) {
}
