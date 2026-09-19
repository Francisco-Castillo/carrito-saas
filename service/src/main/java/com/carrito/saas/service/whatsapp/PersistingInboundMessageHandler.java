package com.carrito.saas.service.whatsapp;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.OrderProposal;
import com.carrito.saas.repository.entity.OrderProposalItem;
import com.carrito.saas.repository.enums.ProposalStatus;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.OrderProposalRepository;

/**
 * The production {@link IInboundMessageHandler}: records every accepted
 * inbound message as an {@link OrderProposal} (T5 of
 * {@code odd/tasks/whatsapp-inbound.md}) and makes provider retries
 * idempotent by Meta's {@code messageId} (T4).
 *
 * <p><strong>Business outcome vs infrastructure failure — the boundary the
 * webhook's HTTP status rides on.</strong> Everything this handler can
 * resolve as a business outcome is PERSISTED and returned from normally:
 * an unresolvable or ambiguous sender phone is recorded as a
 * {@link ProposalStatus#FAILED} proposal with a reason, and the caller
 * answers 200 (retrying could never make the phone resolve). Everything
 * that is NOT decidable here — the database unreachable, any persistence
 * failure that is not the idempotency constraint — is thrown, and the
 * webhook answers 500 so the provider retries. Swapping those two would
 * either lose messages silently (a 200 over an unpersisted message) or
 * make Meta retry forever on a message it can never accept.</p>
 *
 * <p><strong>Idempotency (T4): pre-check as fast path, constraint as the
 * authoritative guarantee.</strong> {@code existsByMessageId} catches the
 * ordinary retry without touching the insert path. A concurrent duplicate
 * is caught by the {@code uq_order_proposals_message_id} UNIQUE constraint
 * at flush time: the {@link DataIntegrityViolationException} is caught
 * OUTSIDE any transaction boundary — this handler is deliberately not
 * {@code @Transactional} and each repository call runs in its own
 * transaction — so the failed insert's transaction rolls back entirely and
 * there is no rollback-only transaction to propagate. Only a violation of
 * the messageId constraint is treated as "already processed"; any other
 * integrity violation is rethrown and becomes a 500, never a silent
 * swallow. A pre-check alone would be racy; the constraint alone would
 * explode inside a shared transaction; together they are correct.</p>
 *
 * <p><strong>A message without a USABLE {@code messageId} cannot be
 * deduplicated and is still recorded</strong>: it skips the pre-check, is
 * persisted with a NULL {@code message_id} (Postgres treats NULLs as
 * distinct in a unique index), and every arrival becomes its own row. No
 * synthesized key: two distinct id-less messages must never collide on the
 * dedup key. "Usable" means present AND non-blank — and that decision lives
 * in exactly ONE place, {@link #usableMessageId}, whose return value is
 * BOTH the pre-check condition AND the value persisted. A blank id stored
 * verbatim would make the unique index treat two distinct blank-id
 * messages as duplicates and silently discard the second with a 200 —
 * message loss (open gap 36: the sixth "one rule, two places" defect, the
 * first one that lost messages).</p>
 *
 * <p><strong>Ambiguous phones (open gap 29).</strong>
 * {@link PhoneResolution.Ambiguous} exposes only business ids; to name the
 * conflicting businesses in the failure reason, this handler re-reads them
 * by id and includes both ids and names (falling back to the bare id).</p>
 */
@Component
public class PersistingInboundMessageHandler implements IInboundMessageHandler {

	private static final Logger log = LoggerFactory.getLogger(PersistingInboundMessageHandler.class);

	/**
	 * Name of the UNIQUE constraint declared on {@code OrderProposal}; the
	 * only integrity violation this handler is allowed to swallow, because
	 * it means exactly "this message was already recorded".
	 */
	static final String MESSAGE_ID_UNIQUE_CONSTRAINT = "uq_order_proposals_message_id";

	private final OrderProposalRepository orderProposals;

	private final IBusinessPhoneResolver phoneResolver;

	private final BusinessRepository businesses;

	public PersistingInboundMessageHandler(OrderProposalRepository orderProposals,
			IBusinessPhoneResolver phoneResolver,
			BusinessRepository businesses) {

		this.orderProposals = orderProposals;
		this.phoneResolver = phoneResolver;
		this.businesses = businesses;
	}

