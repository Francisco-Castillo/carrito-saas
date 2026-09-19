package com.carrito.saas.service.whatsapp;

/**
 * Port the webhook hands every accepted inbound message to.
 *
 * <p>The controller translates the Meta payload into an {@link InboundMessage}
 * and hands it to every handler registered in the context. The production
 * implementation is {@link PersistingInboundMessageHandler}, which records the
 * message as an {@code OrderProposal} and resolves its business.</p>
 *
 * <p><strong>Error contract.</strong> A handler must distinguish two kinds of
 * failure, and the difference is what the provider sees:</p>
 * <ul>
 *   <li><strong>Business outcome</strong> — the phone resolves to no business or
 *       to more than one, or the text cannot be interpreted. The handler must
 *       <em>record</em> the message (state {@code FAILED} with a reason) and
 *       return normally. The controller then answers <strong>200</strong>: the
 *       message was accepted and there is nothing to retry.</li>
 *   <li><strong>Infrastructure failure</strong> — the database is unreachable,
 *       the insert failed for a reason other than the idempotency key, anything
 *       the handler cannot turn into a recorded outcome. The handler must
 *       <strong>throw</strong>. The controller answers <strong>500</strong> so
 *       the provider retries; answering 200 here would tell the provider the
 *       message was processed while nothing was persisted, and the order would
 *       be lost without a trace.</li>
 * </ul>
 *
 * <p>Absence of any handler bean is likewise a loud failure (500), never a
 * silent discard: an accepted message that is thrown away is worse than an
 * error.</p>
 *
 * <p><strong>Idempotency.</strong> The provider retries deliveries, so a handler
 * may be invoked more than once for the same message. Handlers must be
 * idempotent on {@link InboundMessage#externalId}; {@code PersistingInboundMessageHandler}
 * relies on the unique constraint on the proposal's {@code message_id}.</p>
 */
public interface IInboundMessageHandler {

	/**
	 * Handles one accepted inbound message.
	 */
	void handle(InboundMessage message);
}
