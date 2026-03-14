package com.carrito.saas.dto.reportes;

import lombok.Data;

@Data
public class TopProductDTO {
	private String productName;
	private long quantity;

	public TopProductDTO(String productName, long quantity) {
		super();
		this.productName = productName;
		this.quantity = quantity;
	}

}
