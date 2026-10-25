package com.carrito.saas.repository.enums;

public enum OrderStatus {

	NEW, PREPARING, READY, DELIVERED, CANCELLED,

	/**
	 * Pedido con pago por TRANSFERENCIA aún no verificado por el operador.
	 * No entra al KDS (findActiveOrders lo excluye): la consola de Pedidos
	 * lo muestra y al confirmar el pago transiciona a NEW.
	 */
	PENDING_PAYMENT;

	public boolean canTransitionTo(OrderStatus next) {

		switch (this) {

		case PENDING_PAYMENT:
			return next == NEW || next == CANCELLED;

		case NEW:
			return next == PREPARING || next == CANCELLED;

		case PREPARING:
			return next == READY || next == CANCELLED;

		case READY:
			return next == DELIVERED;

		default:
			return false;
		}
	}

	public String label() {
		switch (this) {
		case PENDING_PAYMENT:
			return "Pago pendiente";
		case NEW:
			return "Nuevo";
		case PREPARING:
			return "Preparando";
		case READY:
			return "Listo";
		case DELIVERED:
			return "Entregado";
		case CANCELLED:
			return "Cancelado";
		default:
			return "";
		}
	}
}
