package com.carrito.saas.service.interfaces;

import com.carrito.saas.dto.CreateUserRequestDTO;
import com.carrito.saas.dto.UserLoginInfoDTO;
import com.carrito.saas.dto.UserResponseDTO;

public interface IUserService {
	
	UserResponseDTO createUser(String restaurantSlug, CreateUserRequestDTO request);
	UserLoginInfoDTO getLoginInfo(String username, String slug);

}
