package com.carrito.saas.service.whatsapp;

import java.util.Arrays;
import java.util.List;

/**
 * THE single encode/decode point for the candidate NAMES stored on an
 * ambiguous {@link com.carrito.saas.repository.entity.OrderProposalItem}
 * (T7b.1 of {@code odd/tasks/whatsapp-inbound.md}, closing open gap 55).
 *
 * <p><strong>The stored format is documented HERE and nowhere else:</strong>
 * the candidate names joined by a single newline character ({@code \n}), in
 * the order the normalizer produced them, byte-identical to the names as the
 * catalog carries them — no trimming, no escaping, no reformatting. Names may
 * themselves contain spaces and accents; they may NOT contain a newline
 * (catalog names never do, and the normalizer's candidates come from catalog
 * names).</p>
 *
 * <p>This is the "one rule, one place" discipline this project has paid for
 * six times: before this class the writer joined by hand
 * ({@code PersistingInboundMessageHandler}) and every consumer would have had
 * to split on its own. Both directions of the format live here:</p>
 *
 * <ul>
 *   <li>{@link #join(List)} — the write side, used by the inbound handler
 *     when an ambiguous outcome is persisted;</li>
 *   <li>{@link #split(String)} — the read side, used by the DTO mapping and
 *     by the line-decision service when it validates a chosen name.</li>
 * </ul>
 *
 * <p>Blank handling is explicit, never accidental: {@code split(null)} and
 * {@code split("")} return an EMPTY list — never a list containing an empty
 * name, which would make "the empty name is a candidate" a state the service
 * would have to defend against.</p>
 */
public final class ProposalCandidates {

	/** THE separator of the stored format, documented once, here. */
	private static final String SEPARATOR = "\n";

	private ProposalCandidates() {
	}

	/**
	 * Write side: newline-joined candidate names, in order. {@code null} or
	 * empty input stores as {@code null} (no candidates is no stored text).
	 * Names are stored byte-identically.
	 */
	public static String join(List<String> names) {

		if (names == null || names.isEmpty()) {
			return null;
		}
		return String.join(SEPARATOR, names);
	}

	/**
	 * Read side: the stored text back to the name list, in order,
	 * byte-identically. {@code null} or blank stored text is "no candidates"
	 * and yields an EMPTY list — never a list holding an empty name.
	 */
	public static List<String> split(String stored) {

		if (stored == null || stored.isBlank()) {
			return List.of();
		}
		return Arrays.stream(stored.split(SEPARATOR, -1))
				.filter(name -> !name.isEmpty())
				.toList();
	}
}
