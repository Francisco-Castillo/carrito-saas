package com.carrito.saas.exception;

public class BusinessException extends RuntimeException {

	private static final long serialVersionUID = 1L;
	private final ErrorType type;

    public BusinessException(String message, ErrorType type) {
        super(message);
        this.type = type;
    }

    public ErrorType getType() {
        return type;
    }

}
