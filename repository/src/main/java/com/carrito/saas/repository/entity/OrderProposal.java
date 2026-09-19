package com.carrito.saas.repository.entity;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import com.carrito.saas.repository.enums.ProposalStatus;

import jakarta.persistence.CascadeType;
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
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

/**
 * One inbound WhatsApp message recorded as a candidate order (T4 + T5 of
 * {@code odd/tasks/whatsapp-inbound.md}).
 *
 * <p>This entity serves TWO purposes at once, which is why T4 and T5 land
 * together: it is the record of the inbound message AND the idempotency
 * table keyed by Meta's {@code messageId}. A provider retry of the same
 * message finds this row (by {@code messageId}) and must not produce a
 * second proposal.</p>
 *
 * <p><strong>The proposal never reaches the kitchen — the guarantee is
 * structural, not an {@code if}.</strong> Proposals are NOT {@code Order}
 * rows: they do not participate in {@code OrderRepository.findActiveOrders}
 * (which only reads {@code orders}), are not emitted to the WebSocket
 * broadcast (whose only publisher is the order path), and do not decrement
 * stock ({@code createOrder} is the only stock writer). Confirming a
 * proposal (T7) will go through {@code createOrder}, the single writer.</p>
 *
 * <p><strong>The business reference is NULLABLE on purpose.</strong> A
 * message whose sender phone resolves to nothing or to more than one
 * business is still recorded — with {@link ProposalStatus#FAILED} and a
 * reason — because silently losing messages is the failure mode this
 * project has already paid for twice.</p>
 *
 * <p><strong>Idempotency design (T4).</strong> {@code message_id} carries an
 * explicitly named UNIQUE constraint
 * ({@code uq_order_proposals_message_id}) so the database is the
 * authoritative dedup guarantee and the violation is attributable by name.
 * The column is nevertheless NULLABLE — a deliberate deviation from "unique
 * and not null": a message that arrives WITHOUT a Meta id cannot be
 * deduplicated, and the two options were losing it silently (answering 200
 * without persisting) or inventing a synthesized key (which could collide
 * across distinct messages). Postgres treats NULLs as distinct in a unique
 * index, so an id-less message is recorded every time it arrives — never
 * lost, never falsely deduplicated, never colliding.</p>
 */
@Getter
@Setter
@Entity
@Table(name = "order_proposals",
		uniqueConstraints = @UniqueConstraint(
				name = "uq_order_proposals_message_id",
				columnNames = "message_id"))
public class OrderProposal {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	/** Channel identifier, e.g. {@code whatsapp}. */
	@Column(nullable = false)
	private String channel;

	/**
	 * Meta message id ({@code wamid...}) — the T4 idempotency key. Nullable:
	 * see the class javadoc for the no-id decision.
	 */
	@Column(name = "message_id")
	private String messageId;

	/** Sender phone exactly as the provider sent it, without normalization. */
	@Column(name = "from_phone")
	private String fromPhone;

	/** The message text, intact as the customer typed it. */
	@Column(name = "raw_text", nullable = false, columnDefinition = "text")
	private String rawText;

	/** Instant the message was accepted by this system. */
	@Column(name = "received_at")
	private Instant receivedAt;

	/**
	 * The business the message was routed to, or NULL when the sender phone
	 * resolved to nothing or to more than one business (then the proposal is
	 * {@link ProposalStatus#FAILED} with a reason).
	 */
	@ManyToOne(fetch = FetchType.LAZY)
	@JoinColumn(name = "business_id")
	private Business business;

	@Enumerated(EnumType.STRING)
	private ProposalStatus status;

	/**
	 * Why the proposal is {@link ProposalStatus#FAILED} (unresolvable or
	 * ambiguous phone, later also uninterpretable text); NULL otherwise.
	 */
	@Column(name = "failure_reason")
	private String failureReason;

	@CreationTimestamp
	@Column(name = "created_at")
	private LocalDateTime createdAt;

	@UpdateTimestamp
	@Column(name = "updated_at")
	private LocalDateTime updatedAt;

	/**
	 * Interpreted lines. Nothing produces lines yet — the deterministic
	 * normalizer is T6 — so the table exists now and stays empty.
	 */
	@OneToMany(mappedBy = "proposal", cascade = CascadeType.ALL, orphanRemoval = true)
	private List<OrderProposalItem> items = new ArrayList<>();

	@PrePersist
	public void prePersist() {

		if (status == null) {
			status = ProposalStatus.PENDING;
		}
	}
}
