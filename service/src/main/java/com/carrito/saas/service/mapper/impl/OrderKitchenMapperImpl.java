package com.carrito.saas.service.mapper.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;

import org.springframework.stereotype.Component;

import com.carrito.saas.dto.OrderItemKitchenDTO;
import com.carrito.saas.dto.OrderKitchenDTO;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.entity.OrderItem;
import com.carrito.saas.service.mapper.interfaces.IOrderKitchenMapper;

@Component
public class OrderKitchenMapperImpl implements IOrderKitchenMapper {

	@Override
	public List<OrderKitchenDTO> toListDTO(List<Order> orders) {
		if (orders == null || orders.isEmpty()) {
			return Collections.emptyList();
		}

		List<OrderKitchenDTO> list = new ArrayList<OrderKitchenDTO>();

		for (Order order : orders) {

			OrderKitchenDTO orderKitchenDTO = new OrderKitchenDTO();

			orderKitchenDTO.setOrderId(order.getId());
			orderKitchenDTO.setOrderNumber(order.getOrderNumber());
			orderKitchenDTO.setOrderType(order.getOrderType().toString());
			orderKitchenDTO.setCustomerName(order.getCustomerName());
			orderKitchenDTO.setStatus(order.getStatus());
			orderKitchenDTO.setCreatedAt(order.getCreatedAt());
			orderKitchenDTO.setNotes(order.getNotes());

			if (order.getItems() != null && !order.getItems().isEmpty()) {
				List<OrderItemKitchenDTO> items = new ArrayList<OrderItemKitchenDTO>();

				for (OrderItem orderItem : order.getItems()) {
					OrderItemKitchenDTO orderItemKitchenDTO = new OrderItemKitchenDTO();
					orderItemKitchenDTO.setProductName(orderItem.getProductName());
					orderItemKitchenDTO.setQuantity(orderItem.getQuantity());
					items.add(orderItemKitchenDTO);
				}

				orderKitchenDTO.setItems(items);
			}

			list.add(orderKitchenDTO);
		}

		return list;

	}

}
