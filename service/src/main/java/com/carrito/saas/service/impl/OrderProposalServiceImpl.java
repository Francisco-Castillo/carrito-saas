package com.carrito.saas.service.impl;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.dto.ConfirmProposalRequestDTO;
import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.dto.OrderItemDTO;
import com.carrito.saas.dto.OrderProposalDTO;
import com.carrito.saas.dto.OrderProposalItemDTO;
import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.repository.entity.OrderProposal;
import com.carrito.saas.repository.entity.OrderProposalItem;
import com.carrito.saas.repository.enums.ItemResolution;
import com.carrito.saas.repository.enums.OrderType;
import com.carrito.saas.repository.enums.ProposalStatus;
import com.carrito.saas.repository.jpa.OrderProposalRepository;
import com.carrito.saas.service.interfaces.IOrderProposalService;
import com.carrito.saas.service.interfaces.IOrderService;

/**
 * Read, discard and CONFIRM of order proposals (T7a of
 * {@code odd/tasks/whatsapp-inbound.md}).
 *
 * <p>Rejecting is a bare state flip decided by a CAS update: it creates no
 * order, touches no stock and deletes no line — the proposal is recorded as
 * discarded, that is all. Confirming is the ONLY road to an order, and it
 * goes through {@code createOrder}, the single writer: no stock decrement,
 * price re-lookup, orderNumber, broadcast or metric is ever duplicated
 * here.</p>
 *
 * <p>Module note: this class broadcasts NOTHING and counts NOTHING — the
 * {@code service} module deliberately has no {@code spring-messaging} (a
 * structural guarantee pinned by the suite), so the announcement belongs to
 * the web edge ({@code api.OrderAnnouncer}), run AFTER this transactional
 * method returns.</p>
 */
@Service
public class OrderProposalServiceImpl implements IOrderProposalService {

	private final OrderProposalRepository orderProposalRepository;
	private final IOrderService orderService;

	public OrderProposalServiceImpl(OrderProposalRepository orderProposalRepository, IOrderService orderService) {
		this.orderProposalRepository = orderProposalRepository;
		this.orderService = orderService;
	}

