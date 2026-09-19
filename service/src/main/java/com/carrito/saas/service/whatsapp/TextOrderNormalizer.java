package com.carrito.saas.service.whatsapp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.carrito.saas.dto.ComboDTO;
import com.carrito.saas.dto.MenuDTO;
import com.carrito.saas.dto.ProductDTO;

/**
 * Pure text-order normalizer (T6a of {@code odd/tasks/whatsapp-inbound.md}):
 * a customer's free-text message plus a {@link MenuDTO} catalog produce the
 * matched order lines and the phrases that could NOT be matched. No
 * persistence, no Spring, no database, no LLM/fuzzy/synonym matching — the
 * only text rule is {@link TextNormalization}, shared with the catalog side.
 *
 * <p>Matching contract, in order:</p>
 * <ol>
 *   <li>Catalog names and the message are tokenized by the same
 *   {@link TextNormalization#tokenize(String)} rule.</li>
 *   <li>A catalog entry matches when its FULL token sequence appears as a
 *   contiguous subsequence of the message tokens (no partial names).</li>
 *   <li>Longest match wins: {@code "milanesa napolitana"} beats a plain
 *   {@code "milanesa"} entry. That is specificity, not ambiguity.</li>
 *   <li>Ambiguity: one span matching two or more catalog entries (entries
 *   whose names normalize to the same tokens) is reported, never resolved by
 *   the matcher.</li>
 *   <li>Prefix pass, ONLY over the tokens the exact pass left unconsumed: a
 *   message span matches a catalog entry when the span's tokens are a
 *   contiguous PREFIX of that entry's name tokens (the catalog name itself
 *   need not appear complete: {@code "una coca"} against a catalog whose
 *   entry is {@code "Coca-Cola"}). A prefix match NEVER RESOLVES — whatever
 *   its length, whatever the function-word class says: exactly one candidate
 *   → {@link NormalizationResult.Suggested} (the operator must acknowledge it
 *   explicitly; the sealed outcome type makes mistaking it for a resolution
 *   impossible), two or more candidates → {@link NormalizationResult.Ambiguous}
 *   with their names, none → the tokens stay unresolved. The closed
 *   function-word class is a quality filter on the SUGGESTION only: a span
 *   made only of function words is never even suggested (it stays
 *   unresolved), because {@code "una"} must not traction {@code Una
 *   cerveza}; a hole in that class produces a silly, visible, rejectable
 *   suggestion — never a wrong line. At each start position the longest
 *   matching prefix span wins, and scanning is left-to-right —
 *   deterministic, no fuzzy matching, no synonyms, no edit distance. The
 *   prefix pass is not substring matching: a span must start at the name's
 *   FIRST token, so {@code "cola"} (a suffix of {@code [coca, cola]}) does
 *   not match.</li>
 *   <li>The ordering above is load-bearing: exact matches are consumed
 *   FIRST, so a full name is never re-judged at prefix priority. Otherwise
 *   {@code "pan"} would match {@code Pan} exactly AND {@code Pan de campo}
 *   by prefix and turn AMBIGUOUS — a clear case converted into a question.</li>
 *   <li>Consumed tokens are never reused: the same catalog entry purchased
 *   twice is two lines, never one doubled line.</li>
 * </ol>
 *
 * <p>Quantity: the single token immediately before a matched span, if it is a
 * digit run (1..999999999) or one of {@link TextNormalization#NUMBER_WORDS},
 * becomes the quantity and is consumed. Otherwise the quantity defaults to 1
 * and the token stays unconsumed (it reappears in the unresolved phrases).
 * One quantity token only: {@code "veinte y dos"} or {@code "media docena"}
 * are not quantities.</p>
 *
 * <p>Known limitations, all reported instead of guessed (full list in the T6a
 * section of the task document): out-of-order names do not match exactly (a
 * partial name now goes through the prefix pass, whose looser semantics and
 * its reported over-matches live in the T6 task document);
 * unit qualifiers ({@code "1 kilo de papas"} → {@code kilo de} unresolved
 * filler around a matched entry only if the entry's own name carries the
 * unit); non-immediate quantities ({@code "2 de milanesa"}) do not count;
 * number words beyond {@code doce} are not quantities.</p>
 */
public final class TextOrderNormalizer {

	/** Maximum accepted digit quantity; longer digit runs are treated as non-quantity noise. */
	private static final int MAX_DIGIT_QUANTITY = 999_999_999;

