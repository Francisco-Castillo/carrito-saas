package com.carrito.saas.api;

import java.io.IOException;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.carrito.saas.dto.ComboCreateDTO;
import com.carrito.saas.dto.ComboDTO;
import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.service.interfaces.IComboService;

@RestController
@RequestMapping("/api/combos")
public class ComboController {

	private final IComboService iComboService;

	public ComboController(IComboService iComboService) {
		this.iComboService = iComboService;
	}

	/**
	 * Sube (o reemplaza) la imagen de un combo propio: multipart con el campo
	 * "file". El tenant se valida en el servicio (combo ajeno → 404); el
	 * tipo/tamaño los valida ImageStorageService (400 con mensaje en español).
	 */
	@PostMapping("/{id}/image")
	public ComboDTO subirImagen(@PathVariable Long id,
			@RequestParam("file") MultipartFile file) {
		try {
			return iComboService.actualizarImagen(id, file.getBytes(), file.getContentType());
		} catch (IOException e) {
			throw new BusinessException("No se pudo leer la imagen subida", ErrorType.INTERNAL_ERROR);
		}
	}

	/** Quita la imagen de un combo propio; si no tiene, no cambia nada. */
	@DeleteMapping("/{id}/image")
	public ComboDTO borrarImagen(@PathVariable Long id) {
		return iComboService.eliminarImagen(id);
	}

	@PostMapping
	public ComboDTO crear(@RequestBody ComboCreateDTO dto) {
		return iComboService.crearCombo(dto);
	}

	/**
	 * Panel de administración: actualiza un combo del negocio autenticado.
	 * El tenant se ancla en el JWT — un id ajeno resuelve como 404, nunca
	 * como un éxito silencioso.
	 */
	@PutMapping("/{id}")
	public ComboDTO actualizar(@PathVariable Long id, @RequestBody ComboCreateDTO dto) {
		return iComboService.actualizarCombo(id, dto);
	}

	/**
	 * Panel de administración: activa o desactiva un combo propio. Mismo
	 * shape que el toggle de productos ({@code PUT /api/products/{id}/activar}):
	 * el tenant se ancla en el JWT — un id ajeno resuelve como 404 — y sólo
	 * cambia el flag activo, sin tocar los items.
	 */
	@PutMapping("/{id}/activar")
	public ComboDTO activar(@PathVariable Long id, @RequestParam boolean activo) {
		return iComboService.cambiarEstadoActivo(id, activo);
	}

	/**
	 * Panel de administración: elimina un combo propio. Sus items se van con
	 * él; un combo referenciado por un pedido falla con 409 (FK real en
	 * order_items.combo_id), un id ajeno resuelve como 404.
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<Void> eliminar(@PathVariable Long id) {
		iComboService.eliminarCombo(id);
		return ResponseEntity.noContent().build();
	}

	/**
	 * Panel de administración: los combos del negocio autenticado, acotados
	 * al businessId del JWT.
	 */
	@GetMapping
	public List<ComboDTO> listarCombos() {
		return iComboService.findAllByBusinessId();
	}

}
