package com.carrito.saas.api;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.MenuDTO;
import com.carrito.saas.service.interfaces.IMenuService;


@RestController
@RequestMapping("/api/menu")
@CrossOrigin
public class MenuController {

	private final IMenuService iMenuService;

	public MenuController(IMenuService iMenuService) {
		this.iMenuService = iMenuService;
	}

	@GetMapping
	public MenuDTO getMenu() {
		// Slugless menu (single-business deployment): the public QR URL no longer
		// carries ?restaurant=<slug>; the service resolves the deployment's only
		// business and fails closed when there is not exactly one.
		return iMenuService.getMenu(null);
	}

	@GetMapping("/{slug}")
	public MenuDTO getMenuBySlug(@PathVariable String slug) {
		return iMenuService.getMenu(slug);
	}

}
