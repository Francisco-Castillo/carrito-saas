package com.carrito.saas.service.interfaces;

import com.carrito.saas.dto.MenuDTO;

public interface IMenuService {

	MenuDTO getMenu(String slug);

}
