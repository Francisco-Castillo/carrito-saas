package com.carrito.saas.service.interfaces;

import com.carrito.saas.dto.CreateUserRequestDTO;
import com.carrito.saas.dto.UserResponseDTO;

public interface IUserService {
	
	UserResponseDTO createUser(String restaurantSlug, CreateUserRequestDTO request);

}
