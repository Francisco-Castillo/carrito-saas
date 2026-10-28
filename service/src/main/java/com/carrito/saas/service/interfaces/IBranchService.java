package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.BranchDTO;
import com.carrito.saas.dto.SelectBranchResponseDTO;

/**
 * Sucursales del negocio del JWT (multi-sucursal, odd/tasks/multi-sucursal.md,
 * T2). El tenant ancla en el JWT — igual que el resto del panel del dueño —
 * así que un id de sucursal ajeno resuelve como no encontrado.
 */
public interface IBranchService {

	/** Todas las sucursales del negocio, incluidas las inactivas (panel). */
	List<BranchDTO> listar();

	BranchDTO crear(BranchDTO dto);

	BranchDTO actualizar(Long id, BranchDTO dto);

	/** Baja lógica: {@code active=false}. Reversible; el historial no se toca. */
	BranchDTO eliminar(Long id);

	/**
	 * Reactiva una sucursal dada de baja (S3): la baja es lógica y
	 * reversible, así que el dueño puede volver a activarla. Reactivar una
	 * ya activa es idempotente (200 con el DTO, no un error).
	 */
	BranchDTO reactivar(Long id);

	/**
	 * Selecciona la sucursal activa del usuario (T3): valida propia y activa,
	 * emite token nuevo con el claim branchId.
	 */
	SelectBranchResponseDTO seleccionar(Long id);

}