	private record CatalogEntry(Long productId, Long comboId, String name, List<String> tokens) {
	}

	public TextOrderNormalizer() {
	}

	/**
	 * Normalizes one message against one menu catalog. Deterministic and
	 * locale-independent (folding uses {@link java.util.Locale#ROOT}); the
	 * same inputs always produce the same result.
	 */
	public NormalizationResult normalize(String message, MenuDTO menu) {
		if (message == null || menu == null) {
			return NormalizationResult.empty();
		}
		Map<List<String>, List<CatalogEntry>> catalog = indexCatalog(menu);
		List<TextNormalization.Token> messageTokens = TextNormalization.tokenize(message);
		// Folded token TEXTS for catalog lookup (Token objects are for offsets).
		List<String> tokenTexts = messageTokens.stream().map(TextNormalization.Token::text).toList();
		boolean[] consumed = new boolean[messageTokens.size()];

		// Every span position where some catalog entry's full token sequence
		// appears contiguously.
		List<SpanMatch> spans = findSpanMatches(catalog, tokenTexts);

		// Longest span first, then leftmost; each token consumed at most once.
		spans.sort((a, b) -> {
			int byLength = Integer.compare(b.end - b.start, a.end - a.start);
			return byLength != 0 ? byLength : Integer.compare(a.start, b.start);
		});

		// Outcomes are collected with their message position and sorted at the
		// end: the greedy longest-first loop emits matches in acceptance order,
		// but callers need outcomes in MESSAGE order.
		List<Positioned> positioned = new ArrayList<>();
		for (SpanMatch span : spans) {
			if (isConsumed(consumed, span.start, span.end)) {
				continue;
			}
			int quantity = takeQuantity(consumed, messageTokens, span.start);
			markConsumed(consumed, span.start, span.end);
			String rawPhrase = message.substring(messageTokens.get(span.start).start(),
					messageTokens.get(span.end - 1).end());
			List<CatalogEntry> candidates = span.entries();
			if (candidates.size() == 1) {
				CatalogEntry entry = candidates.get(0);
				positioned.add(new Positioned(span.start, new NormalizationResult.Resolved(new NormalizedLine(
						entry.productId(), entry.comboId(), entry.name(), quantity, rawPhrase))));
			}
			else {
				positioned.add(new Positioned(span.start, new NormalizationResult.Ambiguous(rawPhrase, quantity,
						candidates.stream().map(CatalogEntry::name).toList())));
			}
		}
		// Pass 2 — prefix matching, over the tokens pass 1 left unconsumed.
		// Runs strictly AFTER the whole exact pass: the ordering is the rule
		// that keeps "pan" resolved against {Pan, Pan de campo}.
		matchByPrefix(catalog, message, messageTokens, tokenTexts, consumed, positioned);
		collectUnresolvedPhrases(message, messageTokens, consumed, positioned);
		positioned.sort((a, b) -> Integer.compare(a.start(), b.start()));
		return new NormalizationResult(positioned.stream().map(Positioned::outcome).toList());
	}

	private Map<List<String>, List<CatalogEntry>> indexCatalog(MenuDTO menu) {
		Map<List<String>, List<CatalogEntry>> catalog = new LinkedHashMap<>();
		List<ProductDTO> products = menu.getProducts() == null ? List.of() : menu.getProducts();
		for (ProductDTO product : products) {
			if (product.getId() == null || product.getName() == null || product.getName().isBlank()) {
				continue;
			}
			// Inactive products cannot be ordered; skip them instead of
			// matching a customer request that T7 would refuse.
			if (Boolean.FALSE.equals(product.getActive())) {
				continue;
			}
			addEntry(catalog, new CatalogEntry(product.getId(), null, product.getName(),
					TextNormalization.tokens(product.getName())));
		}
		List<ComboDTO> combos = menu.getCombos() == null ? List.of() : menu.getCombos();
		for (ComboDTO combo : combos) {
			if (combo.getId() == null || combo.getName() == null || combo.getName().isBlank()) {
				continue;
			}
			addEntry(catalog, new CatalogEntry(null, combo.getId(), combo.getName(),
					TextNormalization.tokens(combo.getName())));
		}
		return catalog;
	}

