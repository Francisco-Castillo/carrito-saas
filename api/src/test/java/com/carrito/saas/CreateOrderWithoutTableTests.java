package com.carrito.saas;

import java.math.BigDecimal;
import java.util.List;

import org.assertj.core.api.Assertions;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.dto.OrderItemDTO;
import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.Product;
import com.carrito.saas.repository.enums.OrderStatus;
import com.carrito.saas.repository.enums.OrderType;
import com.carrito.saas.repository.enums.PaymentMethod;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.service.interfaces.IOrderService;

/**
 * Contract: an order with NO table reference must be creatable.
 *
 * <p>The QR menu is a per-business channel that offers pickup (RETIRO) and
 * delivery (DELIVERY) only; there is no table in that flow. This test seeds a
 * business and a product, builds a request that carries no table of any kind
 * ({@link OrderRequestDTO} has no table field, and nothing in the service sets
 * one), and asserts that {@code createOrder} succeeds and returns an order in
 * status {@link OrderStatus#NEW}.</p>
 *
 * <p>Currently RED: {@code Order.restaurantTable} is mapped with
 * {@code @JoinColumn(name = "table_id", nullable = false)} while nothing ever
 * sets it, so every INSERT into {@code orders} carries {@code table_id = NULL}
 * and PostgreSQL rejects it (SQLState 23502). The test must PASS once the
 * entity makes {@code table_id} nullable.</p>
 *
 * <p>{@code @Transactional} rolls back every fixture at test end. The failing
 * INSERT still executes immediately inside {@code createOrder} because
 * {@code Order} uses IDENTITY generation, so the constraint violation is
 * observable from the test method and no committed rows are left behind.</p>
 */
@SpringBootTest
@Transactional
class CreateOrderWithoutTableTests {

	/** High fixed id: {@code businesses.id} is manually assigned (no generator). */
	private static final Long BUSINESS_ID = 987_654_321L;

	private static final String SLUG = "no-table-order-test-business";

	@Autowired
	private IOrderService orderService;

	@Autowired
	private BusinessRepository businessRepository;

	@Autowired
	private ProductRepository productRepository;

	@Test
	void createOrderWithoutTableIsCreatedWithStatusNew() {

		// --- Seed: business with a known slug ---------------------------------
		Business business = new Business();
		business.setId(BUSINESS_ID);
		business.setName("No Table Order Test Business");
		business.setSlug(SLUG);
		businessRepository.saveAndFlush(business);

		// --- Seed: product with a known price and enough stock ----------------
		Product product = new Product();
		product.setName("No Table Order Test Product");
		product.setPrice(new BigDecimal("100.00"));
		product.setCost(new BigDecimal("40.00"));
		product.setStock(10);
		product.setActive(true);
		product = productRepository.save(product);

		// --- Request: one item, customer, type and payment, NO table at all ---
		OrderItemDTO item = new OrderItemDTO();
		item.setProductId(product.getId());
		item.setQuantity(1);

		OrderRequestDTO request = new OrderRequestDTO();
		request.setCustomerName("Test Customer");
		request.setOrderType(OrderType.RETIRO);
		request.setPaymentMethod(PaymentMethod.EFECTIVO);
		request.setItems(List.of(item));

		// --- Act + assert the required behavior -------------------------------
		// RETIRO (not DELIVERY) keeps the request free of any address/table
		// concern. An order without a table must be creatable.
		OrderDTO result = orderService.createOrder(SLUG, request);

		Assertions.assertThat(result).isNotNull();
		Assertions.assertThat(result.getOrderId()).isNotNull();
		Assertions.assertThat(result.getStatus()).isEqualTo(OrderStatus.NEW.name());
	}

}
