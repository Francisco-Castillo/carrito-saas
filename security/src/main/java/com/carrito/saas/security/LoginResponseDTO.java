package com.carrito.saas.security;

import lombok.Data;

@Data
public class LoginResponseDTO {
	private String token;

	public LoginResponseDTO(String token) {
		super();
		this.token = token;
	}
	
	

}