	@Override
	public void handle(InboundMessage message) {

		String usableId = usableMessageId(message);
		if (usableId != null && orderProposals.existsByMessageId(usableId)) {
			log.info("Duplicate inbound message ignored (fast path): messageId={}", usableId);
			return;
		}

		PhoneResolution resolution = phoneResolver.resolve(message.fromPhone());
		OrderProposal proposal = buildProposal(message, usableMessageId(message), resolution);

		try {
			orderProposals.saveAndFlush(proposal);
		} catch (DataIntegrityViolationException conflict) {
			if (isMessageIdUniqueViolation(conflict)) {
				log.info("Duplicate inbound message ignored (unique constraint {}): messageId={}",
						MESSAGE_ID_UNIQUE_CONSTRAINT, usableMessageId(message));
				return;
			}
			throw conflict;
		}

		log.info("Inbound message recorded as order proposal id={} status={} business={}",
				proposal.getId(), proposal.getStatus(),
				proposal.getBusiness() == null ? null : proposal.getBusiness().getId());
	}

	/**
	 * THE single decision of whether this message carries a usable idempotency
	 * id — present and non-blank — and THE single source of the value stored:
	 * {@code handle} passes this return value both to the pre-check and to
	 * {@code buildProposal}, so "is there an id to dedup on" and "what gets
	 * persisted as message_id" cannot disagree. Anything unusable comes back
	 * as {@code null}, which is exactly what is persisted — a NULL that the
	 * unique index treats as distinct, never a blank string that it treats
	 * as a value (open gap 36).
	 */
	private String usableMessageId(InboundMessage message) {

		String id = message.externalId();
		return id == null || id.isBlank() ? null : id;
	}

	private OrderProposal buildProposal(InboundMessage message, String messageId,
			PhoneResolution resolution) {

		OrderProposal proposal = new OrderProposal();
		proposal.setChannel(message.channel());
		proposal.setMessageId(messageId);
		proposal.setFromPhone(message.fromPhone());
		proposal.setRawText(message.text());
		proposal.setReceivedAt(message.receivedAt());

		if (resolution instanceof PhoneResolution.Resolved resolved) {
			proposal.setBusiness(resolved.business());
			proposal.setStatus(ProposalStatus.PENDING);
		} else if (resolution instanceof PhoneResolution.NotFound) {
			proposal.setStatus(ProposalStatus.FAILED);
			proposal.setFailureReason("no business owns the sender phone: " + message.fromPhone());
		} else if (resolution instanceof PhoneResolution.Ambiguous ambiguous) {
			proposal.setStatus(ProposalStatus.FAILED);
			proposal.setFailureReason("ambiguous sender phone: " + message.fromPhone()
					+ "; conflicting businesses: " + describeConflictingBusinesses(ambiguous.businessIds()));
		} else {
			throw new IllegalStateException("Unknown phone resolution: " + resolution);
		}

		// The lines table exists (T5) but nothing produces lines yet: the
		// deterministic normalizer is T6. Until then every proposal has none.
		proposal.setItems(new java.util.ArrayList<OrderProposalItem>());
		return proposal;
	}

	/**
	 * Open gap 29: {@code Ambiguous} carries only ids, so the conflicting
	 * businesses are re-read here to name them in the reason; a business
	 * that cannot be re-read degrades to its bare id.
	 */
	private String describeConflictingBusinesses(List<Long> businessIds) {

		Map<Long, String> namesById = businesses.findAllById(businessIds).stream()
				.collect(Collectors.toMap(Business::getId,
						business -> business.getName() == null ? "" : business.getName()));

		return businessIds.stream()
				.map(id -> {
					String name = namesById.get(id);
					return name == null || name.isBlank() ? String.valueOf(id) : id + " (" + name + ")";
				})
				.collect(Collectors.joining(", "));
	}

	/**
	 * True only when the violation is THE messageId unique constraint. Any
	 * other integrity violation must surface as a 500, not be mistaken for
	 * an already-processed message.
	 */
	private boolean isMessageIdUniqueViolation(DataIntegrityViolationException conflict) {

		Throwable cause = conflict.getCause();
		while (cause != null) {
			if (cause instanceof ConstraintViolationException constraintViolation) {
				return MESSAGE_ID_UNIQUE_CONSTRAINT.equals(constraintViolation.getConstraintName());
			}
			cause = cause.getCause();
		}
		return conflict.getMessage() != null
				&& conflict.getMessage().contains(MESSAGE_ID_UNIQUE_CONSTRAINT);
	}
}
