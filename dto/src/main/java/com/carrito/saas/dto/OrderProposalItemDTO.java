package com.carrito.saas.dto;

import com.carrito.saas.repository.enums.ItemResolution;

import lombok.Data;

/**
 * Operator-facing view of one interpreted line of an
 * {@link com.carrito.saas.repository.entity.OrderProposal} (T7a.1).
 *
 * <p>{@code candidates} is exposed VERBATIM — the candidate names exactly as
 * the writer stored them, newline-joined. Nothing parses or reformats it:
 * T7a does not consume it (only {@code RESOLVED} is placeable), and a single
 * encode/decode point is a documented obligation before anyone reads it
 * (open gap 55).</p>
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

	private String candidates;

}
