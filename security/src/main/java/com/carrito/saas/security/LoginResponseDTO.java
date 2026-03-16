package com.carrito.saas.security;

import lombok.Data;

@Data
public class LoginResponseDTO {
	private String token;
	private String role;
	private String restaurantSlug;

	public LoginResponseDTO(String token) {
		super();
		this.token = token;
	}

	public LoginResponseDTO(String token, String role, String restaurantSlug) {
		super();
		this.token = token;
		this.role = role;
		this.restaurantSlug = restaurantSlug;
	}

	

}
