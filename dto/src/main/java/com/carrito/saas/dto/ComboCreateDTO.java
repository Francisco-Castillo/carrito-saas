package com.carrito.saas.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.Data;

@Data
public class ComboCreateDTO {
	
	private String name;
	private BigDecimal price;
	private List<ComboProductDTO> products;

}
