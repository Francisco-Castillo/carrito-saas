package com.carrito.saas.dto;

import lombok.Data;

@Data
public class BranchDTO {

	private Long id;
	private String name;
	private String address;
	private Boolean active;
	private Long businessId;

}
