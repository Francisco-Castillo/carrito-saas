package com.carrito.saas.api;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.ComboCreateDTO;
import com.carrito.saas.dto.ComboDTO;
import com.carrito.saas.service.interfaces.IComboService;

@RestController
@RequestMapping("/api/combos")
public class ComboController {

	private final IComboService iComboService;

	public ComboController(IComboService iComboService) {
		this.iComboService = iComboService;
	}

	@PostMapping
	public ComboDTO crear(@RequestBody ComboCreateDTO dto) {
		return iComboService.crearCombo(dto);
	}

}
