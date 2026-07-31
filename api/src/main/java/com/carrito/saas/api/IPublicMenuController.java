package com.carrito.saas.api;

import org.springframework.web.bind.annotation.PathVariable;

import com.carrito.saas.dto.MenuDTO;

public interface IPublicMenuController {

	public MenuDTO getMenu(@PathVariable String token);

}
