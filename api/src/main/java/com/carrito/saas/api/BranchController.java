package com.carrito.saas.api;

import java.util.List;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.BranchDTO;
import com.carrito.saas.service.interfaces.IBranchService;

/**
 * Sucursales del negocio del JWT (multi-sucursal, odd/tasks/multi-sucursal.md,
 * T2). Igual que el resto del panel del dueño, no hay id ni slug de negocio en
 * la ruta: el negocio sale del JWT, así que un negocio nunca ve ni toca las
 * sucursales de otro. {@code DELETE} es baja lógica (reversible), nunca
 * borrado físico.
 */
@RestController
@RequestMapping("/api/business/branches")
public class BranchController {

	private final IBranchService branchService;

	public BranchController(IBranchService branchService) {
		this.branchService = branchService;
	}

	@GetMapping
	public List<BranchDTO> listar() {
		return branchService.listar();
	}

	@PostMapping
	public BranchDTO crear(@RequestBody BranchDTO dto) {
		return branchService.crear(dto);
	}

	@PutMapping("/{id}")
	public BranchDTO actualizar(@PathVariable Long id, @RequestBody BranchDTO dto) {
		return branchService.actualizar(id, dto);
	}

	@DeleteMapping("/{id}")
	public BranchDTO eliminar(@PathVariable Long id) {
		return branchService.eliminar(id);
	}

}
