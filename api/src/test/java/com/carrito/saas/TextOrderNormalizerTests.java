package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Locale;

import org.junit.jupiter.api.Test;

import com.carrito.saas.dto.ComboDTO;
import com.carrito.saas.dto.MenuDTO;
import com.carrito.saas.dto.ProductDTO;
import com.carrito.saas.service.whatsapp.NormalizationResult;
import com.carrito.saas.service.whatsapp.NormalizedLine;
import com.carrito.saas.service.whatsapp.TextOrderNormalizer;
import com.carrito.saas.service.whatsapp.TextNormalization;

/**
 * Contract of {@link TextOrderNormalizer} — the free-text to order-lines
 * normalizer as a PURE function (T6a of {@code odd/tasks/whatsapp-inbound.md}):
 * a customer message plus a {@link MenuDTO} catalog produce a
 * {@link NormalizationResult}. No persistence, no Spring, no database, no
 * fuzzy matching, no synonyms.
 *
 * <p>Expectations in the table are built from the SAME records the
 * implementation must produce ({@link NormalizationResult.Outcome} values),
 * so every case asserts outcome order, quantities, raw phrases (cut verbatim
 * from the message), candidate names and the product-XOR-combo id shape
 * structurally.</p>
 */
class TextOrderNormalizerTests {

	private static final long PRODUCT_MILANESA_NAPOLITANA = 501L;
	private static final long PRODUCT_PAPAS_FRITAS = 502L;
	private static final long PRODUCT_COCA_500 = 503L;
	private static final long PRODUCT_COCA_2250 = 504L;
	private static final long PRODUCT_EMPANADAS_CARNE = 505L;
	private static final long PRODUCT_CAFE_CON_LECHE = 506L;
	private static final long PRODUCT_MILANESA = 507L;
	private static final long PRODUCT_TORTA_INACTIVE = 509L;
	private static final long COMBO_FAMILIAR = 901L;
	private static final long PRODUCT_UNA_CERVEZA = 512L;
	private static final long PRODUCT_UNA_GASEOSA = 513L;
	private static final long COMBO_EL_COMBO_FAMILIAR = 902L;
	private static final long PRODUCT_EL_TORO = 514L;
	private static final long PRODUCT_CON_LIMON = 515L;
	private static final long PRODUCT_DEL_DIA = 516L;
	private static final long PRODUCT_AL_PLATO = 517L;
	private static final long PRODUCT_DOS_EMPANADAS = 518L;
	private static final long PRODUCT_GASEOSA = 519L;

	private final TextOrderNormalizer normalizer = new TextOrderNormalizer();

	// ------------------------------------------------------------------
	// Catalog under test.
	// ------------------------------------------------------------------

