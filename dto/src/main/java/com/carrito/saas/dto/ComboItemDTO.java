package com.carrito.saas.dto;

import java.math.BigDecimal;

import lombok.Data;

@Data
public class ComboItemDTO {
	
	private String productName;

    private BigDecimal quantity;

}
