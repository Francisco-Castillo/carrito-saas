package com.carrito.saas.dto;

import com.carrito.saas.repository.enums.RequestType;

import lombok.Data;

@Data
public class TableRequestDTO {
	
	private String tableToken;

    private RequestType requestType;

}
