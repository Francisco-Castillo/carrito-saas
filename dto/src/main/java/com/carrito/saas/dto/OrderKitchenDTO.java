package com.carrito.saas.dto;

import java.time.LocalDateTime;
import java.util.List;

import com.carrito.saas.repository.enums.OrderStatus;

import lombok.Data;

@Data
public class OrderKitchenDTO {
	
	private Long orderId;

    private Integer orderNumber;

    private String customerName;

    private OrderStatus status;

    private LocalDateTime createdAt;

    private List<OrderItemKitchenDTO> items;

}
