package com.carrito.saas.dto;

import lombok.Data;

@Data
public class CategoryDTO {

	private Long id;
	private String name;

	/**
	 * Lugar de la categoría en la lista del dueño.
	 *
	 * <p>En el alta ({@code POST /api/categorias}) este campo se <strong>ignora</strong>:
	 * el servidor asigna {@code max(orden) + 1} para que la categoría nueva
	 * nazca última, sin depender de lo que calcule el cliente — mandar
	 * "filas + 1" repetía un número en cuanto había una categoría borrada en
	 * el medio, y la nueva quedaba en el medio en vez de al final.</p>
	 *
	 * <p>En la edición ({@code PUT /api/categorias/{id}}) sí se aplica cuando
	 * viene (el servicio sólo toca los campos no nulos), y el reordenamiento
	 * del panel usa su propia ruta: {@code PUT /api/categorias/orden}.</p>
	 */
	private Integer order;

	private Boolean active;
	private Long businessId;

}
