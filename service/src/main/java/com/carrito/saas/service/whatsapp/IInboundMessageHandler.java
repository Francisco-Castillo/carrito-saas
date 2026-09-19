package com.carrito.saas.service.whatsapp;

/**
 * Port the webhook hands every accepted inbound message to.
 *
 * <p>T1 only defines the boundary: the controller translates the Meta payload
 * into an {@link InboundMessage} and hands it to every handler registered in
 * the context. There is no production implementation yet — later tasks of
 * {@code odd/tasks/whatsapp-inbound.md} (T3 phone resolution, T4 idempotency,
 * T5 proposal persistence) arrive as implementations of this interface. When
 * no handler bean exists, the controller logs and drops the message, so the
 * application still boots and the channel stays verifiable end to end.</p>
 *
 * <p>Handlers must not throw to signal a provider-visible failure: the caller
 * has already answered 200 to the provider and retries are governed by
 * idempotency, not by server errors.</p>
 */
public interface IInboundMessageHandler {

	/**
	 * Handles one accepted inbound message.
	 */
	void handle(InboundMessage message);
}
