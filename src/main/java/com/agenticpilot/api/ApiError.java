package com.agenticpilot.api;

import io.swagger.v3.oas.annotations.media.Schema;

/** The one error format of the API. */
@Schema(description = "Error returned for every failed request")
public record ApiError(
        @Schema(description = "Stable machine-readable code", example = "unauthorized") String code,
        @Schema(description = "Human-readable explanation", example = "The API key is missing or not valid.") String message,
        @Schema(description = "Id to quote when asking for help; also sent as the X-Request-Id header") String requestId) {
}
