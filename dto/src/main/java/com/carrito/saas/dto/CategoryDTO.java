package com.carrito.saas.dto;

import lombok.Data;

@Data
public class CategoryDTO {

	private Long id;
	private String name;
	private Integer order;
	private Boolean active;
	private Long businessId;

}
