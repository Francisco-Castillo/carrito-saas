package com.carrito.saas.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class UserResponseDTO {
	
	private Long id;
    private String username;
    private String role;

}
