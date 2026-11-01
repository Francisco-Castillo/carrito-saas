package com.carrito.saas.api;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
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

	@GetMapping
	public List<CategoryDTO> obtenerTodasLasCategorias() {
		return categoryService.findAllByBusinessId();
	}

	/**
	 * Panel de administración: todas las categorías del negocio, activas e
	 * inactivas, en el orden del dueño.
	 *
	 * <p>Es la lectura de la tabla de categorías. Sin las inactivas, apagar una
	 * categoría la sacaba de la única lista que el panel consulta y el switch
	 * era de ida: no había forma de volver a prenderla.</p>
	 *
	 * <p>Se sumó como ruta propia en vez de cambiar {@code GET /api/categorias}:
	 * ese listado alimenta también los selectores del alta de productos y
	 * combos, que sólo deben ofrecer categorías activas. La ruta literal gana
	 * sobre la plantilla {@code /{id}} en el matcher de Spring, así que no
	 * compite con ella.</p>
	 */
	@GetMapping("/todas")
	public List<CategoryDTO> obtenerTodasLasCategoriasIncluyendoInactivas() {
		return categoryService.findAllIncludingInactive();
	}

	@GetMapping("/{id}")
	public ResponseEntity<CategoryDTO> getById(@PathVariable Long id) {

		CategoryDTO category = categoryService.findById(id);

		return ResponseEntity.ok(category);
	}

	/**
	 * Panel de administración: elimina una categoría propia. Una categoría
	 * con productos falla con 409 (nunca se hace cascade hacia los
	 * productos); un id ajeno resuelve como 404.
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> eliminar(@PathVariable Long id) {
		categoryService.eliminarCategoria(id);
		return ResponseEntity.noContent().build();
	}

}
