package com.carrito.saas.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.CategoryDTO;
import com.carrito.saas.service.interfaces.ICategoryService;

@RestController
@RequestMapping("/api/categorias")
public class CategoryController {

	private final ICategoryService categoryService;

	public CategoryController(ICategoryService iCategoryService) {
		this.categoryService = iCategoryService;
	}

	@PostMapping
	public CategoryDTO crear(@RequestBody CategoryDTO dto) {
		return categoryService.crearCategoria(dto);
	}

	@PutMapping("/{id}")
	public CategoryDTO actualizar(@PathVariable Long id, @RequestBody CategoryDTO dto) {
		return categoryService.actualizarCategoria(id, dto);
	}

	@PutMapping("/orden")
	public void ordenar(@RequestBody List<Long> idsOrdenados) {
		categoryService.ordenarCategorias(idsOrdenados);
	}

	// FIXME: AQUI SOLO SE DEBEN VER LAS CATEGORIAS PROPIAS DEL LOCAL.
	@GetMapping
	public List<CategoryDTO> obtenerTodasLasCategorias() {
		return categoryService.findAllByBusinessId();
	}

	@GetMapping("/{id}")
	public ResponseEntity<CategoryDTO> getById(@PathVariable Long id) {

		CategoryDTO category = categoryService.findById(id);

		return ResponseEntity.ok(category);
	}

}
