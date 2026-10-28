package com.carrito.saas.security;

public interface ISecurityService {

	LoginResponseDTO login(LoginRequestDTO request);
	
	Long getCurrentBusinessId();

	/**
	 * Sucursal activa del JWT (multi-sucursal, T3). El claim branchId si
	 * viaja; si no, el fallback: una sola sucursal activa del negocio se
	 * resuelve sola, ninguna → null (vista a nivel negocio, compatibilidad
	 * pre-multi-sucursal), varias → 400 pidiendo selección explícita.
	 */
	Long getCurrentBranchId();

	/**
	 * Igual que {@link #getCurrentBranchId()} pero lectura-segura: con varias
	 * activas y sin selección devuelve la DEFAULT (primera activa) en vez del
	 * 400. Para lecturas del panel (perfil): un dueño con varias sucursales y
	 * token sin claim no puede quedarse sin panel; las escrituras siguen
	 * exigendo selección explícita vía getCurrentBranchId.
	 */
	Long getCurrentBranchIdOrDefault();

	/**
	 * Sucursal reclamada por el TOKEN (claim branchId), o null si el token no
	 * la trae. Sin fallback a propósito: es la sucursal con la que la sesión
	 * está trabajando, la misma que el panel marca como «Actual».
	 */
	Long getClaimedBranchId();

}
