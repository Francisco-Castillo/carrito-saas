package com.carrito.saas.dto;

import java.util.List;

import lombok.Data;

@Data
public class OrderDTO {
	
	private Long id;

    private Long businessId;

    private String customerName;

    private String customerPhone;

    private String status;

    private Double total;

    private List<OrderItemDTO> items;

}
