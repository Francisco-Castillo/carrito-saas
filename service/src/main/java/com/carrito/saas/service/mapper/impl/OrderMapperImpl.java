package com.carrito.saas.service.mapper.impl;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

import org.springframework.stereotype.Component;

import org.hibernate.Hibernate;

import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.dto.OrderItemDTO;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.entity.OrderItem;
import com.carrito.saas.service.impl.OrderServiceImpl;
import com.carrito.saas.service.mapper.interfaces.IOrderMapper;

@Component
public class OrderMapperImpl implements IOrderMapper {

	@Override
	public OrderDTO toDTO(Order entity) {
		if (entity == null) {
			return null;
		}
		
		OrderDTO orderDTO = new OrderDTO();
		orderDTO.setOrderId(entity.getId());
		orderDTO.setBusinessId(entity.getBusiness().getId());
		orderDTO.setBusinessSlug(entity.getBusiness().getSlug());
		orderDTO.setCustomerName(entity.getCustomerName());
		orderDTO.setCustomerPhone(entity.getCustomerPhone());
		orderDTO.setCustomerAddress(entity.getCustomerAddress());
		orderDTO.setOrderType(entity.getOrderType().toString());
		// S4 de odd/tasks/pedido-qr-mesa.md: un pedido de salón viaja sin forma
		// de pago (se paga en la mesa) — el mapper no debe reventar con null.
		orderDTO.setPaymentMethod(entity.getPaymentMethod() == null ? null : entity.getPaymentMethod().toString());
		orderDTO.setStatus(entity.getStatus().toString());
		orderDTO.setCreatedAt(entity.getCreatedAt());
		orderDTO.setUpdatedAt(entity.getUpdatedAt());
		orderDTO.setOrderNumber(entity.getOrderNumber());

		// Mesa: exponer id siempre que exista; tocar el nombre solo si la
		// referencia está cargada (createOrder la setea completa; en los caminos
		// de lectura detached queda un proxy lazy que no se debe inicializar).
		if (entity.getRestaurantTable() != null) {
			orderDTO.setTableId(entity.getRestaurantTable().getId());
			if (Hibernate.isInitialized(entity.getRestaurantTable())) {
				orderDTO.setTableName(entity.getRestaurantTable().getTableName());
			}
		}

		orderDTO.setPreparingAt(entity.getPreparingAt());
		orderDTO.setReadyAt(entity.getReadyAt());
		orderDTO.setCompletedAt(entity.getCompletedAt());
		orderDTO.setNotes(entity.getNotes());
		
		if (entity.getItems()!=null && !entity.getItems().isEmpty()) {
			List<OrderItemDTO> itemsDTO = new ArrayList<OrderItemDTO>();
			for (OrderItem orderItem : entity.getItems()) {
				OrderItemDTO item = new OrderItemDTO();
				item.setProductId(orderItem.getProductId());
				
				if (orderItem.getCombo() != null) {
					item.setComboId(orderItem.getCombo().getId());
				}
				item.setComboRoot(orderItem.getComboRoot());
				item.setQuantity(orderItem.getQuantity());
				item.setProductName(orderItem.getProductName());
				// La preferencia viaja en la respuesta (historial del cliente).
				item.setNotes(orderItem.getItemNotes());
				item.setPrice(orderItem.getPrice());
				item.setCost(orderItem.getCost());
				item.setSubtotal(orderItem.getSubtotal());
				// Opciones elegidas (S5): snapshot resuelto con grupo, nombre y
				// recargo, tal como quedó vendido.
				item.setSelectedOptions(OrderServiceImpl.leerOpcionesJson(orderItem.getSelectedOptions()));
				
				itemsDTO.add(item);
			}
			
			orderDTO.setItems(itemsDTO);
		}
		orderDTO.setTotal(entity.getTotal());
		return orderDTO;
	}

	@Override
	public List<OrderDTO> toListDTO(List<Order> entities) {
		List<OrderDTO> lista = new ArrayList<OrderDTO>();
		entities.forEach(entity -> lista.add(toDTO(entity)));
		return lista;
	}

	

}