	/**
	 * Products 503 and 504 are the document's two Coca-Cola sizes: the
	 * catalog distinguishes them only by data (not by name), so both names
	 * normalize to the same tokens and any phrase matching that name is
	 * AMBIGUOUS, never resolved by the matcher. Product 509 is explicitly
	 * inactive and must never match.
	 */
	private MenuDTO menu() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(
				product(PRODUCT_MILANESA_NAPOLITANA, "Milanesa napolitana"),
				product(PRODUCT_PAPAS_FRITAS, "Papas fritas"),
				product(PRODUCT_COCA_500, "Coca-Cola"),
				product(PRODUCT_COCA_2250, "Coca Cola"),
				product(PRODUCT_EMPANADAS_CARNE, "Empanadas de carne"),
				product(PRODUCT_CAFE_CON_LECHE, "Café con leche"),
				product(PRODUCT_MILANESA, "Milanesa"),
				inactiveProduct(PRODUCT_TORTA_INACTIVE, "Torta")));
		menu.setCombos(List.of(combo(COMBO_FAMILIAR, "Combo familiar")));
		return menu;
	}

	// ------------------------------------------------------------------
	// Prefix pass (T6 adjustment, user decision 2026-09-19).
	// ------------------------------------------------------------------

	private static final long PRODUCT_PAN = 510L;
	private static final long PRODUCT_PAN_DE_CAMPO = 511L;

	/**
	 * AC7 VERBATIM: "hola quiero 2 milanesas con papas y una coca" against a
	 * catalog with two Coca-Cola sizes. The catalog name ({@code Coca-Cola} →
	 * tokens {@code [coca, cola]}) never appears contiguously in the message —
	 * only its PREFIX does. The three lines demonstrate the whole outcome
	 * model: milanesa is an EXACT match of the complete catalog name →
	 * {@code Resolved} (automatic, the common case); papas is a prefix span
	 * with ONE candidate → {@code Suggested} (a prefix match never resolves,
	 * whatever its length: the operator must acknowledge it explicitly); coca
	 * is a prefix span with TWO candidates → {@code Ambiguous}. A hole in the
	 * function-word class can no longer produce a wrong Resolved line — at
	 * worst a silly, visible, rejectable Suggestion.
	 */
	@Test
	void ac7VerbatimMilanesaExactResolvedPapasPrefixSuggestedCocaAmbiguous() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(
				product(PRODUCT_MILANESA, "Milanesa"),
				product(PRODUCT_PAPAS_FRITAS, "Papas fritas"),
				product(PRODUCT_COCA_500, "Coca-Cola"),
				product(PRODUCT_COCA_2250, "Coca Cola")));
		NormalizationResult result = normalizer.normalize(
				"hola quiero 2 milanesas con papas y una coca", menu);
		assertThat(result.outcomes()).containsExactly(
				unresolved("hola quiero"),
				resolvedProduct(PRODUCT_MILANESA, "Milanesa", 2, "milanesas"),
				unresolved("con"),
				suggestedProduct(PRODUCT_PAPAS_FRITAS, "Papas fritas", 1, "papas"),
				unresolved("y"),
				ambiguous("coca", 1, List.of("Coca-Cola", "Coca Cola")));
	}

	/** "una coca" alone: the prefix span matches both sizes → AMBIGUOUS, never resolved. */
	@Test
	void unaCocaAloneIsAmbiguousWithBothSizes() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(
				product(PRODUCT_COCA_500, "Coca-Cola"),
				product(PRODUCT_COCA_2250, "Coca Cola")));
		NormalizationResult result = normalizer.normalize("una coca", menu);
		assertThat(result.outcomes()).containsExactly(
				ambiguous("coca", 1, List.of("Coca-Cola", "Coca Cola")));
	}

	/**
	 * THE regression test that justifies the two-pass ordering: exact matches
	 * are consumed FIRST, so the prefix pass never sees the {@code pan} span.
	 * With a single combined pass, {@code "pan"} would match {@code Pan}
	 * exactly AND {@code Pan de campo} by prefix at the same priority and turn
	 * AMBIGUOUS — a clear case converted into a question by a tie-break rule
	 * put in the wrong place. This test fails under that mutation.
	 */
	@Test
	void exactPriorityResolvesPanInsteadOfAmbiguatingItAgainstPanDeCampo() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(
				product(PRODUCT_PAN, "Pan"),
				product(PRODUCT_PAN_DE_CAMPO, "Pan de campo")));
		assertThat(normalizer.normalize("pan", menu).outcomes()).containsExactly(
				resolvedProduct(PRODUCT_PAN, "Pan", 1, "pan"));
		assertThat(normalizer.normalize("2 pan", menu).outcomes()).containsExactly(
				resolvedProduct(PRODUCT_PAN, "Pan", 2, "pan"));
	}

	/**
	 * A prefix span matching EXACTLY ONE catalog entry is SUGGESTED — never
	 * Resolved, whatever its length: the operator must acknowledge it
	 * explicitly. The outcome carries the full candidate (id, name, quantity,
	 * raw phrase) so acceptance can place the line without re-interpreting
	 * anything, and {@code resolvedLines()} does NOT contain it: a caller
	 * cannot mistake a suggestion for a resolution.
	 */
	@Test
	void prefixSpanMatchingExactlyOneItemIsSuggestedAndNeverResolved() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(product(PRODUCT_MILANESA_NAPOLITANA, "Milanesa napolitana")));
		NormalizationResult result = normalizer.normalize("una milanesa", menu);
		assertThat(result.outcomes()).containsExactly(
				suggestedProduct(PRODUCT_MILANESA_NAPOLITANA, "Milanesa napolitana", 1, "milanesa"));
		assertThat(result.resolvedLines()).isEmpty();
		assertThat(result.suggested()).hasSize(1);
		NormalizationResult.Suggested suggested = result.suggested().get(0);
		assertThat(suggested.line().productId()).isEqualTo(PRODUCT_MILANESA_NAPOLITANA);
		assertThat(suggested.line().name()).isEqualTo("Milanesa napolitana");
		assertThat(suggested.line().quantity()).isEqualTo(1);
		assertThat(suggested.line().rawPhrase()).isEqualTo("milanesa");
	}

	/**
	 * The boundary of the prefix rule: a span must start at the name's FIRST
	 * token. {@code "cola"} is a suffix of {@code [coca, cola]}, not a prefix,
	 * so it stays UNRESOLVED — the prefix pass is not substring matching.
	 */
	@Test
	void prefixDoesNotMatchMidName() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(product(PRODUCT_COCA_500, "Coca-Cola")));
		NormalizationResult result = normalizer.normalize("quiero cola", menu);
		assertThat(result.outcomes()).containsExactly(unresolved("quiero cola"));
	}

	/**
	 * Prefix content rule (T6 refinement, user decision 2026-09-19): a prefix
	 * span may be RESOLVED only if it CONTAINS at least one token outside the
	 * closed class of function words (articles, prepositions and the existing
	 * quantity number words). A function-only span may be AMBIGUOUS but must
	 * never resolve. The distinction is CONTENT, not span length: {@code papa}
	 * identifies an item, {@code una} does not — a blanket single-token rule
	 * was measured and rejected because it broke {@code "papas"} →
	 * {@code Papas fritas}. The exact pass is untouched by this rule.
	 */

	/** The defect this rule fixes: a contentless span is never even SUGGESTED. */
	@Test
	void functionOnlySpanNeverResolvesEvenWithASingleCandidate() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(product(PRODUCT_UNA_CERVEZA, "Una cerveza")));
		NormalizationResult result = normalizer.normalize("una", menu);
		assertThat(result.outcomes()).containsExactly(unresolved("una"));
	}

	/** Ambiguity stays a first-class outcome for function-only spans: it is safe, resolution is not. */
	@Test
	void functionOnlySpanWithTwoOrMoreCandidatesIsStillAmbiguous() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(
				product(PRODUCT_UNA_CERVEZA, "Una cerveza"),
				product(PRODUCT_UNA_GASEOSA, "Una gaseosa")));
		NormalizationResult result = normalizer.normalize("una", menu);
		assertThat(result.outcomes()).containsExactly(
				ambiguous("una", 1, List.of("Una cerveza", "Una gaseosa")));
	}

	/**
	 * The case the rejected single-token rule destroyed: a single CONTENT
	 * token matching by prefix is exactly how people order — and under the
	 * guarantee move it is a SUGGESTION for the operator, not an automatic
	 * resolution.
	 */
	@Test
	void singleContentTokenPrefixIsSuggested() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(product(PRODUCT_PAPAS_FRITAS, "Papas fritas")));
		NormalizationResult result = normalizer.normalize("papas", menu);
		assertThat(result.outcomes()).containsExactly(
				suggestedProduct(PRODUCT_PAPAS_FRITAS, "Papas fritas", 1, "papas"));
	}

	/**
	 * The corrected article case (user correction 2026-09-19): the matcher
	 * longest-matches, so the span here is {@code [el, combo]} — a MIXED span
	 * that contains the content token {@code combo} — and it is SUGGESTED
	 * (never silently resolved). This pins the "contains content" semantics
	 * against the rejected stronger rule "a span starting with a function word
	 * never counts", which would turn a perfectly clear order into an
	 * unresolved phrase.
	 */
	@Test
	void mixedSpanStartingWithAFunctionWordButContainingContentIsSuggested() {
		MenuDTO menu = new MenuDTO();
		menu.setCombos(List.of(combo(COMBO_EL_COMBO_FAMILIAR, "El combo familiar")));
		NormalizationResult result = normalizer.normalize("quiero el combo", menu);
		assertThat(result.outcomes()).containsExactly(
				unresolved("quiero"),
				suggestedCombo(COMBO_EL_COMBO_FAMILIAR, "El combo familiar", 1, "el combo"));
	}

	/**
	 * The article case the number-word-only class would have missed: span
	 * {@code [el]} is function-only (articles are in the class, {@code el} is
	 * not a numeral), so it must NOT resolve — before this rule it tractioned
	 * {@code "El combo familiar"} out of the bare article.
	 */
	@Test
	void articleOnlySpanDoesNotResolveEvenThoughTheNameStartsWithIt() {
		MenuDTO menu = new MenuDTO();
		menu.setCombos(List.of(combo(COMBO_EL_COMBO_FAMILIAR, "El combo familiar")));
		NormalizationResult result = normalizer.normalize("quiero el", menu);
		assertThat(result.outcomes()).containsExactly(unresolved("quiero el"));
	}

	/**
	 * STRUCTURAL guarantee: {@code Suggested} is a distinct sealed variant, so
	 * a caller CANNOT mistake it for {@code Resolved}. The switch EXPRESSION
	 * below has no default arm — it compiles only while every Outcome variant
	 * is handled, so removing {@code Suggested} from the hierarchy, or adding
	 * a new variant, breaks THIS test's compilation instead of silently
	 * misplacing an unacknowledged line into an order. The caller that wants
	 * to place an order must handle {@code Suggested} explicitly; this one
	 * counts only what may be placed automatically.
	 */
	@Test
	void suggestedIsStructurallyDistinctFromResolvedAndMustBeHandledExplicitly() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(product(PRODUCT_PAPAS_FRITAS, "Papas fritas")));
		NormalizationResult result = normalizer.normalize("papas", menu);
		int automaticallyPlaceable = 0;
		for (NormalizationResult.Outcome outcome : result.outcomes()) {
			automaticallyPlaceable += switch (outcome) {
				case NormalizationResult.Resolved r -> 1;
				case NormalizationResult.Suggested s -> 0; // needs explicit acknowledgement
				case NormalizationResult.Ambiguous a -> 0;
				case NormalizationResult.Unresolved u -> 0;
			};
		}
		assertThat(automaticallyPlaceable).isZero();
		assertThat(result.resolvedLines()).isEmpty();
		assertThat(result.suggested()).hasSize(1);
	}

	/**
	 * The exact pass is untouched by the content rule: a complete catalog
	 * name justifies itself. {@code "milanesa"} matches {@code Milanesa}
	 * EXACTLY in pass 1 (two {@code "Milanesa*"} entries exist, so if the
	 * exact pass failed to consume it, the prefix pass would see two
	 * candidates and this case would come back ambiguous instead).
	 */
	@Test
	void exactSingleTokenMatchResolvesInPassOne() {
		NormalizationResult result = normalizer.normalize("milanesa", menu());
		assertThat(result.outcomes()).containsExactly(
				resolvedProduct(PRODUCT_MILANESA, "Milanesa", 1, "milanesa"));
	}

	// ------------------------------------------------------------------
	// Closed-class sub-classes (T6 verification FU-2) and numerals
	// (BLOCKING-1/BLOCKING-2 fixes). One pin per sub-class: removing that
	// sub-class from the closed class must kill exactly these pins. Since
	// the guarantee moved into the structure, the class is a QUALITY FILTER
	// (it decides whether a single-candidate prefix span is suggested at
	// all), but it is still pinned in the same per-sub-class way: a hole
	// here now produces a silly Suggestion instead of a wrong line, and
	// these pins keep each sub-class load-bearing for that filter.
	// ------------------------------------------------------------------

	/** Article-only span: removing the articles from the class kills this pin. */
	@Test
	void articleOnlySpanIsGatedFromResolving() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(product(PRODUCT_EL_TORO, "El toro")));
		NormalizationResult result = normalizer.normalize("el", menu);
		assertThat(result.outcomes()).containsExactly(unresolved("el"));
	}

	/** Preposition-only span: removing the prepositions from the class kills this pin. */
	@Test
	void prepositionOnlySpanIsGatedFromResolving() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(product(PRODUCT_CON_LIMON, "Con limon")));
		NormalizationResult result = normalizer.normalize("con", menu);
		assertThat(result.outcomes()).containsExactly(unresolved("con"));
	}

	/**
	 * Contraction-only spans (BLOCKING-2): {@code del} = de+el and {@code al} =
	 * a+el are the RAE's two mandatory contractions — preposition+article
	 * fused, so they inherit the article's inability to name an item.
	 * Removing the contractions from the class kills this pin.
	 */
	@Test
	void contractionOnlySpansAreGatedFromResolving() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(
				product(PRODUCT_DEL_DIA, "Del dia"),
				product(PRODUCT_AL_PLATO, "Al plato")));
		assertThat(normalizer.normalize("del", menu).outcomes()).containsExactly(unresolved("del"));
		assertThat(normalizer.normalize("al", menu).outcomes()).containsExactly(unresolved("al"));
	}

	/** Numeral-WORD-only span: removing NUMBER_WORDS from the class kills this pin. */
	@Test
	void numeralWordOnlySpanIsGatedFromResolving() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(product(PRODUCT_UNA_CERVEZA, "Una cerveza")));
		NormalizationResult result = normalizer.normalize("una", menu);
		assertThat(result.outcomes()).containsExactly(unresolved("una"));
	}

	/**
	 * Numeral-DIGIT-only span (BLOCKING-1, part 1): a bare digit run is the
	 * orthographic form of a numeral — the same grammatical role as the number
	 * words — so as a span it is as contentless as {@code "una"} and must be
	 * gated. Removing the digit numerals from the class kills this pin.
	 */
	@Test
	void numeralDigitOnlySpanIsGatedFromResolving() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(product(PRODUCT_DOS_EMPANADAS, "2 empanadas")));
		NormalizationResult result = normalizer.normalize("2", menu);
		assertThat(result.outcomes()).containsExactly(unresolved("2"));
	}

	/**
	 * BLOCKING-1, part 2 — the flagship defect: with {@code "2 empanadas"} in
	 * the catalog, {@code "quiero 2 coca"} used to resolve {@code "2"} into the
	 * UNRELATED {@code "2 empanadas"} line (a wrong line) AND steal the
	 * quantity (coca came out qty 1). The digit span must be gated; the
	 * quantity must then attach to the coca span, and the human still sees the
	 * ambiguity question. This is AC7's catalog plus a digit-named entry.
	 */
	@Test
	void digitSpanNeverResolvesIntoAnUnrelatedItemAndNeverStealsTheQuantity() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(
				product(PRODUCT_DOS_EMPANADAS, "2 empanadas"),
				product(PRODUCT_COCA_500, "Coca-Cola")));
		NormalizationResult result = normalizer.normalize("quiero 2 coca", menu);
		assertThat(result.outcomes()).containsExactly(
				unresolved("quiero"),
				suggestedProduct(PRODUCT_COCA_500, "Coca-Cola", 2, "coca"));
	}

	/**
	 * The exact pass is NOT gated by the content rule (a complete catalog name
	 * justifies itself), and that includes names that START with a digit:
	 * {@code "2 empanadas"} must keep matching itself in pass 1. The quantity
	 * is 1 because the leading numeral is part of the consumed name — there is
	 * no separate quantity token before the span.
	 */
	@Test
	void exactPassStillMatchesANameStartingWithADigit() {
		MenuDTO menu = new MenuDTO();
		menu.setProducts(List.of(product(PRODUCT_DOS_EMPANADAS, "2 empanadas")));
		NormalizationResult result = normalizer.normalize("2 empanadas", menu);
		assertThat(result.outcomes()).containsExactly(
				resolvedProduct(PRODUCT_DOS_EMPANADAS, "2 empanadas", 1, "2 empanadas"));
	}

	// ------------------------------------------------------------------
	// Dotted numbers (T6 verification FU-4): a decimal/dotted number is ONE
	// token and is NOT a quantity.
	// ------------------------------------------------------------------

	/**
	 * {@code "2.25"} must tokenize as ONE token (not {@code [2, 25]}), and a
	 * dotted number is never a quantity: {@code "2.25 coca"} must NOT report
	 * qty 25 with a phantom {@code "2"}-token; the dotted token stays
	 * unconsumed and is reported verbatim, the line defaults to qty 1.
	 */
	@Test
	void dottedNumbersStayOneTokenInTheSharedRule() {
		assertThat(TextNormalization.tokens("2.25")).isEqualTo(List.of("2.25"));
		assertThat(TextNormalization.tokens("1.500")).isEqualTo(List.of("1.500"));
		// A dot NOT between digits is still an ordinary separator.
		assertThat(TextNormalization.tokens("a.b")).isEqualTo(List.of("a", "b"));
		assertThat(TextNormalization.tokens("2.")).isEqualTo(List.of("2"));
	}

	/** The matcher-side consequence: dotted numbers are noise, not quantities. */
	@Test
	void dottedNumberIsNeverAQuantityAndIsReportedVerbatim() {
		// menu() has the two Coca sizes, so "coca" is AMBIGUOUS — but the
		// pinned point is the quantity: it is 1, NOT 25, and "2.25" comes back
		// verbatim as unresolved instead of a phantom "2" token.
		NormalizationResult decimal = normalizer.normalize("2.25 coca", menu());
		assertThat(decimal.outcomes()).containsExactly(
				unresolved("2.25"),
				ambiguous("coca", 1, List.of("Coca-Cola", "Coca Cola")));
		NormalizationResult thousands = normalizer.normalize("1.500 papas fritas", menu());
		assertThat(thousands.outcomes()).containsExactly(
				unresolved("1.500"),
				resolvedProduct(PRODUCT_PAPAS_FRITAS, "Papas fritas", 1, "papas fritas"));
	}

	// ------------------------------------------------------------------
	// Quantity words (T6 landing, defect 5): EVERY word in NUMBER_WORDS must
	// survive plural folding so it can be taken as a quantity. `tres` ends in
	// -es and used to fold to `tr` in the -es branch (the exemption existed
	// only in the -s branch), producing qty 1 — a wrong quantity from one of
	// the design's own quantity words.
	// ------------------------------------------------------------------

	@Test
	void everyNumberWordIsAQuantityIncludingTres() {
		MenuDTO milanesa = new MenuDTO();
		milanesa.setProducts(List.of(product(PRODUCT_MILANESA, "Milanesa")));
		NormalizationResult tres = normalizer.normalize("tres milanesas", milanesa);
		assertThat(tres.outcomes()).containsExactly(
				resolvedProduct(PRODUCT_MILANESA, "Milanesa", 3, "milanesas"));
		NormalizationResult seis = normalizer.normalize("seis milanesas", milanesa);
		assertThat(seis.outcomes()).containsExactly(
				resolvedProduct(PRODUCT_MILANESA, "Milanesa", 6, "milanesas"));
		NormalizationResult doce = normalizer.normalize("doce milanesas", milanesa);
		assertThat(doce.outcomes()).containsExactly(
				resolvedProduct(PRODUCT_MILANESA, "Milanesa", 12, "milanesas"));
	}

	// ------------------------------------------------------------------
	// Comma decimals (T6 landing, defect 6): the comma is the Spanish
	// decimal separator, so a comma between digits stays inside the number
	// token — the same defect already fixed for dots. Multi-separator runs
	// ("12.5.3", "2..25") stay one token too, so no digit run leaks out of
	// a split and becomes a phantom quantity.
	// ------------------------------------------------------------------

	@Test
	void commaAndMultiSeparatorNumbersStayOneTokenInTheSharedRule() {
		assertThat(TextNormalization.tokens("1,5")).isEqualTo(List.of("1,5"));
		assertThat(TextNormalization.tokens("2,25")).isEqualTo(List.of("2,25"));
		assertThat(TextNormalization.tokens("12.5.3")).isEqualTo(List.of("12.5.3"));
		assertThat(TextNormalization.tokens("2..25")).isEqualTo(List.of("2..25"));
		// A comma NOT between numeric characters is still an ordinary separator.
		// (Tokens come back FOLDED: the single rule folds plurals too.)
		assertThat(TextNormalization.tokens("2 papas, 3 empanadas"))
				.isEqualTo(List.of("2", "papa", "3", "empanada"));
		assertThat(TextNormalization.tokens("a.b")).isEqualTo(List.of("a", "b"));
	}

	/** The matcher-side consequence: comma decimals are noise, not quantities. */
	@Test
	void commaDecimalIsNeverAQuantityAndIsReportedVerbatim() {
		MenuDTO gaseosa = new MenuDTO();
		gaseosa.setProducts(List.of(product(PRODUCT_GASEOSA, "Gaseosa")));
		NormalizationResult decimal = normalizer.normalize("quiero 1,5 gaseosa", gaseosa);
		assertThat(decimal.outcomes()).containsExactly(
				unresolved("quiero 1,5"),
				resolvedProduct(PRODUCT_GASEOSA, "Gaseosa", 1, "gaseosa"));
		// menu() has the two Coca sizes, so "coca" is AMBIGUOUS — the pinned
		// point is the quantity: 1, NOT 25, and "2,25" comes back verbatim.
		NormalizationResult coca = normalizer.normalize("2,25 coca", menu());
		assertThat(coca.outcomes()).containsExactly(
				unresolved("2,25"),
				ambiguous("coca", 1, List.of("Coca-Cola", "Coca Cola")));
	}

	/** Residuals of the dot rule, pinned: no digit run may leak out of a split as a phantom quantity. */
	@Test
	void multiSeparatorNumbersNeverLeakAQuantity() {
		NormalizationResult triple = normalizer.normalize("12.5.3 coca", menu());
		assertThat(triple.outcomes()).containsExactly(
				unresolved("12.5.3"),
				ambiguous("coca", 1, List.of("Coca-Cola", "Coca Cola")));
		NormalizationResult doubled = normalizer.normalize("2..25 coca", menu());
		assertThat(doubled.outcomes()).containsExactly(
				unresolved("2..25"),
				ambiguous("coca", 1, List.of("Coca-Cola", "Coca Cola")));
	}

	private ProductDTO product(Long id, String name) {
		ProductDTO product = new ProductDTO();
		product.setId(id);
		product.setName(name);
		product.setActive(Boolean.TRUE);
		return product;
	}

	private ProductDTO inactiveProduct(Long id, String name) {
		ProductDTO product = new ProductDTO();
		product.setId(id);
		product.setName(name);
		product.setActive(Boolean.FALSE);
		return product;
	}

	private ComboDTO combo(Long id, String name) {
		ComboDTO combo = new ComboDTO();
		combo.setId(id);
		combo.setName(name);
		return combo;
	}

	// ------------------------------------------------------------------
	// Line/phrase expectation builders.
	// ------------------------------------------------------------------

	private static NormalizationResult.Outcome resolved(long id, boolean combo, String name, int quantity,
			String rawPhrase) {
		return new NormalizationResult.Resolved(
				combo ? new NormalizedLine(null, id, name, quantity, rawPhrase)
						: new NormalizedLine(id, null, name, quantity, rawPhrase));
	}

	private static NormalizationResult.Outcome resolvedProduct(long id, String name, int quantity, String rawPhrase) {
		return resolved(id, false, name, quantity, rawPhrase);
	}

	private static NormalizationResult.Outcome resolvedCombo(long id, String name, int quantity, String rawPhrase) {
		return resolved(id, true, name, quantity, rawPhrase);
	}

	private static NormalizationResult.Outcome ambiguous(String rawPhrase, int quantity, List<String> candidates) {
		return new NormalizationResult.Ambiguous(rawPhrase, quantity, candidates);
	}

	private static NormalizationResult.Outcome suggested(long id, boolean combo, String name, int quantity,
			String rawPhrase) {
		return new NormalizationResult.Suggested(
				combo ? new NormalizedLine(null, id, name, quantity, rawPhrase)
						: new NormalizedLine(id, null, name, quantity, rawPhrase));
	}

	private static NormalizationResult.Outcome suggestedProduct(long id, String name, int quantity, String rawPhrase) {
		return suggested(id, false, name, quantity, rawPhrase);
	}

	private static NormalizationResult.Outcome suggestedCombo(long id, String name, int quantity, String rawPhrase) {
		return suggested(id, true, name, quantity, rawPhrase);
	}

	private static NormalizationResult.Outcome unresolved(String rawPhrase) {
		return new NormalizationResult.Unresolved(rawPhrase);
	}

	private record TableCase(String name, String message, List<NormalizationResult.Outcome> expected) {
	}

	/**
	 * The table. Case order follows the T6a report: quantities first, then
	 * separators, case/accents, ambiguity, unresolved text, mixed messages.
	 */
	private List<TableCase> table() {
		return List.of(
				new TableCase("digitsQuantity",
						"2 milanesas napolitanas",
						List.of(resolvedProduct(PRODUCT_MILANESA_NAPOLITANA, "Milanesa napolitana", 2,
								"milanesas napolitanas"))),
				new TableCase("wordQuantityUna",
						"una milanesa",
						List.of(resolvedProduct(PRODUCT_MILANESA, "Milanesa", 1, "milanesa"))),
				new TableCase("wordQuantityDoce",
						"doce empanadas de carne",
						List.of(resolvedProduct(PRODUCT_EMPANADAS_CARNE, "Empanadas de carne", 12,
								"empanadas de carne"))),
				new TableCase("unrecognizedQuantityDefaultsToOneAndIsReported",
						"unas cuantas empanadas de carne",
						List.of(
								unresolved("unas cuantas"),
								resolvedProduct(PRODUCT_EMPANADAS_CARNE, "Empanadas de carne", 1, "empanadas de carne"))),
				new TableCase("numberWordBeyondDoceIsNotAQuantity",
						"trece empanadas de carne",
						List.of(
								unresolved("trece"),
								resolvedProduct(PRODUCT_EMPANADAS_CARNE, "Empanadas de carne", 1, "empanadas de carne"))),
				new TableCase("quantityMustBeImmediatelyBeforeThePhrase",
						"la milanesa",
						List.of(
								unresolved("la"),
								resolvedProduct(PRODUCT_MILANESA, "Milanesa", 1, "milanesa"))),
				new TableCase("commaSeparator",
						"2 papas fritas, 3 empanadas de carne",
						List.of(
								resolvedProduct(PRODUCT_PAPAS_FRITAS, "Papas fritas", 2, "papas fritas"),
								resolvedProduct(PRODUCT_EMPANADAS_CARNE, "Empanadas de carne", 3,
										"empanadas de carne"))),
				new TableCase("ySeparatorIsConsumedAroundMatches",
						"1 papas fritas y 3 empanadas de carne",
						List.of(
								resolvedProduct(PRODUCT_PAPAS_FRITAS, "Papas fritas", 1, "papas fritas"),
								unresolved("y"),
								resolvedProduct(PRODUCT_EMPANADAS_CARNE, "Empanadas de carne", 3,
										"empanadas de carne"))),
				new TableCase("lineBreakSeparator",
						"1 papas fritas\n3 empanadas de carne",
						List.of(
								resolvedProduct(PRODUCT_PAPAS_FRITAS, "Papas fritas", 1, "papas fritas"),
								resolvedProduct(PRODUCT_EMPANADAS_CARNE, "Empanadas de carne", 3,
										"empanadas de carne"))),
				new TableCase("mixedCaseIsFoldedByTheSingleRule",
						"UNA MiLaNeSa NaPoLiTaNa",
						List.of(resolvedProduct(PRODUCT_MILANESA_NAPOLITANA, "Milanesa napolitana", 1,
								"MiLaNeSa NaPoLiTaNa"))),
				new TableCase("accentsAreFoldedByTheSingleRule",
						"quiero 2 cafés con leche",
						List.of(
								unresolved("quiero"),
								resolvedProduct(PRODUCT_CAFE_CON_LECHE, "Café con leche", 2, "cafés con leche"))),
				new TableCase("documentCocaAmbiguityIsReportedNeverResolved",
						"una coca cola",
						List.of(ambiguous("coca cola", 1, List.of("Coca-Cola", "Coca Cola")))),
				new TableCase("longestMatchWins",
						"1 milanesa napolitana y 2 milanesas",
						List.of(
								resolvedProduct(PRODUCT_MILANESA_NAPOLITANA, "Milanesa napolitana", 1,
										"milanesa napolitana"),
								unresolved("y"),
								resolvedProduct(PRODUCT_MILANESA, "Milanesa", 2, "milanesas"))),
				new TableCase("consumedTokensAreNeverReused",
						"milanesa milanesa",
						List.of(
								resolvedProduct(PRODUCT_MILANESA, "Milanesa", 1, "milanesa"),
								resolvedProduct(PRODUCT_MILANESA, "Milanesa", 1, "milanesa"))),
				new TableCase("comboResolvesToComboId",
						"2 combo familiar",
						List.of(resolvedCombo(COMBO_FAMILIAR, "Combo familiar", 2, "combo familiar"))),
				new TableCase("inactiveProductNeverMatches",
						"una torta",
						List.of(unresolved("una torta"))),
				new TableCase("outOfOrderNameDoesNotMatch",
						"napolitana milanesa",
						List.of(
								unresolved("napolitana"),
								// The reversed phrase must NOT match 'Milanesa napolitana'
								// (contiguity is order-sensitive); only the plain
								// product's own full name matches.
								resolvedProduct(PRODUCT_MILANESA, "Milanesa", 1, "milanesa"))),
				new TableCase("fullyUnresolvedMessage",
						"hola, queria hacer un pedido",
						// Unresolved phrases are cut VERBATIM from the raw message,
						// punctuation included.
						List.of(unresolved("hola, queria hacer un pedido"))),
				new TableCase("mixedResolvedAmbiguousUnresolved",
						"hola! quiero 2 milanesas napolitanas, una coca cola y 3 empanadas de carne por favor",
						List.of(
								unresolved("hola! quiero"),
								resolvedProduct(PRODUCT_MILANESA_NAPOLITANA, "Milanesa napolitana", 2,
										"milanesas napolitanas"),
								ambiguous("coca cola", 1, List.of("Coca-Cola", "Coca Cola")),
								unresolved("y"),
								resolvedProduct(PRODUCT_EMPANADAS_CARNE, "Empanadas de carne", 3,
										"empanadas de carne"),
								unresolved("por favor"))));
	}

	@Test
	void tableCases() {
		for (TableCase tableCase : table()) {
			NormalizationResult result = normalizer.normalize(tableCase.message(), menu());
			assertThat(result.outcomes())
					.as("case %s: message '%s'", tableCase.name(), tableCase.message())
					.isEqualTo(tableCase.expected());
		}
	}

	// ------------------------------------------------------------------
	// The single-normalization-rule property.
	// ------------------------------------------------------------------

	/**
	 * PROPERTY, not a golden string: the SAME catalog item spelled in several
	 * ways on the catalog side must match the SAME message spelled in several
	 * ways on the message side, producing the identical line every time.
	 *
	 * <p>This is the proof that there is one normalization rule used by BOTH
	 * sides: any pair of spellings only matches identically if case folding,
	 * diacritic stripping and plural folding run on the catalog name exactly
	 * as they run on the message. If either side had its own variant (the
	 * recurring "same rule in two places" defect), some combination below
	 * would fail — with the JVM default locale set to Turkish even case
	 * folding through the wrong locale is caught.</p>
	 */
	@Test
	void sameCatalogItemMatchesIdenticallyAcrossSpellingsOnBothSides() {
		List<String> catalogSpellings = List.of("Café con leche", "CAFE CON LECHE", "café con leche");
		List<String> messageSpellings = List.of(
				"un cafe con leche",
				"Un Café con Leche",
				"un CAFÉ CON LECHE",
				"un cafes con leches");
		for (String catalogSpelling : catalogSpellings) {
			MenuDTO menu = new MenuDTO();
			menu.setProducts(List.of(product(PRODUCT_CAFE_CON_LECHE, catalogSpelling)));
			for (String messageSpelling : messageSpellings) {
				NormalizationResult result = normalizer.normalize(messageSpelling, menu);
				List<NormalizedLine> lines = result.resolvedLines();
				assertThat(lines)
						.as("catalog '%s' + message '%s' must match one identical line", catalogSpelling, messageSpelling)
						.hasSize(1);
				assertThat(lines.get(0).productId()).isEqualTo(PRODUCT_CAFE_CON_LECHE);
				assertThat(lines.get(0).comboId()).isNull();
				assertThat(lines.get(0).name()).isEqualTo(catalogSpelling);
				assertThat(lines.get(0).quantity()).isEqualTo(1);
			}
		}
	}

	@Test
	void normalizationIsDeterministic() {
		String message = "hola! quiero 2 milanesas napolitanas, una coca cola y 3 empanadas de carne por favor";
		assertThat(normalizer.normalize(message, menu()))
				.isEqualTo(normalizer.normalize(message, menu()));
	}

	/**
	 * Case folding must use a FIXED locale ({@link Locale#ROOT}), never the
	 * JVM default: under a Turkish default the wrong call folds
	 * {@code MILANESA} to {@code mılanesa} and the sides would disagree.
	 */
	@Test
	void foldingIsIndependentOfJvmDefaultLocale() {
		Locale original = Locale.getDefault();
		try {
			Locale.setDefault(new Locale("tr", "TR"));
			NormalizationResult result = normalizer.normalize("UNA MILANESA", menu());
			assertThat(result.resolvedLines()).hasSize(1);
			assertThat(result.resolvedLines().get(0).productId()).isEqualTo(PRODUCT_MILANESA);
			assertThat(result.resolvedLines().get(0).quantity()).isEqualTo(1);
		}
		finally {
			Locale.setDefault(original);
		}
	}

	// ------------------------------------------------------------------
	// The single normalization function itself.
	// ------------------------------------------------------------------

	@Test
	void normalizationTokensAreTheSharedRule() {
		// The message side and the catalog side must fold to the SAME tokens
		// for names that differ only in spelling — asserted here directly so
		// a future second variant of the rule fails here first.
		assertThat(TextNormalization.tokens("Coca-Cola")).isEqualTo(TextNormalization.tokens("coca cola"));
		assertThat(TextNormalization.tokens("CAFÉS")).isEqualTo(TextNormalization.tokens("cafes"));
		assertThat(TextNormalization.tokens("milanesas")).isEqualTo(TextNormalization.tokens("milanesa"));
		assertThat(TextNormalization.tokens("dos")).isEqualTo(List.of("dos"));
	}
}
