package com.carrito.saas.dto;

import lombok.Data;

/**
 * Respuesta de {@code POST /api/business/branches/select/{id}}
 * (multi-sucursal, T3): token nuevo con el claim {@code branchId} de la
 * sucursal elegida. El token viejo sigue válido hasta expirar — la selección
 * es un cambio de contexto, no una revocación.
 */
@Data
public class SelectBranchResponseDTO {

	private String token;
	private Long branchId;
	private String branchName;
	private Long expiresAt;

	public SelectBranchResponseDTO() {
	}

	public SelectBranchResponseDTO(String token, Long branchId, String branchName, Long expiresAt) {
		this.token = token;
		this.branchId = branchId;
		this.branchName = branchName;
		this.expiresAt = expiresAt;
	}

}
