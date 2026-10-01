package com.accenture.franchises.api;

import com.accenture.franchises.domain.InventoryException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ServerWebInputException;
import software.amazon.awssdk.core.exception.SdkException;
import reactor.core.Exceptions;

@RestControllerAdvice
public class ApiErrorHandler {
    private static final Logger LOG = LoggerFactory.getLogger(ApiErrorHandler.class);

    @ExceptionHandler(InventoryException.class)
    ProblemDetail domain(InventoryException error) {
        return ProblemDetail.forStatusAndDetail(error.isConflict() ? HttpStatus.CONFLICT : HttpStatus.NOT_FOUND,
                error.getMessage());
    }

    @ExceptionHandler(WebExchangeBindException.class)
    ProblemDetail validation(WebExchangeBindException error) {
        var problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "La solicitud contiene campos inválidos.");
        problem.setProperty("errors", error.getFieldErrors().stream()
                .map(field -> new FieldError(field.getField(), field.getDefaultMessage())).toList());
        return problem;
    }
    private record FieldError(String field, String message) {}

    @ExceptionHandler(ServerWebInputException.class)
    ProblemDetail malformed(ServerWebInputException error) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, "Verifica el JSON y los identificadores UUID.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception error) {
        Throwable cause = Exceptions.unwrap(error);
        LOG.error("Request failed", cause);
        boolean unavailable = cause instanceof SdkException;
        return ProblemDetail.forStatusAndDetail(unavailable ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.INTERNAL_SERVER_ERROR,
                unavailable ? "La persistencia no está disponible. Reintenta más tarde." : "Ocurrió un error inesperado.");
    }
}
