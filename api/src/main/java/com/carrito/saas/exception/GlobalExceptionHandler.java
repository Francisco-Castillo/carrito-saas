package com.carrito.saas.exception;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import jakarta.servlet.http.HttpServletRequest;

@RestControllerAdvice
public class GlobalExceptionHandler {

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

    // ERRORES GENERALES
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ErrorResponse> handleGeneral(
            Exception ex,
            HttpServletRequest request) {

        ex.printStackTrace(); // luego logging

        ErrorResponse error = new ErrorResponse(
                500,
                "Internal Server Error",
                "Ocurrió un error inesperado",
                request.getRequestURI()
        );

        return ResponseEntity.internalServerError().body(error);
    }

}