	private void addEntry(Map<List<String>, List<CatalogEntry>> catalog, CatalogEntry entry) {
		if (entry.tokens().isEmpty()) {
			return;
		}
		catalog.computeIfAbsent(entry.tokens(), key -> new ArrayList<>()).add(entry);
	}

	private List<SpanMatch> findSpanMatches(Map<List<String>, List<CatalogEntry>> catalog,
			List<String> tokenTexts) {
		List<SpanMatch> spans = new ArrayList<>();
		int maxNameLength = catalog.keySet().stream().mapToInt(List::size).max().orElse(0);
		for (int start = 0; start < tokenTexts.size(); start++) {
			for (int end = start + 1; end <= Math.min(start + maxNameLength, tokenTexts.size()); end++) {
				List<CatalogEntry> entries = catalog.get(tokenTexts.subList(start, end));
				if (entries != null) {
					spans.add(new SpanMatch(start, end, entries));
				}
			}
		}
		return spans;
	}

	private int takeQuantity(boolean[] consumed, List<TextNormalization.Token> messageTokens, int spanStart) {
		if (spanStart == 0 || consumed[spanStart - 1]) {
			return 1;
		}
		String previous = messageTokens.get(spanStart - 1).text();
		if (previous.chars().allMatch(Character::isDigit)) {
			try {
				int value = Integer.parseInt(previous);
				if (value >= 1 && value <= MAX_DIGIT_QUANTITY) {
					consumed[spanStart - 1] = true;
					return value;
				}
			}
			catch (NumberFormatException ignored) {
				// Digit run beyond int range: treat as noise, not a quantity.
			}
			return 1;
		}
		Integer word = TextNormalization.NUMBER_WORDS.get(previous);
		if (word != null) {
			consumed[spanStart - 1] = true;
			return word;
		}
		return 1;
	}

	private boolean isConsumed(boolean[] consumed, int start, int end) {
		for (int i = start; i < end; i++) {
			if (consumed[i]) {
				return true;
			}
		}
		return false;
	}

	private void markConsumed(boolean[] consumed, int start, int end) {
		for (int i = start; i < end; i++) {
			consumed[i] = true;
		}
	}

	/**
	 * Unconsumed message tokens grouped into consecutive runs, reported as
	 * phrases cut from the raw message. Nothing is dropped: every token the
	 * matcher did not attribute appears in some phrase.
	 */
	private void collectUnresolvedPhrases(String message, List<TextNormalization.Token> messageTokens,
			boolean[] consumed, List<Positioned> into) {
		int runStart = -1;
		for (int i = 0; i <= messageTokens.size(); i++) {
			boolean free = i < messageTokens.size() && !consumed[i];
			if (free && runStart < 0) {
				runStart = i;
			}
			else if (!free && runStart >= 0) {
				String phrase = message.substring(messageTokens.get(runStart).start(),
						messageTokens.get(i - 1).end());
				into.add(new Positioned(runStart, new NormalizationResult.Unresolved(phrase)));
				runStart = -1;
			}
		}
	}

	private record Positioned(int start, NormalizationResult.Outcome outcome) {
	}

	private record SpanMatch(int start, int end, List<CatalogEntry> entries) {
	}

