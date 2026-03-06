package com.carrito.saas.dto;

import java.util.List;

import lombok.Data;

@Data
public class MenuDTO {
	
	private BusinessDTO business;

    private List<CategoryDTO> categories;

    private List<ProductDTO> products;

}
