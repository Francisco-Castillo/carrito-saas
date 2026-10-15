package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Order;
import com.carrito.saas.repository.entity.OrderItem;
import com.carrito.saas.repository.enums.OrderStatus;
import com.carrito.saas.repository.enums.OrderType;
import com.carrito.saas.repository.enums.PaymentMethod;
import com.carrito.saas.service.mapper.impl.OrderMapperImpl;

/**
 * Contract for {@link OrderMapperImpl#toListDTO(List)}.
 *
 * <p>{@code toListDTO} used to be a TODO returning {@code null} while the
 * rest of the {@code ICarritoMapper} family honours it. It must map every
 * entity, in order, with exactly the same fields as calling {@code toDTO}
 * on each one, and an empty input must produce an empty list, not null.</p>
 */
class OrderListMappingTests {

	private final OrderMapperImpl mapper = new OrderMapperImpl();

	private Order order(String customerName) {

		Business business = new Business();
		business.setId(987_900_001L);
		business.setSlug("order-list-biz");

		OrderItem item = new OrderItem();
		item.setProductId(42L);
		item.setProductName("Order List Product");
		item.setQuantity(2);
		item.setPrice(new BigDecimal("10.00"));
		item.setCost(new BigDecimal("4.00"));
		item.setSubtotal(new BigDecimal("20.00"));
		item.setComboRoot(false);

		Order order = new Order();
		order.setBusiness(business);
		order.setCustomerName(customerName);
		order.setCustomerPhone("+5491100000000");
		order.setCustomerAddress("Calle Falsa 123");
		order.setOrderType(OrderType.DELIVERY);
		order.setPaymentMethod(PaymentMethod.EFECTIVO);
		order.setStatus(OrderStatus.NEW);
		order.setOrderNumber(7);
		order.setNotes("sin cebolla");
		order.setTotal(new BigDecimal("20.00"));
		order.setItems(List.of(item));

		return order;
	}

	/**
	 * {@code toListDTO} must map every entity, in order, with the same logic
	 * as {@code toDTO}: the result must equal the per-entity {@code toDTO}
	 * output (single source of truth, no hardcoded field values).
	 */
	@Test
	void toListDTOMapsEveryEntityInOrderLikeToDTO() {

		Order first = order("Order List First");
		Order second = order("Order List Second");

		List<com.carrito.saas.dto.OrderDTO> dtos = mapper.toListDTO(List.of(first, second));

		assertThat(dtos)
				.as("toListDTO must not return null: it maps one DTO per entity")
				.isNotNull()
				.hasSize(2);
		assertThat(dtos.get(0)).isEqualTo(mapper.toDTO(first));
		assertThat(dtos.get(1)).isEqualTo(mapper.toDTO(second));
	}

	/** An empty input list must produce an empty list, not {@code null}. */
	@Test
	void toListDTOWithEmptyListReturnsEmptyList() {

		assertThat(mapper.toListDTO(List.of()))
				.isNotNull()
				.isEmpty();
	}

}
