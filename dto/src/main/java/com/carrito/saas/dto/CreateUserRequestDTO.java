package com.carrito.saas.dto;

import lombok.Data;

@Data
public class CreateUserRequestDTO {
	
	private String username;
    private String password;
    private String role; // OWNER o KITCHEN

}