	@Override
	@Transactional(readOnly = true)
	public List<OrderProposalDTO> listPendingProposals(Long businessId) {

		return orderProposalRepository.findPendingByBusinessId(businessId).stream()
				.map(OrderProposalServiceImpl::toDTO)
				.toList();
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public OrderProposalDTO rejectProposal(Long proposalId, Long businessId) {

		int updated = orderProposalRepository.rejectIfPending(proposalId, businessId, LocalDateTime.now());

		if (updated == 1) {
			// The bulk update bypassed the persistence context
			// (clearAutomatically detached the stale managed entity), so this
			// is a fresh SELECT serving the REJECTED state.
			return toDTO(orderProposalRepository.findByIdAndBusinessId(proposalId, businessId)
					.orElseThrow(() -> new BusinessException("Proposal not found", ErrorType.NOT_FOUND)));
		}

		// 0 rows: distinguish absent-or-foreign (404) from already-decided
		// (409) with an ownership-scoped read — never a second CAS.
		if (orderProposalRepository.findByIdAndBusinessId(proposalId, businessId).isPresent()) {
			throw new BusinessException("Proposal is not pending", ErrorType.CONFLICT);
		}
		throw new BusinessException("Proposal not found", ErrorType.NOT_FOUND);
	}

	/**
	 * The SINGLE colocability rule, shared with the confirmation path (T7a.2
	 * calls this exact function to gate {@code createOrder}): a proposal is
	 * confirmable when it has a business, has at least one line, EVERY line is
	 * {@code RESOLVED}, and every RESOLVED line carries exactly one of
	 * {@code productId}/{@code comboId}. Any {@code SUGGESTED},
	 * {@code AMBIGUOUS} or {@code UNRESOLVED} line blocks the whole proposal —
	 * nothing reaches an order that the operator has not accepted.
	 */
	public static boolean isConfirmable(OrderProposal proposal) {

		return proposal.getBusiness() != null
				&& !proposal.getItems().isEmpty()
				&& proposal.getItems().stream().allMatch(OrderProposalServiceImpl::isPlaceableLine);
	}

	/**
	 * T7a.2 — the confirmation core. ONE transaction (so a writer failure
	 * rolls the claim back and the proposal is PENDING again, retryable), in
	 * an order that matters:
	 *
	 * <ol>
	 *   <li>ownership-scoped read: absent, ANOTHER BUSINESS's, or a FAILED
	 *     proposal (which has no business at all) → 404, never 403, so a
	 *     foreign id is indistinguishable from a missing one;</li>
	 *   <li>not PENDING (already CONFIRMED/REJECTED) → 409;</li>
	 *   <li>THE GATE: {@link #isConfirmable} — a single non-RESOLVED line
	 *     blocks the WHOLE proposal with 409. Nothing touches stock before
	 *     this;</li>
	 *   <li>operator-data validation and {@code OrderRequestDTO} assembly
	 *     BEFORE the CAS — the writer's own validation errors are plain
	 *     {@code RuntimeException} (500), so they must never be reached with
	 *     bad data; and the CAS's bulk update clears the persistence context,
	 *     so everything the writer needs is captured from the managed entity
	 *     first (including the slug, from the proposal's OWN business — never
	 *     from the request);</li>
	 *   <li>THE CLAIM: a CAS bulk update PENDING → CONFIRMED scoped by id AND
	 *     business AND status. 0 rows ⇒ 409 — the loser of a race creates
	 *     nothing and decrements nothing;</li>
	 *   <li>{@code createOrder(slug, dto)} — the single writer;</li>
	 *   <li>the created order id is recorded on the proposal with one more
	 *     bulk update guarded by {@code order_id IS NULL}; 0 rows ⇒
	 *     {@code IllegalStateException}, the whole transaction rolls back.</li>
	 * </ol>
	 *
	 * <p><strong>Invariants.</strong> (a) The CAS IS the "at most one order
	 * per proposal" mechanism — the {@code UNIQUE} on {@code order_id} only
	 * proves an order is not attributed to TWO proposals. (b) One transaction
	 * end to end: if {@code createOrder} fails (insufficient stock), the CAS
	 * rolls back and the proposal is PENDING again — that is what makes
	 * "stock decremented once" true in both directions. (c) The bulk updates
	 * bypass the persistence context, so the (now detached)
	 * {@code OrderProposal} entity is NEVER mutated after the claim —
	 * {@code order_id} is written with JPQL.</p>
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public OrderDTO confirmProposal(Long proposalId, Long businessId, ConfirmProposalRequestDTO request) {

		// 1. Ownership-scoped read (404, never 403: no existence leak).
		OrderProposal proposal = orderProposalRepository.findByIdAndBusinessId(proposalId, businessId)
				.orElseThrow(() -> new BusinessException("Proposal not found", ErrorType.NOT_FOUND));

		// 2. Already decided.
		if (proposal.getStatus() != ProposalStatus.PENDING) {
			throw new BusinessException("Proposal is not pending", ErrorType.CONFLICT);
		}

		// 3. THE GATE: a non-RESOLVED line blocks the whole proposal.
		if (!isConfirmable(proposal)) {
			throw new BusinessException(
					"A non-RESOLVED line blocks the whole proposal: nothing can be confirmed until every line is resolved",
					ErrorType.CONFLICT);
		}

		// Everything the rest of the method needs, captured BEFORE the claim:
		// the CAS's bulk update clears the persistence context and detaches
		// the entity, so no lazy access is legal after it.
		String slug = proposal.getBusiness().getSlug();
		OrderRequestDTO orderRequest = buildOrderRequest(proposal, request);

		// 5. THE CLAIM, before any stock is touched.
		int claimed = orderProposalRepository.confirmIfPending(proposalId, businessId, LocalDateTime.now());
		if (claimed == 0) {
			throw new BusinessException("Proposal was confirmed by another operator", ErrorType.CONFLICT);
		}

		// 6. The single writer.
		OrderDTO order = orderService.createOrder(slug, orderRequest);

		// 7. Attribute the order; 0 rows means the claim was lost or already
		// attributed — fail the whole transaction.
		int recorded = orderProposalRepository.recordOrderId(proposalId, order.getOrderId(), LocalDateTime.now());
		if (recorded == 0) {
			throw new IllegalStateException(
					"Proposal %d lost its claim before order attribution".formatted(proposalId));
		}

		return order;
	}

	/**
	 * Operator-data validation and {@code OrderRequestDTO} assembly (eje 1),
	 * run BEFORE the CAS: the writer validates the same data but fails with a
	 * plain {@code RuntimeException} (a 500), and after the CAS the proposal
	 * entity is detached — so both the validation and the assembly must
	 * happen while it is still managed.
	 */
	private static OrderRequestDTO buildOrderRequest(OrderProposal proposal, ConfirmProposalRequestDTO request) {

		// The operator's name is a CORRECTION of Meta's draft; blank falls
		// back to the draft, and two blanks are a 400.
		String customerName = request.getCustomerName() != null && !request.getCustomerName().isBlank()
				? request.getCustomerName()
				: proposal.getCustomerName();
		if (customerName == null || customerName.isBlank()) {
			throw new BusinessException(
					"Customer name is required: the request was blank and the proposal carries no draft",
					ErrorType.VALIDATION);
		}
		if (request.getOrderType() == null) {
			throw new BusinessException("Order type is required", ErrorType.VALIDATION);
		}
		if (request.getPaymentMethod() == null) {
			throw new BusinessException("Payment method is required", ErrorType.VALIDATION);
		}
		if (request.getOrderType() == OrderType.DELIVERY
				&& (request.getCustomerAddress() == null || request.getCustomerAddress().isBlank())) {
			throw new BusinessException("Customer address is required for DELIVERY", ErrorType.VALIDATION);
		}

		OrderRequestDTO dto = new OrderRequestDTO();
		dto.setCustomerName(customerName);
		// The phone comes FREE from the proposal: the same datum, never the
		// operator's hands.
		dto.setCustomerPhone(proposal.getFromPhone());
		dto.setCustomerAddress(request.getCustomerAddress());
		dto.setOrderType(request.getOrderType());
		dto.setPaymentMethod(request.getPaymentMethod());
		dto.setNotes(request.getNotes());
		dto.setItems(proposal.getItems().stream().map(OrderProposalServiceImpl::toOrderItemDTO).toList());
		return dto;
	}

	/**
	 * Line → order item mapping.
	 *
	 * <p><strong>REVIEW OBLIGATION — this switch has NO {@code default} arm
	 * ON PURPOSE.</strong> The sealed {@code ItemResolution} only forces
	 * exhaustiveness while there is no {@code default}: adding one compiles
	 * and silently changes what can be placed. Every non-RESOLVED arm throws:
	 * the gate makes them unreachable through the confirm endpoint, but the
	 * mapping itself must be STRUCTURALLY unable to place an unaccepted
	 * line. {@code OrderProposalConfirmationTests
	 * #theLineMappingRefusesToPlaceNonResolvedLinesEvenWithoutTheGate} pins
	 * this: adding {@code default ->} that places the line must BREAK that
	 * test.</p>
	 */
	private static OrderItemDTO toOrderItemDTO(OrderProposalItem line) {

		return switch (line.getResolution()) {
			case RESOLVED -> {
				OrderItemDTO item = new OrderItemDTO();
				item.setProductId(line.getProductId());
				item.setComboId(line.getComboId());
				item.setQuantity(line.getQuantity());
				item.setProductName(line.getProductName());
				yield item;
			}
			case SUGGESTED ->
				throw new IllegalStateException("SUGGESTED line needs explicit acceptance: " + line.getId());
			case AMBIGUOUS ->
				throw new IllegalStateException("AMBIGUOUS line needs a choice: " + line.getId());
			case UNRESOLVED ->
				throw new IllegalStateException("UNRESOLVED line has nothing to place: " + line.getId());
		};
	}

	private static boolean isPlaceableLine(OrderProposalItem item) {

		return item.getResolution() == ItemResolution.RESOLVED
				&& (item.getProductId() != null) ^ (item.getComboId() != null);
	}

	private static OrderProposalDTO toDTO(OrderProposal proposal) {

		OrderProposalDTO dto = new OrderProposalDTO();
		dto.setId(proposal.getId());
		dto.setChannel(proposal.getChannel());
		dto.setFromPhone(proposal.getFromPhone());
		dto.setCustomerName(proposal.getCustomerName());
		dto.setRawText(proposal.getRawText());
		dto.setReceivedAt(proposal.getReceivedAt());
		dto.setStatus(proposal.getStatus());
		dto.setFailureReason(proposal.getFailureReason());
		dto.setConfirmable(isConfirmable(proposal));
		dto.setItems(proposal.getItems().stream().map(OrderProposalServiceImpl::toItemDTO).toList());
		return dto;
	}

	private static OrderProposalItemDTO toItemDTO(OrderProposalItem item) {

		OrderProposalItemDTO dto = new OrderProposalItemDTO();
		dto.setId(item.getId());
		dto.setProductId(item.getProductId());
		dto.setComboId(item.getComboId());
		dto.setProductName(item.getProductName());
		dto.setQuantity(item.getQuantity());
		dto.setRawLine(item.getRawLine());
		dto.setResolution(item.getResolution());
		// VERBATIM: the writer's newline-joined candidate names, untouched.
		dto.setCandidates(item.getCandidates());
		return dto;
	}
}
