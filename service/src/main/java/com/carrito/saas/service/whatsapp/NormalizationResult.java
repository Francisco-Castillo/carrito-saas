package com.carrito.saas.service.whatsapp;

import java.util.ArrayList;
import java.util.List;

/**
 * Outcome of normalizing one free-text WhatsApp message against a
 * {@link com.carrito.saas.dto.MenuDTO} catalog (T6a of
 * {@code odd/tasks/whatsapp-inbound.md}). Ordered list of one
 * {@link Outcome} per phrase the matcher identified, in message order.
 *
 * <p>The {@link Outcome} hierarchy is modeled on {@link PhoneResolution}: a
 * sealed interface whose compiler-checked variants make the two T6a errors
 * impossible to write.</p>
 *
 * <ul>
 *   <li>An ambiguous phrase carries its candidate NAMES and its quantity, and
 *   structurally carries NO product or combo id — a caller cannot treat an
 *   ambiguous line as resolved, nor "pick one" from the result: resolving it
 *   requires the customer to write a more specific phrase.</li>
 *   <li>A PREFIX match NEVER resolves — whatever its length, whatever the
 *   function-word class says. Exactly one candidate → {@link Suggested}: a
 *   distinct outcome the caller must acknowledge explicitly, carrying the
 *   full candidate line so acceptance can place it. Two or more candidates →
 *   {@link Ambiguous}, as before. The closed function-word class is demoted
 *   to a QUALITY FILTER: it decides whether a single-candidate prefix span is
 *   suggested at all (a function-only span stays {@link Unresolved}). A HOLE
 *   in that class therefore no longer produces a wrong line: it produces a
 *   silly suggestion — visible and rejectable — because a curated lexical
 *   class cannot be proven complete, and every hole used to be a wrong line.</li>
 *   <li>Unmatched text is reported as raw phrases, never dropped silently.</li>
 * </ul>
 *
 * <p>Handling code must switch over {@link Outcome} exhaustively (no default
 * arm) so T7 cannot forget a variant — in particular, T7 cannot place a
 * {@link Suggested} line without writing an explicit arm for it.</p>
 */
public record NormalizationResult(List<Outcome> outcomes) {

	public NormalizationResult {
		if (outcomes == null) {
			outcomes = List.of();
		}
		outcomes = List.copyOf(outcomes);
	}

	/** An empty result: nothing matched and nothing to report. */
	public static NormalizationResult empty() {
		return new NormalizationResult(List.of());
	}

	public sealed interface Outcome permits Resolved, Suggested, Ambiguous, Unresolved {
	}

	/** A phrase matched exactly one catalog entry (an EXACT, complete name match). */
	public record Resolved(NormalizedLine line) implements Outcome {
	}

	/**
	 * A PREFIX match against exactly one catalog entry: a PROPOSAL that
	 * requires explicit operator acknowledgement before it may become an
	 * order line. Structurally distinct from {@link Resolved} — the sealed
	 * hierarchy and the exhaustive switch a caller must write make it
	 * impossible to mistake a suggestion for a resolution (a caller that
	 * wants to place an order MUST handle this variant explicitly). Carries
	 * the full candidate (id and name), the quantity and the raw phrase, so
	 * acceptance can place the line without re-interpreting anything. A hole
	 * in the function-word class surfaces here as a silly suggestion —
	 * visible and rejectable — never as a wrong resolved line.
	 */
	public record Suggested(NormalizedLine line) implements Outcome {
	}

	/**
	 * A phrase matched two or more catalog entries with equal specificity.
	 * Carries the phrase and quantity so the operator can ask the customer
	 * for precision, and the candidate names — NOT ids: ambiguity is resolved
	 * by the customer re-writing the order, never by the caller choosing.
	 */
	public record Ambiguous(String rawPhrase, int quantity, List<String> candidateNames) implements Outcome {
		public Ambiguous {
			if (candidateNames == null) {
				candidateNames = List.of();
			}
			candidateNames = List.copyOf(candidateNames);
		}
	}

	/** Text the matcher could not attribute to any catalog entry, kept verbatim. */
	public record Unresolved(String rawPhrase) implements Outcome {
	}

	/** All resolved lines, in message order. */
	public List<NormalizedLine> resolvedLines() {
		List<NormalizedLine> resolved = new ArrayList<>();
		for (Outcome outcome : outcomes) {
			if (outcome instanceof Resolved(NormalizedLine line)) {
				resolved.add(line);
			}
		}
		return resolved;
	}

	/** All suggested lines (prefix matches needing acknowledgement), in message order. */
	public List<Suggested> suggested() {
		List<Suggested> suggested = new ArrayList<>();
		for (Outcome outcome : outcomes) {
			if (outcome instanceof Suggested found) {
				suggested.add(found);
			}
		}
		return suggested;
	}

	/** All ambiguous phrases, in message order. */
	public List<Ambiguous> ambiguous() {
		List<Ambiguous> ambiguous = new ArrayList<>();
		for (Outcome outcome : outcomes) {
			if (outcome instanceof Ambiguous found) {
				ambiguous.add(found);
			}
		}
		return ambiguous;
	}

	/** All unresolved phrases, in message order. */
	public List<Unresolved> unresolved() {
		List<Unresolved> unresolved = new ArrayList<>();
		for (Outcome outcome : outcomes) {
			if (outcome instanceof Unresolved found) {
				unresolved.add(found);
			}
		}
		return unresolved;
	}
}
