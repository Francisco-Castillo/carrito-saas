package com.carrito.saas.service.mapper.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.OrderManagementCancellationReasonDTO;
import com.carrito.saas.dto.OrderManagementDTO;
import com.carrito.saas.dto.OrderManagementItemDTO;
import com.carrito.saas.dto.OrderManagementTableDTO;
import com.carrito.saas.dto.OrderManagementTimelineDTO;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.entity.OrderItem;
import com.carrito.saas.repository.entity.RestaurantTable;
import com.carrito.saas.service.mapper.interfaces.IOrderManagementMapper;

/**
 * Mapper del payload de gestión (todos los estados del día). Sigue la
 * convención de {@link OrderKitchenMapperImpl}: mapeo manual por campo,
 * lista vacía para entrada vacía. La consulta que alimenta este mapper
 * trae items, mesa y motivo fetch-joined, así el mapeo nunca dispara
 * lazy loading.
 */
@Component
public class OrderManagementMapperImpl implements IOrderManagementMapper {

	@Override
	public List<OrderManagementDTO> toListDTO(List<Order> orders) {
		if (orders == null || orders.isEmpty()) {
			return Collections.emptyList();
		}

		List<OrderManagementDTO> list = new ArrayList<>();

		for (Order order : orders) {
			list.add(toDTO(order));
		}

		return list;
	}

	private OrderManagementDTO toDTO(Order order) {

		OrderManagementDTO dto = new OrderManagementDTO();

		dto.setOrderId(order.getId());
		dto.setOrderNumber(order.getOrderNumber());
		dto.setOrderType(order.getOrderType() == null ? null : order.getOrderType().toString());
		dto.setStatus(order.getStatus());
		dto.setCustomerName(order.getCustomerName());
		dto.setCustomerPhone(order.getCustomerPhone());
		dto.setCustomerAddress(order.getCustomerAddress());
		dto.setPaymentMethod(order.getPaymentMethod());
		dto.setNotes(order.getNotes());
		dto.setTotal(order.getTotal());
		dto.setCreatedAt(order.getCreatedAt());
		dto.setTable(toTableDTO(order.getRestaurantTable()));
		dto.setTimeline(toTimelineDTO(order));
		dto.setItems(toItemDTOs(order.getItems()));
		dto.setCancellationReason(toCancellationReasonDTO(order.getCancellationReason()));
		dto.setCancellationNote(order.getCancellationNote());

		return dto;
	}

	private OrderManagementTableDTO toTableDTO(RestaurantTable table) {

		if (table == null) {
			return null;
		}

		OrderManagementTableDTO dto = new OrderManagementTableDTO();
		dto.setId(table.getId());
		dto.setName(table.getTableName());
		dto.setNumber(table.getTableNumber());
		return dto;
	}

	private OrderManagementTimelineDTO toTimelineDTO(Order order) {

		OrderManagementTimelineDTO dto = new OrderManagementTimelineDTO();
		dto.setPreparingAt(order.getPreparingAt());
		dto.setReadyAt(order.getReadyAt());
		dto.setCompletedAt(order.getCompletedAt());
		dto.setCancelledAt(order.getCancelledAt());
		return dto;
	}

	private List<OrderManagementItemDTO> toItemDTOs(List<OrderItem> items) {

		if (items == null || items.isEmpty()) {
			return Collections.emptyList();
		}

		List<OrderManagementItemDTO> list = new ArrayList<>();

		for (OrderItem item : items) {
			OrderManagementItemDTO dto = new OrderManagementItemDTO();
			dto.setName(item.getProductName());
			dto.setQuantity(item.getQuantity());
			dto.setNotes(item.getItemNotes());
			dto.setUnitPrice(item.getPrice());
			dto.setSubtotal(item.getSubtotal());
			list.add(dto);
		}

		return list;
	}

	private OrderManagementCancellationReasonDTO toCancellationReasonDTO(
			com.carrito.saas.repository.entity.CancellationReason reason) {

		if (reason == null) {
			return null;
		}

		OrderManagementCancellationReasonDTO dto = new OrderManagementCancellationReasonDTO();
		dto.setId(reason.getId());
		dto.setDescription(reason.getDescription());
		return dto;
	}

}
