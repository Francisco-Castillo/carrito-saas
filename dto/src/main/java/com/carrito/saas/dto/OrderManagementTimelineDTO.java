package com.carrito.saas.dto;

import java.time.LocalDateTime;

import lombok.Data;

/**
 * Timeline del pedido para la pantalla de gestión: los sellos de tiempo de
 * cada estado. Los campos son null mientras el pedido no alcanzó el estado.
 */
@Data
public class OrderManagementTimelineDTO {

	private LocalDateTime preparingAt;

	private LocalDateTime readyAt;

	private LocalDateTime completedAt;

	private LocalDateTime cancelledAt;

}
