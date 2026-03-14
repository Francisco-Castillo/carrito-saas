package com.carrito.saas.dto.reportes;

import lombok.Data;

@Data
public class OrderStatusSummaryDTO {
	private long NEW;
    private long PREPARING;
    private long READY;

}
