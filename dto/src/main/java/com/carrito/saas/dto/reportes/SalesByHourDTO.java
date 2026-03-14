package com.carrito.saas.dto.reportes;

import lombok.Data;

@Data
public class SalesByHourDTO {

	private String hour;
	private double revenue;

	public SalesByHourDTO(String hour, double revenue) {
		super();
		this.hour = hour;
		this.revenue = revenue;
	}

}
