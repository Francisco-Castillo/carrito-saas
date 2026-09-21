package com.carrito.saas.dto;

import com.carrito.saas.repository.enums.OperatorAction;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * The operator's decision on ONE line of a proposal (T7b.1 of
 * {@code odd/tasks/whatsapp-inbound.md}), body of
 * {@code PATCH /api/business/proposals/{id}/items/{itemId}}.
 *
 * <ul>
 *   <li>{@code action} is REQUIRED ({@code @NotNull} → 400 when absent).</li>
 *   <li>{@code chosenName} is required ONLY for {@code OperatorAction#CHOSEN}
 *     — and PROHIBITED for the other actions, so no unused data is accepted.
 *     The conditional half is business validation (400 via
 *     {@code BusinessException(VALIDATION)}), not bean validation, because
 *     {@code @Valid} cannot express "required only when the sibling field
 *     equals CHOSEN".</li>
 *   <li>The choice travels as a candidate NAME, never an id: the system never
 *     had the ids of the stored candidates. The service re-resolves the name
 *     against the live catalog — an id from the request would be an
 *     unverified claim about a line that becomes an order.</li>
 * </ul>
 */
@Data
public class LineDecisionRequestDTO {

	@NotNull
	private OperatorAction action;

	/** Required only when {@code action} is {@code CHOSEN}; rejected otherwise. */
	private String chosenName;

}
