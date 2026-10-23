package com.agenticpilot.source;

/**
 * What an agent does when a source is in error or too old. USE_LAST_COPY keeps answering from the last good content;
 * STOP_AND_REPORT leaves the source out and says so in the answer.
 */
public enum OnFailure { USE_LAST_COPY, STOP_AND_REPORT }
