package com.carrito.saas.repository.jpa;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.carrito.saas.repository.entity.OrderProposal;
import com.carrito.saas.repository.enums.ProposalStatus;

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

	/**
	 * The T7a.1 list query: the PENDING proposals of ONE business, with their
	 * lines fetched in the same query. {@code items} is a LAZY
	 * {@code @OneToMany}, so without the {@code join fetch} any read outside a
	 * transaction would throw {@code LazyInitializationException} — the fetch
	 * is part of the contract, not an optimization.
	 *
	 * <p>{@code NULLS LAST} is deliberate: Postgres sorts {@code DESC} as
	 * NULLS FIRST, and {@code received_at} is nullable, so a proposal without
	 * a timestamp would jump to the top of the operator's queue.</p>
	 */
	@Query("""
			select distinct p from OrderProposal p
			left join fetch p.items
			where p.business.id = :businessId and p.status = com.carrito.saas.repository.enums.ProposalStatus.PENDING
			order by p.receivedAt desc nulls last, p.id desc
			""")
	List<OrderProposal> findPendingByBusinessId(@Param("businessId") Long businessId);

	/**
	 * Ownership-scoped read, used to distinguish "not found" from "found but
	 * already decided" when a CAS update reports 0 rows.
	 */
	Optional<OrderProposal> findByIdAndBusinessId(Long id, Long businessId);

	/**
	 * The T7a REJECT compare-and-set: flips a proposal to REJECTED only if it
	 * is still PENDING and belongs to the given business. Returns 1 when the
	 * caller won the transition and 0 otherwise.
	 *
	 * <p>{@code updatedAt} is set EXPLICITLY: {@code @UpdateTimestamp} does not
	 * fire for bulk JPQL. {@code clearAutomatically} detaches the stale managed
	 * entity — a bulk update bypasses the persistence context, so re-reading
	 * afterwards is mandatory before serving a DTO.</p>
	 */
	@Modifying(clearAutomatically = true, flushAutomatically = true)
	@Query("""
			update OrderProposal p
			set p.status = com.carrito.saas.repository.enums.ProposalStatus.REJECTED, p.updatedAt = :now
			where p.id = :id
			  and p.business.id = :businessId
			  and p.status = com.carrito.saas.repository.enums.ProposalStatus.PENDING
			""")
	int rejectIfPending(@Param("id") Long id, @Param("businessId") Long businessId, @Param("now") LocalDateTime now);
}
