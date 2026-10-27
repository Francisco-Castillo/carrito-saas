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

}
