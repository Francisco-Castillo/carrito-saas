package com.carrito.saas.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserLoginInfoDTO {
	
	private String role;
    private String restaurantSlug;

}
