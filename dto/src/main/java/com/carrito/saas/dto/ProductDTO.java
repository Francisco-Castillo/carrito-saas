package com.carrito.saas.dto;

import lombok.Data;

@Data
public class ProductDTO {
	
	private Long id;

    private String name;

    private String description;

    private Double price;

    private Boolean active;

    private Long categoryId;

}
