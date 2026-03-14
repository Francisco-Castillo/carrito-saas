package com.carrito.saas.dto.reportes;

import lombok.Data;

@Data
public class DashboardTodayDTO {
	
	 private long orders;
	    private double revenue;
	    private double avgTicket;
	    private long avgPrepTime;

}
