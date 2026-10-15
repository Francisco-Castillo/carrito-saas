package com.carrito.saas.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	// BUSINESS (desde service)
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ErrorResponse> handleBusiness(
	        BusinessException ex,
	        HttpServletRequest request) {

	    HttpStatus status = ErrorMapper.map(ex.getType());

	    ErrorResponse error = new ErrorResponse(
	            status.value(),
	            status.getReasonPhrase(),
	            ex.getMessage(),
	            request.getRequestURI()
	    );

	    return ResponseEntity.status(status).body(error);
	}

    // VALIDACIONES (@Valid)
    @ExceptionHandler(org.springframework.web.bind.MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(
            org.springframework.web.bind.MethodArgumentNotValidException ex,
            HttpServletRequest request) {

        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(e -> e.getField() + " " + e.getDefaultMessage())
                .orElse("Datos inválidos");

        ErrorResponse error = new ErrorResponse(
                400,
                "Bad Request",
                message,
                request.getRequestURI()
        );

        return ResponseEntity.badRequest().body(error);
    }

    // ERRORES DEL PROPIO SPRING MVC (404, 405, 400, 415, ...)
    // Las excepciones de Spring ya describen su propio estado: la mayoría
    // implementa org.springframework.web.ErrorResponse
    // (getStatusCode() + getBody()), y el detalle y el estado se leen desde la
    // interfaz dentro del handler. Sin este handler caían en el catch-all de
    // Exception y la API respondía 500 por errores del cliente.
    //
    // La interfaz ErrorResponse NO es un Throwable, así que no puede ir en la
    // anotación: hay que listar las clases concretas. La lista de abajo es la
    // del propio ResponseEntityExceptionHandler de Spring (ver
    // ErrorResponseException, que cubre a ResponseStatusException y a todo lo
    // que se lance como ErrorResponseException) recortada a las que aplica a
    // esta API; una excepción nueva de Spring que implemente ErrorResponse
    // pero no esté listada seguiría cayendo al catch-all (500).
    @ExceptionHandler({
            org.springframework.web.servlet.resource.NoResourceFoundException.class,
            org.springframework.web.HttpRequestMethodNotSupportedException.class,
            org.springframework.web.method.annotation.MethodArgumentTypeMismatchException.class,
            org.springframework.web.bind.MissingServletRequestParameterException.class,
            org.springframework.web.multipart.support.MissingServletRequestPartException.class,
            org.springframework.http.converter.HttpMessageNotReadableException.class,
            org.springframework.http.converter.HttpMessageNotWritableException.class,
            org.springframework.web.HttpMediaTypeNotSupportedException.class,
            org.springframework.web.HttpMediaTypeNotAcceptableException.class,
            org.springframework.web.ErrorResponseException.class,
            org.springframework.web.server.ResponseStatusException.class,
            org.springframework.web.method.annotation.HandlerMethodValidationException.class,
            org.springframework.web.context.request.async.AsyncRequestTimeoutException.class
    })
    public ResponseEntity<ErrorResponse> handleSpringMvc(
            Exception ex,
            HttpServletRequest request) {

        HttpStatus status = HttpStatus.INTERNAL_SERVER_ERROR;
        String message = "Ocurrió un error inesperado";

        if (ex instanceof org.springframework.web.ErrorResponse errorResponse) {
            status = HttpStatus.valueOf(errorResponse.getStatusCode().value());

            String detail = errorResponse.getBody() != null
                    ? errorResponse.getBody().getDetail()
                    : null;
            message = (detail != null && !detail.isBlank())
                    ? detail
                    : status.getReasonPhrase();
        } else if (ex instanceof org.springframework.http.converter.HttpMessageNotReadableException
                // Cuerpo malformado: error del cliente (Spring 7 NO la marca
                // como ErrorResponse, a diferencia del resto de la familia).
                || ex instanceof org.springframework.web.bind.MissingRequestValueException
                || ex instanceof org.springframework.web.method.annotation.MethodArgumentTypeMismatchException) {
            status = HttpStatus.BAD_REQUEST;
            message = (ex.getMessage() != null && !ex.getMessage().isBlank())
                    ? ex.getMessage()
                    : status.getReasonPhrase();
        }

        ErrorResponse error = new ErrorResponse(
                status.value(),
                status.getReasonPhrase(),
                message,
                request.getRequestURI()
        );

        return ResponseEntity.status(status).body(error);
    }

    // ERRORES GENERALES
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(
            Exception ex,
            HttpServletRequest request) {

        log.error("Excepción no manejada en {}", request.getRequestURI(), ex);

        ErrorResponse error = new ErrorResponse(
                500,
                "Internal Server Error",
                "Ocurrió un error inesperado",
                request.getRequestURI()
        );

        return ResponseEntity.internalServerError().body(error);
    }

}
