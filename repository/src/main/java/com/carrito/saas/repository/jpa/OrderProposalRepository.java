package com.carrito.saas.repository.jpa;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.OrderProposal;

/**
 * Persistence for inbound message proposals (T4 + T5 of
 * {@code odd/tasks/whatsapp-inbound.md}).
 *
 * <p><strong>This repository is deliberately a SEPARATE surface from
 * {@code OrderRepository}</strong>: proposals are not orders. They cannot
 * participate in {@code OrderRepository.findActiveOrders} (that query reads
 * only {@code Order} rows), they are never emitted to the KDS broadcast, and
 * they never touch stock. The guarantee that a proposal does not reach the
 * kitchen is structural — a different entity, a different table, a different
 * repository — not a filter inside the order path.</p>
 *
 * <p>{@code existsByMessageId} is the fast-path check of the T4 idempotency
 * mechanism; the authoritative guarantee is the
 * {@code uq_order_proposals_message_id} UNIQUE constraint declared on the
 * entity, which the insert handler treats as "already processed".</p>
 */
@Repository
public interface OrderProposalRepository extends JpaRepository<OrderProposal, Long> {

	/** Fast path of the T4 idempotency check: has this Meta message id been recorded? */
	boolean existsByMessageId(String messageId);

	/** Reads a proposal back by its Meta message id, when it has one. */
	Optional<OrderProposal> findByMessageId(String messageId);
}
