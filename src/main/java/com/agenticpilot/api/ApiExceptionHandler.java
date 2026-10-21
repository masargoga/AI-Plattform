package com.agenticpilot.api;

import com.agenticpilot.common.InvalidInputException;
import com.agenticpilot.common.Messages;
import com.agenticpilot.common.NotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Turns every failure of the API controllers into the one JSON error format. Details of unexpected errors stay in the log. */
@RestControllerAdvice(basePackageClasses = ApiExceptionHandler.class)
class ApiExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private static ResponseEntity<ApiError> body(HttpServletRequest request, HttpStatus status, String code, String message) {
        Object id = request.getAttribute(ApiAuthenticationFilter.REQUEST_ID);
        return ResponseEntity.status(status).body(new ApiError(code, message, id == null ? null : id.toString()));
    }

    @ExceptionHandler(ApiException.class)
    ResponseEntity<ApiError> api(ApiException e, HttpServletRequest request) {
        return body(request, e.status(), e.code(), e.getMessage());
    }

    @ExceptionHandler(InvalidInputException.class)
    ResponseEntity<ApiError> invalid(InvalidInputException e, HttpServletRequest request) {
        return body(request, HttpStatus.BAD_REQUEST, "invalid_request", Messages.english(e.getMessage(), e.getArgs()));
    }

    @ExceptionHandler(NotFoundException.class)
    ResponseEntity<ApiError> notFound(NotFoundException e, HttpServletRequest request) {
        return body(request, HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(AccessDeniedException.class)
    ResponseEntity<ApiError> denied(AccessDeniedException e, HttpServletRequest request) {
        return body(request, HttpStatus.FORBIDDEN, "forbidden", "This key may not do that.");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MethodArgumentTypeMismatchException.class})
    ResponseEntity<ApiError> unreadable(Exception e, HttpServletRequest request) {
        return body(request, HttpStatus.BAD_REQUEST, "invalid_request", "The request is not valid: check the JSON body and the ids.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<ApiError> method(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return body(request, HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed", "This method is not supported here.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<ApiError> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return body(request, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "unsupported_media_type", "Send the body as application/json.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<ApiError> unexpected(Exception e, HttpServletRequest request) {
        Object id = request.getAttribute(ApiAuthenticationFilter.REQUEST_ID);
        log.error("API request {} failed: {}", id, e.getClass().getName());
        return body(request, HttpStatus.INTERNAL_SERVER_ERROR, "internal_error", "The request could not be completed.");
    }
}
