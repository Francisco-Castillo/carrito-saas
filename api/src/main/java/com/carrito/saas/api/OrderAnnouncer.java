package com.carrito.saas.api;

import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

import com.carrito.saas.dto.OrderDTO;

import io.micrometer.core.instrument.MeterRegistry;

/**
 * The ONE place where "an order is announced" (T7a.2 of
 * {@code odd/tasks/whatsapp-inbound.md}, closing open gap 54).
 *
 * <p><strong>Why this exists.</strong> The KDS broadcast and the
 * {@code pedidos.creados} metric used to live inline in
 * {@code OrderController#createOrder} only. Any NEW path that called
 * {@code createOrder} directly — like proposal confirmation — produced an
 * order invisible in the kitchen and uncounted, with NOTHING failing: the
 * writer only persists and returns. Extracting the two lines here makes "a
 * created order is announced" a rule with a single home instead of an
 * accident of one controller.</p>
 *
 * <p><strong>Why the announcement lives in {@code api}, not in
 * {@code service}.</strong> The {@code service} module deliberately has no
 * {@code spring-messaging} dependency — that is a structural guarantee pinned
 * by the suite (open gap 32). Broadcasting belongs to the web edge.</p>
 *
 * <p><strong>Timing guarantee.</strong> Announcements happen AFTER the
 * transactional service method returns, never inside the transaction:
 * announcing inside it would put an order on the kitchen board that a later
 * rollback erases.</p>
 *
 * <p>Two methods, and the split is load-bearing: creation also counts the
 * {@code pedidos.creados} metric; every other change only broadcasts.</p>
 */
@Component
public class OrderAnnouncer {

	private final SimpMessagingTemplate messagingTemplate;
	private final MeterRegistry registry;

	public OrderAnnouncer(SimpMessagingTemplate messagingTemplate, MeterRegistry registry) {
		this.messagingTemplate = messagingTemplate;
		this.registry = registry;
	}

	/**
	 * Announces a NEWLY CREATED order: broadcast to the business's kitchen
	 * topic AND one increment of {@code pedidos.creados}.
	 */
	public void orderCreated(OrderDTO order) {

		messagingTemplate.convertAndSend("/topic/orders/" + order.getBusinessSlug(), order);
		registry.counter("pedidos.creados").increment();
	}

	/**
	 * Announces a change to an EXISTING order (status change, cancellation):
	 * broadcast only — never counts as a created order.
	 */
	public void orderChanged(OrderDTO order) {

		messagingTemplate.convertAndSend("/topic/orders/" + order.getBusinessSlug(), order);
	}
}
