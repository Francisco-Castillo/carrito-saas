package com.carrito.saas.service.interfaces;

import java.util.List;

import com.carrito.saas.dto.BranchDTO;

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

}
