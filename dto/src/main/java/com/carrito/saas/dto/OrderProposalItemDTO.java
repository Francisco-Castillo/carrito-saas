package com.carrito.saas.dto;

import java.util.List;

import com.carrito.saas.repository.enums.ItemResolution;
import com.carrito.saas.repository.enums.OperatorAction;

import lombok.Data;

/**
 * Operator-facing view of one interpreted line of an
 * {@link com.carrito.saas.repository.entity.OrderProposal} (T7a.1, extended
 * by T7b.1).
 *
 * <p>Since T7b.1 the candidates are exposed as a LIST of names
 * ({@code candidateNames}), decoded through the single codec
 * ({@code ProposalCandidates}) — the raw newline-joined text is never served.
 * This is a deliberate contract change from T7a, which exposed it verbatim
 * because nothing consumed it (open gap 55).</p>
 *
 * <p>{@code operatorAction} and {@code chosenName} mirror the operator's
 * recorded decision; both are {@code null} until the operator acts (T7b.1).</p>
 */
@Data
public class OrderProposalItemDTO {

	private Long id;

	private Long productId;

	private Long comboId;

	private String productName;

	private Integer quantity;

	private String rawLine;

	private ItemResolution resolution;

	/** Candidate NAMES of an ambiguous line, decoded by the single codec. */
	private List<String> candidateNames;

	private OperatorAction operatorAction;

	private String chosenName;

}
