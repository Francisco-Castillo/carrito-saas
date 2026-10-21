package com.carrito.saas.dto.reportes;

import lombok.Data;

/**
 * Ventas de un día calendario del negocio: label legible (yyyy-MM-dd) y
 * revenue acumulado del día. Espeja SalesByHourDTO pero por día.
 */
@Data
public class SalesByDayDTO {

	private String day;
	private double revenue;

	public SalesByDayDTO(String day, double revenue) {
		super();
		this.day = day;
		this.revenue = revenue;
	}

}
