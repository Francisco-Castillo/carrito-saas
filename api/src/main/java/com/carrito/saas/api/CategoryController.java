package com.carrito.saas.api;

import java.util.List;

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

	private final ICategoryService iCategoryService;

	public CategoryController(ICategoryService iCategoryService) {
		this.iCategoryService = iCategoryService;
	}

	@PostMapping
	public CategoryDTO crear(@RequestBody CategoryDTO dto) {
		return iCategoryService.crearCategoria(dto);
	}

	@PutMapping("/{id}")
	public CategoryDTO actualizar(@PathVariable Long id, @RequestBody CategoryDTO dto) {
		return iCategoryService.actualizarCategoria(id, dto);
	}


	@PutMapping("/orden")
	public void ordenar(@RequestBody List<Long> idsOrdenados) {
		iCategoryService.ordenarCategorias(idsOrdenados);
	}
	
	@GetMapping
	public List<CategoryDTO> obtenerTodasLasCategorias(){
		return iCategoryService.findAllByBusinessIdAndActiveTrueOrFalse();
	}

}
