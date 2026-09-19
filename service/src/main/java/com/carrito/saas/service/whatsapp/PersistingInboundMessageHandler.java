package com.carrito.saas.service.whatsapp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.hibernate.exception.ConstraintViolationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;

import com.carrito.saas.dto.MenuDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.OrderProposal;
import com.carrito.saas.repository.entity.OrderProposalItem;
import com.carrito.saas.repository.enums.ItemResolution;
import com.carrito.saas.repository.enums.ProposalStatus;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.OrderProposalRepository;
import com.carrito.saas.service.interfaces.IMenuService;

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
 *
 * <p><strong>Normalization (T6b): the lines reach the database.</strong> When
 * the phone resolves to exactly one business, the message text is normalized
 * against that business's menu — read through the EXISTING menu path
 * ({@link IMenuService#getMenu(String)}, no new query) — and every outcome
 * becomes a persisted {@link OrderProposalItem}, so every outcome is
 * representable and visible to an operator later without re-running the
 * normalizer (P1) and nothing is silently lost (P3): an
 * {@link NormalizationResult.Unresolved Unresolved} keeps its raw text, an
 * {@link NormalizationResult.Ambiguous Ambiguous} keeps its candidate names
 * (which the normalizer already carries — unlike the phone resolver's
 * id-only Ambiguous, open gap 29 — so no re-read was needed), and a
 * {@link NormalizationResult.Suggested Suggested} keeps its full candidate
 * line with the {@link ItemResolution#SUGGESTED} state a caller cannot
 * mistake for a resolution.</p>
 *
 * <p><strong>The P2 mapping from line outcomes to {@link ProposalStatus}, as
 * RULES (not cases):</strong></p>
 * <ol>
 *   <li><strong>R-routing</strong> — the phone did not resolve to exactly one
 *   business: {@link ProposalStatus#FAILED}, no lines (there is nothing to
 *   normalize against; unchanged T5 behaviour).</li>
 *   <li><strong>R-nothing</strong> — the phone resolved but NO outcome is
 *   attributable to the catalog (no Resolved, Suggested or Ambiguous):
 *   {@link ProposalStatus#FAILED} with a reason — nothing understood is not a
 *   proposal. The Unresolved lines are STILL persisted so the raw text
 *   survives (P3; FAILED is a business outcome, answered 200).</li>
 *   <li><strong>R-incomplete</strong> — at least one catalog-attributable
 *   outcome AND at least one line {@code SUGGESTED} or {@code AMBIGUOUS}:
 *   {@link ProposalStatus#PENDING}. The proposal is NOT complete; what it
 *   needs is said by the line states (explicit acceptance for SUGGESTED,
 *   customer precision for AMBIGUOUS) — exactly the T7/T8 obligations
 *   registered in T6a.</li>
 *   <li><strong>R-actionable</strong> — at least one catalog-attributable
 *   outcome AND every line {@code RESOLVED}: {@link ProposalStatus#PENDING},
 *   actionable.</li>
 * </ol>
 *
 * <p>R-incomplete and R-actionable both map to {@code PENDING} because the
 * enum (deliberately NOT extended here) has no "incomplete" state — the
 * actionable/incomplete distinction is carried by {@link ItemResolution} on
 * the lines, which is what T7's confirmation rule and T8's rendering
 * consume. Each rule is pinned by a test in
 * {@code WhatsappInboundPersistenceTests}.</p>
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

	/**
	 * The pure normalizer is stateless, so one instance serves every message;
	 * it is deliberately NOT a Spring bean (no Spring, no database — T6a).
	 */
	private final TextOrderNormalizer normalizer = new TextOrderNormalizer();

	private final OrderProposalRepository orderProposals;

	private final IBusinessPhoneResolver phoneResolver;

	private final BusinessRepository businesses;

	private final IMenuService menuService;

	public PersistingInboundMessageHandler(OrderProposalRepository orderProposals,
			IBusinessPhoneResolver phoneResolver,
			BusinessRepository businesses,
			IMenuService menuService) {

		this.orderProposals = orderProposals;
		this.phoneResolver = phoneResolver;
		this.businesses = businesses;
		this.menuService = menuService;
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
			applyNormalization(proposal, normalizeAgainstMenu(message.text(), resolved.business()));
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

		return proposal;
	}

	/**
	 * R-routing boundary: only a message whose phone resolved to exactly one
	 * business is normalized, against that business's menu read through the
	 * EXISTING path ({@link IMenuService#getMenu(String)} — no new query).
	 * A menu read failure is infrastructure, not a business outcome: it
	 * propagates and the webhook answers 500.
	 */
	private NormalizationResult normalizeAgainstMenu(String text, Business business) {

		MenuDTO menu = menuService.getMenu(business.getSlug());
		return normalizer.normalize(text, menu);
	}

	/**
	 * Every outcome becomes a persisted line (P1 + P3), and the P2 mapping
	 * decides the proposal status. The switch over the sealed outcome type is
	 * deliberately EXHAUSTIVE with NO default arm — adding a variant to
	 * {@code NormalizationResult} breaks compilation here instead of silently
	 * losing outcomes.
	 */
	private void applyNormalization(OrderProposal proposal, NormalizationResult result) {

		List<OrderProposalItem> items = new ArrayList<>();
		boolean catalogAttributable = false;
		boolean needsHumanInput = false;
		for (NormalizationResult.Outcome outcome : result.outcomes()) {
			OrderProposalItem item = new OrderProposalItem();
			item.setProposal(proposal);
			switch (outcome) {
				case NormalizationResult.Resolved(NormalizedLine line) -> {
					item.setProductId(line.productId());
					item.setComboId(line.comboId());
					item.setProductName(line.name());
					item.setQuantity(line.quantity());
					item.setRawLine(line.rawPhrase());
					item.setResolution(ItemResolution.RESOLVED);
					catalogAttributable = true;
				}
				case NormalizationResult.Suggested(NormalizedLine line) -> {
					item.setProductId(line.productId());
					item.setComboId(line.comboId());
					item.setProductName(line.name());
					item.setQuantity(line.quantity());
					item.setRawLine(line.rawPhrase());
					item.setResolution(ItemResolution.SUGGESTED);
					catalogAttributable = true;
					needsHumanInput = true;
				}
				case NormalizationResult.Ambiguous ambiguous -> {
					item.setQuantity(ambiguous.quantity());
					item.setRawLine(ambiguous.rawPhrase());
					item.setCandidates(String.join("\n", ambiguous.candidateNames()));
					item.setResolution(ItemResolution.AMBIGUOUS);
					catalogAttributable = true;
					needsHumanInput = true;
				}
				case NormalizationResult.Unresolved unresolved -> {
					item.setRawLine(unresolved.rawPhrase());
					item.setResolution(ItemResolution.UNRESOLVED);
				}
			}
			items.add(item);
		}
		proposal.setItems(items);

		if (!catalogAttributable) {
			// R-nothing: nothing understood is not a proposal — but the raw text
			// stays on the lines (P3), so the record is still worth reading.
			proposal.setStatus(ProposalStatus.FAILED);
			proposal.setFailureReason("no catalog item could be matched to this message; the text is preserved verbatim on the proposal lines");
		}
		else {
			// R-incomplete and R-actionable: both PENDING; the difference
			// (needs human input vs actionable) is carried by the line states.
			proposal.setStatus(ProposalStatus.PENDING);
		}
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
