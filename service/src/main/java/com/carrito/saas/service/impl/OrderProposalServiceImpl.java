package com.carrito.saas.service.impl;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.exception.BusinessException;
import com.carrito.saas.exception.ErrorType;
import com.carrito.saas.dto.OrderProposalDTO;
import com.carrito.saas.dto.OrderProposalItemDTO;
import com.carrito.saas.repository.entity.OrderProposal;
import com.carrito.saas.repository.entity.OrderProposalItem;
import com.carrito.saas.repository.enums.ItemResolution;
import com.carrito.saas.repository.jpa.OrderProposalRepository;
import com.carrito.saas.service.interfaces.IOrderProposalService;

/**
 * Read and discard of order proposals (T7a.1 of
 * {@code odd/tasks/whatsapp-inbound.md}).
 *
 * <p>Rejecting is a bare state flip decided by a CAS update: it creates no
 * order, touches no stock and deletes no line — the proposal is recorded as
 * discarded, that is all. The confirmation path (T7a.2) is the ONLY road to
 * an order, and it must go through {@code createOrder}, the single
 * writer.</p>
 */
@Service
public class OrderProposalServiceImpl implements IOrderProposalService {

	private final OrderProposalRepository orderProposalRepository;

	public OrderProposalServiceImpl(OrderProposalRepository orderProposalRepository) {
		this.orderProposalRepository = orderProposalRepository;
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
