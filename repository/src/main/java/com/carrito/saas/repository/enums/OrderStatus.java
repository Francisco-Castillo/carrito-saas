package com.carrito.saas.repository.enums;

public enum OrderStatus {

	NEW, PREPARING, READY, DELIVERED, CANCELLED;

	public boolean canTransitionTo(OrderStatus next) {

		switch (this) {

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
	
	public void validateTransition(OrderStatus next){

	    if(!canTransitionTo(next)){
	        throw new IllegalStateException(
	            "Transición inválida: " + this + " -> " + next
	        );
	    }

	}

}
