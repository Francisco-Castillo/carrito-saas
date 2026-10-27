package com.carrito.saas.dto;

import lombok.Data;

@Data
public class BranchDTO {

	private Long id;
	private String name;
	private String address;

	/** WhatsApp propio de la sucursal (E.164 sin '+'), o null si no tiene; ver {@code Branch#phone}. */
	private String phone;

	private Boolean active;
	private Long businessId;

}
