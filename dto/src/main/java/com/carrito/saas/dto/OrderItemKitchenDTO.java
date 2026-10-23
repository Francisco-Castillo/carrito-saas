package com.carrito.saas.dto;

import java.util.ArrayList;
import java.util.List;

import lombok.Data;

@Data
public class OrderItemKitchenDTO {
	
	private String productName;

    private Integer quantity;

    /**
     * Opciones elegidas del ítem, en formato legible "Grupo: Opción"
     * (odd/tasks/product-options.md S5), en el orden en que el dueño definió
     * los grupos. Vacío cuando el producto no tiene opciones.
     */
    private List<String> selectedOptions = new ArrayList<>();

	/** Preferencias del cliente ("sin mayonesa"): el KDS las muestra
	 * debajo del ítem para que cocina las respete. */
	private String notes;

}
