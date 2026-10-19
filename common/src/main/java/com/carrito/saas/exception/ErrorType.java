package com.carrito.saas.exception;

public enum ErrorType {
	VALIDATION,
	NOT_FOUND,
	CONFLICT,
	UNAUTHORIZED,
	FORBIDDEN,
	/** Falla de infraestructura propia del backend (disco, IO): 500. */
	INTERNAL_ERROR
}