	/**
	 * Pass 2 — prefix matching over the tokens the exact pass left unconsumed.
	 * A span matches when its tokens are a contiguous PREFIX of some catalog
	 * entry's name tokens (the name must START with the span, never merely
	 * contain it). Left-to-right over the remaining tokens; at each start
	 * position the LONGEST matching prefix wins. THE GUARANTEE: a prefix match
	 * never resolves, whatever its length — exactly one candidate →
	 * {@link NormalizationResult.Suggested} (the operator must acknowledge it
	 * explicitly; the sealed outcome type makes mistaking it for a resolution
	 * a compile-time impossibility in an exhaustive switch); two or more
	 * candidates → {@link NormalizationResult.Ambiguous} with their names,
	 * regardless of content (a question is not a proposal). The closed
	 * function-word class (T6 refinement, user decision 2026-09-19) is a
	 * QUALITY FILTER on the suggestion: a span made only of function words
	 * (articles, prepositions, contractions, quantity number words, numeral
	 * tokens) is never even suggested — it stays for the unresolved-phrase
	 * report — because {@code "una"} must not traction {@code Una cerveza}.
	 * The distinction is content, not span length ({@code "papas"} is one
	 * token and suggests; {@code "el combo"} starts with an article and
	 * suggests because it contains {@code combo}). A hole in the class
	 * produces a silly, visible, rejectable suggestion — never a wrong line.
	 * The exact pass is NOT gated and NOT demoted — a complete catalog name
	 * justifies itself and still resolves. Quantity works exactly as in the
	 * exact pass: the immediately preceding unconsumed number token is taken.
	 */
	private void matchByPrefix(Map<List<String>, List<CatalogEntry>> catalog, String message,
			List<TextNormalization.Token> messageTokens, List<String> tokenTexts, boolean[] consumed,
			List<Positioned> into) {
		int maxNameLength = catalog.keySet().stream().mapToInt(List::size).max().orElse(0);
		for (int start = 0; start < tokenTexts.size(); start++) {
			if (consumed[start]) {
				continue;
			}
			List<CatalogEntry> candidates = List.of();
			int spanEnd = start;
			for (int end = start + Math.min(maxNameLength, tokenTexts.size() - start); end > start; end--) {
				if (isConsumed(consumed, start, end)) {
					// A span crossing a consumed token is invalid; a shorter
					// span stopping before the consumed token may still match.
					continue;
				}
				List<CatalogEntry> matches = entriesWhoseNameStartsWith(catalog, tokenTexts.subList(start, end));
				if (!matches.isEmpty()) {
					candidates = matches;
					spanEnd = end;
					break; // longest prefix at this start wins
				}
			}
			if (candidates.isEmpty()) {
				continue;
			}
			// T6 refinement (user decision 2026-09-19) + guarantee move (T6,
			// user decision): the closed class is a QUALITY FILTER on the
			// suggestion. A span made only of function words is never even
			// SUGGESTED — {@code "una"} must not traction {@code Una cerveza}
			// — so it is skipped without consuming and falls to the
			// unresolved-phrase report. Ambiguity is unaffected (a question is
			// not a proposal). A hole in the class can now only produce a
			// silly, visible, rejectable Suggestion — never a wrong line.
			if (candidates.size() == 1 && !spanCarriesContent(tokenTexts, start, spanEnd)) {
				continue;
			}
			if (candidates.size() == 1 && !spanCarriesContent(tokenTexts, start, spanEnd)) {
				continue;
			}
			int quantity = takeQuantity(consumed, messageTokens, start);
			markConsumed(consumed, start, spanEnd);
			String rawPhrase = message.substring(messageTokens.get(start).start(),
					messageTokens.get(spanEnd - 1).end());
			if (candidates.size() == 1) {
				// A prefix match NEVER resolves, whatever its length: one
				// candidate is a Suggestion the operator must acknowledge
				// explicitly. The sealed outcome type makes mistaking it for a
				// resolution impossible in an exhaustive switch.
				CatalogEntry entry = candidates.get(0);
				into.add(new Positioned(start, new NormalizationResult.Suggested(new NormalizedLine(
						entry.productId(), entry.comboId(), entry.name(), quantity, rawPhrase))));
			}
			else {
				into.add(new Positioned(start, new NormalizationResult.Ambiguous(rawPhrase, quantity,
						candidates.stream().map(CatalogEntry::name).toList())));
			}
			start = spanEnd - 1; // the loop increment moves past the consumed span
		}
	}

	/** Entries whose normalized name tokens BEGIN with exactly {@code prefix} — set membership, nothing fuzzy. */
	private List<CatalogEntry> entriesWhoseNameStartsWith(Map<List<String>, List<CatalogEntry>> catalog,
			List<String> prefix) {
		List<CatalogEntry> matches = new ArrayList<>();
		for (List<CatalogEntry> entries : catalog.values()) {
			for (CatalogEntry entry : entries) {
				if (entry.tokens().size() >= prefix.size()
						&& entry.tokens().subList(0, prefix.size()).equals(prefix)) {
					matches.add(entry);
				}
			}
		}
		return matches;
	}

	/**
	 * True when the span {@code [start, end)} contains at least one token
	 * outside the closed function-word class
	 * ({@link TextNormalization#isFunctionWord}) — the quality filter that
	 * decides whether a single-candidate prefix span is suggested at all.
	 */
	private boolean spanCarriesContent(List<String> tokenTexts, int start, int end) {
		for (int i = start; i < end; i++) {
			if (!TextNormalization.isFunctionWord(tokenTexts.get(i))) {
				return true;
			}
		}
		return false;
	}
}
