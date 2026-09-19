package com.carrito.saas.repository.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One interpreted line of an {@link OrderProposal} (T5 of
 * {@code odd/tasks/whatsapp-inbound.md}).
 *
 * <p>The table exists now, but nothing produces lines yet: the deterministic
 * normalizer that turns free text into candidate lines is T6. The columns
 * are the minimum T6 needs to fill in — what the customer wrote, and, when
 * it resolved against the catalog, what it meant.</p>
 */
@Getter
@Setter
@Entity
@Table(name = "order_proposal_items")
public class OrderProposalItem {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "proposal_id", nullable = false)
	private OrderProposal proposal;

	/**
	 * The catalog product this line resolved to, or NULL when the normalizer
	 * could not resolve it (an unresolved phrase is a first-class outcome,
	 * shown to the human — it never guesses).
	 */
	@Column(name = "product_id")
	private Long productId;

	/** Product name as resolved, or the raw phrase when unresolved. */
	@Column(name = "product_name")
	private String productName;

	/** Requested quantity; NULL when the normalizer could not read one. */
	private Integer quantity;

	/** The raw text of the line, intact as the customer typed it. */
	@Column(name = "raw_line", columnDefinition = "text")
	private String rawLine;
}
