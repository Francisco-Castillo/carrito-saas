package com.carrito.saas.dto;

import java.util.List;

import lombok.Data;

@Data
public class MenuDTO {
	
	private BusinessDTO business;

    private List<CategoryDTO> categories;

    private List<ProductDTO> products;
    
    private List<ComboDTO> combos;

    /**
     * Sucursales activas del negocio para el selector público del storefront
     * (multi-sucursal, odd/tasks/multi-sucursal.md, S4/T7): sólo activas, con
     * id/name/address, ordenadas por id. La carta (S2) es compartida: la
     * lista NO bifurca productos ni precios, sólo identifica lugares. Vacía
     * (nunca ausente) cuando el negocio no tiene sucursales — la UI queda
     * idéntica a la de hoy.
     */
    private List<BranchDTO> branches;

}
