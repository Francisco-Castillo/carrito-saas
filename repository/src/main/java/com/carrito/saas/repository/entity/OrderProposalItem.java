package com.carrito.saas.repository.entity;

import com.carrito.saas.repository.enums.ItemResolution;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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
 * One interpreted line of an {@link OrderProposal} (T5 + T6b of
 * {@code odd/tasks/whatsapp-inbound.md}).
 *
 * <p>Since T6b the normalizer's EVERY outcome is representable here and
 * visible to an operator reading the table later, without re-running the
 * normalizer (P1):</p>
 *
 * <ul>
 *   <li>a product line carries {@code product_id}; a COMBO line carries
 *   {@code combo_id} — exactly one of the two, mirroring the XOR the
 *   normalizer's {@code NormalizedLine} enforces (the entity is data and does
 *   not re-check it);</li>
 *   <li>{@code resolution} distinguishes resolved / suggested / ambiguous /
 *   unresolved — the per-line state {@link ProposalStatus} cannot carry (see
 *   {@link ItemResolution});</li>
 *   <li>an ambiguous line keeps its candidate NAMES in {@code candidates}
 *   (newline-separated), so the choice is visible without re-reading the
 *   catalog — the normalizer's Ambiguous carries names, NOT ids (the matcher
 *   never chooses); the id-only ambiguity of the PHONE resolver (open gap 29)
 *   is a different object and needed no re-read here;</li>
 *   <li>an unresolved line keeps the raw text verbatim in {@code raw_line},
 *   with no ids, no name and no quantity — losing what was not understood
 *   would defeat the purpose of recording the message (P3).</li>
 * </ul>
 *
 * <p><strong>Pre-existing databases (no Flyway, same precedent as
 * {@code Business.slug}/{@code Business.phone}).</strong> {@code ddl-auto:
 * update} ADDS the three new columns to an existing table, but on a table
 * that already has rows the NOT NULL on {@code resolution} needs the rows
 * backfilled first. Hand-applied SQL for such a database:</p>
 *
 * <pre>{@code
 * UPDATE order_proposal_items SET resolution = 'UNRESOLVED' WHERE resolution IS NULL;
 * ALTER TABLE order_proposal_items ALTER COLUMN resolution SET NOT NULL;
 * }</pre>
 *
 * <p>On an empty table (the state of this table before T6b) {@code ddl-auto}
 * creates the column NOT NULL directly and no hand SQL is needed.</p>
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
	 * The catalog product this line resolved to (or was suggested as a prefix
	 * candidate for), or NULL when the line is a combo line, ambiguous or
	 * unresolved.
	 */
	@Column(name = "product_id")
	private Long productId;

	/**
	 * The catalog combo this line resolved to (or was suggested as a prefix
	 * candidate for), or NULL. A line carries a product id XOR a combo id —
	 * the same XOR {@code NormalizedLine} enforces at the source.
	 */
	@Column(name = "combo_id")
	private Long comboId;

	/** Product or combo name as matched by the normalizer; NULL when ambiguous or unresolved. */
	@Column(name = "product_name")
	private String productName;

	/** Requested quantity; NULL when nothing was quantifiable (unresolved text). */
	private Integer quantity;

	/** The raw text of the line, intact as the customer typed it. */
	@Column(name = "raw_line", columnDefinition = "text")
	private String rawLine;

	/**
	 * How the normalizer treated the phrase behind this line. NOT NULL: a line
	 * without a state is unrepresentable — see the class javadoc for the
	 * pre-existing-database backfill.
	 */
	@Enumerated(EnumType.STRING)
	@Column(name = "resolution", nullable = false)
	private ItemResolution resolution;

	/**
	 * Candidate NAMES of an ambiguous line, newline-separated; NULL for every
	 * other state. Names, not ids: the normalizer's Ambiguous never carries
	 * ids, because ambiguity is resolved by the customer re-writing the order,
	 * never by choosing.
	 */
	@Column(name = "candidates", columnDefinition = "text")
	private String candidates;
}
