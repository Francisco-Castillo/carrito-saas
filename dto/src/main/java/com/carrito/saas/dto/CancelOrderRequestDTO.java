package com.carrito.saas.dto;

import lombok.Data;

@Data
public class CancelOrderRequestDTO {

	private Long reasonId;
	private String note;

}
