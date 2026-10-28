package com.carrito.saas.api;

import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.MenuDTO;
import com.carrito.saas.dto.MenuTableDTO;
import com.carrito.saas.service.interfaces.IMenuService;


@RestController
@RequestMapping("/api/menu")
@CrossOrigin
public class MenuController {

	private final IMenuService iMenuService;

	public MenuController(IMenuService iMenuService) {
		this.iMenuService = iMenuService;
	}

	@GetMapping("/{slug}")
	public MenuDTO getMenuBySlug(@PathVariable String slug) {
		return iMenuService.getMenu(slug);
	}

	/**
	 * Public identity of one table (anonymous, under the already-public
	 * {@code /api/menu/**} surface): the customer menu calls it to show the
	 * table badge when a per-table QR opened the page with {@code ?table=<id>}.
	 *
	 * <p>Slug-scoped (S4 enmendada de odd/tasks/retire-menu-route.md): the
	 * table lookup resolves the business from the slug first — a table of
	 * another business, an unknown slug or a deactivated local all answer
	 * 404, never a cross-tenant identity.</p>
	 */
	@GetMapping("/{slug}/table/{id}")
	public MenuTableDTO getTable(@PathVariable String slug, @PathVariable Long id) {
		return iMenuService.getPublicTable(slug, id);
	}

}
