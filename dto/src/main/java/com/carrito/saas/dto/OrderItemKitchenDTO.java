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

}
