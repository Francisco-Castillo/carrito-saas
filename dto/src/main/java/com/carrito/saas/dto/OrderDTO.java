package com.carrito.saas.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import lombok.Data;

@Data
public class OrderDTO {

	private Long orderId;

	private Long businessId;

	private String customerName;

	private String customerPhone;

	private String customerAddress;

	private String orderType;

	private String paymentMethod;

	private String notes;

	private BigDecimal total;

	private String status;

	private LocalDateTime createdAt;

	private LocalDateTime updatedAt;

	private List<OrderItemDTO> items;

	private Integer orderNumber;

	private LocalDateTime preparingAt;

	private LocalDateTime readyAt;

	private LocalDateTime completedAt;

}
