package com.agenticpilot.governance;

/**
 * State of a skill or agent as a whole. Only APPROVED items are used by the runtime, the API and the pickers.
 * A new item is DRAFT (or IN_REVIEW once submitted) until its first version is approved.
 */
public enum Lifecycle { DRAFT, IN_REVIEW, APPROVED, RETIRED }
