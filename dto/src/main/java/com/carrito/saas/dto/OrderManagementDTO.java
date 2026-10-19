package com.carrito.saas.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

import com.carrito.saas.repository.enums.OrderStatus;
import com.carrito.saas.repository.enums.PaymentMethod;

import lombok.Data;

/**
 * Payload rico de la pantalla de gestión de pedidos (mostrador): todos los
 * estados del día, con total, método de pago, dirección, mesa, timeline y
 * motivo de cancelación — todo lo que {@link OrderKitchenDTO} (KDS) no
 * necesita.
 */
@Data
public class OrderManagementDTO {

	private Long orderId;

	private Integer orderNumber;

	private String orderType;

	private OrderStatus status;

	private String customerName;

	private String customerPhone;

	private String customerAddress;

	private PaymentMethod paymentMethod;

	private String notes;

	private OrderManagementTableDTO table;

	private BigDecimal total;

	private LocalDateTime createdAt;

	private List<OrderManagementItemDTO> items;

	private OrderManagementTimelineDTO timeline;

	private OrderManagementCancellationReasonDTO cancellationReason;

	private String cancellationNote;

}
