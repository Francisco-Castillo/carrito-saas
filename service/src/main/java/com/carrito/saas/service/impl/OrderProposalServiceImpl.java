package com.carrito.saas.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.dto.ConfirmProposalRequestDTO;
import com.carrito.saas.dto.LineDecisionRequestDTO;
import com.carrito.saas.dto.MenuDTO;
import com.carrito.saas.dto.OrderDTO;
import com.carrito.saas.dto.OrderItemDTO;
import com.carrito.saas.dto.OrderProposalDTO;
import com.carrito.saas.dto.OrderProposalItemDTO;
import com.carrito.saas.dto.OrderRequestDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.entity.OrderProposal;
import com.carrito.saas.repository.entity.OrderProposalItem;
import com.carrito.saas.repository.enums.ItemResolution;
import com.carrito.saas.repository.enums.OperatorAction;
import com.carrito.saas.repository.enums.OrderType;
import com.carrito.saas.repository.enums.ProposalStatus;
import com.carrito.saas.repository.jpa.OrderProposalRepository;
import com.carrito.saas.service.interfaces.IMenuService;
import com.carrito.saas.service.interfaces.IOrderProposalService;
import com.carrito.saas.service.interfaces.IOrderService;
import com.carrito.saas.service.whatsapp.ProposalCandidates;
import com.carrito.saas.service.whatsapp.TextNormalization;

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
	private final IMenuService menuService;

	public OrderProposalServiceImpl(OrderProposalRepository orderProposalRepository, IOrderService orderService,
			IMenuService menuService) {
		this.orderProposalRepository = orderProposalRepository;
		this.orderService = orderService;
		this.menuService = menuService;
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
	 * THE legality matrix of the operator's per-line action (T7b.1), in ONE
	 * place: {@code SUGGESTED -> {ACCEPTED, DISCARDED}}, {@code AMBIGUOUS ->
	 * {CHOSEN, DISCARDED}}, {@code UNRESOLVED -> {DISCARDED}}, {@code RESOLVED
	 * -> {}} (a RESOLVED line is already acted upon; accepting an action on it
	 * would mean inventing a second semantics for it). Anything outside this
	 * matrix is a STATE conflict (409), never a malformed request (400).
	 *
	 * <p>Exhaustive switch with NO {@code default} arm — same review
	 * obligation as the line mapping: adding an {@code ItemResolution} value
	 * must break compilation here instead of silently widening the matrix.
	 */
	public static boolean isLegalAction(ItemResolution resolution, OperatorAction action) {

		return switch (resolution) {
			case RESOLVED -> false;
			case SUGGESTED -> action == OperatorAction.ACCEPTED || action == OperatorAction.DISCARDED;
			case AMBIGUOUS -> action == OperatorAction.CHOSEN || action == OperatorAction.DISCARDED;
			case UNRESOLVED -> action == OperatorAction.DISCARDED;
		};
	}

	/**
	 * T7b.1 — the operator's decision on ONE line. ONE transaction, in an
	 * order that matters:
	 *
	 * <ol>
	 *   <li>ownership-scoped read of the proposal (absent or ANOTHER
	 *     business's → 404, never 403 — no existence leak);</li>
	 *   <li>not PENDING (already CONFIRMED/REJECTED) → 409;</li>
	 *   <li>the line must belong to THAT proposal → 404 (an item id that
	 *     exists elsewhere is foreign HERE);</li>
	 *   <li>THE legality matrix ({@link #isLegalAction}) → 409 when the pair
	 *     action×resolution is not legal. The matrix is judged BEFORE the
	 *     chosenName shape rules on purpose: a state conflict (409) must never
	 *     be masked by a data-shape complaint (400) about a name the action
	 *     would have ignored anyway;</li>
	 *   <li>THEN the chosenName shape rules (400): required for CHOSEN,
	 *     prohibited otherwise — the "request itself is malformed" class;</li>
	 *   <li>for CHOSEN: the name must be one of the STORED candidates (409)
	 *     and re-resolve against the LIVE catalog through the normal read path
	 *     to EXACTLY one item (409 — the catalog changed; choosing a name that
	 *     resolves to nothing or to two items would write a wrong line);</li>
	 *   <li>THE WRITE: a CAS bulk update scoped by item AND proposal AND
	 *     business AND PENDING. 0 rows ⇒ re-classify with a scoped read
	 *     (foreign/missing → 404, decided → 409) — the race path;</li>
	 *   <li>fresh re-read and DTO: the bulk update cleared the persistence
	 *     context, so nothing stale can be served.</li>
	 * </ol>
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	public OrderProposalDTO decideLine(Long proposalId, Long itemId, Long businessId, LineDecisionRequestDTO request) {

		// 2-3. Ownership-scoped read; foreign/absent proposal → 404, decided → 409.
		OrderProposal proposal = orderProposalRepository.findByIdAndBusinessId(proposalId, businessId)
				.orElseThrow(() -> new BusinessException("Proposal not found", ErrorType.NOT_FOUND));
		if (proposal.getStatus() != ProposalStatus.PENDING) {
			throw new BusinessException("Proposal is not pending", ErrorType.CONFLICT);
		}

		// 4. The line must belong to THAT proposal.
		OrderProposalItem line = proposal.getItems().stream()
				.filter(item -> item.getId().equals(itemId))
				.findFirst()
				.orElseThrow(() -> new BusinessException("Line not found on this proposal", ErrorType.NOT_FOUND));

		// 5. THE legality matrix — judged BEFORE the chosenName shape rules so a
		// state conflict is never masked by a data-shape complaint.
		if (!isLegalAction(line.getResolution(), request.getAction())) {
			throw new BusinessException("Action %s is not legal for a %s line".formatted(request.getAction(),
					line.getResolution()), ErrorType.CONFLICT);
		}

		// 6. Request shape (400) — required for CHOSEN, prohibited otherwise.
		if (request.getAction() == OperatorAction.CHOSEN
				&& (request.getChosenName() == null || request.getChosenName().isBlank())) {
			throw new BusinessException("chosenName is required when the action is CHOSEN", ErrorType.VALIDATION);
		}
		if (request.getAction() != OperatorAction.CHOSEN && request.getChosenName() != null) {
			throw new BusinessException("chosenName is only accepted when the action is CHOSEN", ErrorType.VALIDATION);
		}

		// 7. The CHOSEN resolution: stored candidate first, live catalog second.
		Long productId = line.getProductId();
		Long comboId = line.getComboId();
		String chosenName = null;
		if (request.getAction() == OperatorAction.CHOSEN) {
			ResolvedChoice resolved = resolveChosenName(line, request.getChosenName(), proposal.getBusiness());
			productId = resolved.productId();
			comboId = resolved.comboId();
			chosenName = request.getChosenName();
		}

		// 8. THE WRITE: scoped CAS.
		int updated = orderProposalRepository.decideLineIfPending(itemId, proposalId, businessId,
				request.getAction(), chosenName, productId, comboId);
		if (updated == 0) {
			// 0 rows: re-classify with a scoped read — never a second CAS.
			if (orderProposalRepository.findByIdAndBusinessId(proposalId, businessId)
					.map(found -> found.getStatus() != ProposalStatus.PENDING)
					.orElse(false)) {
				throw new BusinessException("Proposal is not pending", ErrorType.CONFLICT);
			}
			throw new BusinessException("Line not found on this proposal", ErrorType.NOT_FOUND);
		}

		// 9. Fresh re-read: the CAS cleared the persistence context.
		return toDTO(orderProposalRepository.findByIdAndBusinessId(proposalId, businessId)
				.orElseThrow(() -> new BusinessException("Proposal not found", ErrorType.NOT_FOUND)));
	}

	/** The ids a CHOSEN line carries after the service re-resolved the name. */
	private record ResolvedChoice(Long productId, Long comboId) {
	}

	/**
	 * The chosen name must (a) be one of the names STORED on the line — the
	 * system never had candidate ids, so membership is the only thing a name
	 * can be checked against — and (b) re-resolve against the LIVE catalog
	 * through the EXISTING read path with the SAME normalization as the
	 * normalizer ({@link TextNormalization#tokens}, full-name exact match —
	 * no new comparison, no fuzzy matching). Exactly one product or combo must
	 * match; zero (the candidate vanished) or two (a product and a combo, or
	 * two items, share the normalized name) are a 409: choosing a name that no
	 * longer maps to exactly one item would write a line the catalog cannot
	 * honour.
	 */
	private ResolvedChoice resolveChosenName(OrderProposalItem line, String chosenName, Business business) {

		List<String> storedCandidates = ProposalCandidates.split(line.getCandidates());
		if (!storedCandidates.contains(chosenName)) {
			throw new BusinessException("Chosen name is not one of the stored candidates", ErrorType.CONFLICT);
		}

		MenuDTO menu = menuService.getMenu(business.getSlug());
		List<String> key = TextNormalization.tokens(chosenName);
		List<ResolvedChoice> matches = new ArrayList<>();
		if (!key.isEmpty()) {
			for (var product : menu.getProducts()) {
				if (TextNormalization.tokens(product.getName()).equals(key)) {
					matches.add(new ResolvedChoice(product.getId(), null));
				}
			}
			for (var combo : menu.getCombos()) {
				if (TextNormalization.tokens(combo.getName()).equals(key)) {
					matches.add(new ResolvedChoice(null, combo.getId()));
				}
			}
		}
		if (matches.size() != 1) {
			throw new BusinessException(
					"Chosen name no longer resolves to exactly one live catalog item (%d matches)".formatted(matches.size()),
					ErrorType.CONFLICT);
		}
		return matches.get(0);
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

		// 7. Attribute the order, scoped to THIS business; 0 rows means the
		// claim was lost, the proposal is not this business's, or the order
		// was already attributed — fail the whole transaction.
		int recorded = orderProposalRepository.recordOrderId(proposalId, businessId, order.getOrderId(),
				LocalDateTime.now());
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
		// VERBATIM (decoded): the candidate names go through the ONE codec, so
		// the newline-joined storage format never leaks to a consumer.
		dto.setCandidateNames(ProposalCandidates.split(item.getCandidates()));
		dto.setOperatorAction(item.getOperatorAction());
		dto.setChosenName(item.getChosenName());
		return dto;
	}
}
