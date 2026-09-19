package com.carrito.saas.service.whatsapp;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The single text normalization rule for WhatsApp text-order matching (T6a of
 * {@code odd/tasks/whatsapp-inbound.md}).
 *
 * <p><strong>One rule, both sides.</strong> Every string that participates in
 * order matching — the customer's free-text message AND every catalog name —
 * passes through the same {@link #tokenize(String)} call. There is exactly one
 * place in the codebase where the matching alphabet is defined. This is the
 * same discipline {@link PhoneMatching} applies to phones: if the two sides of
 * a comparison normalize differently, they drift; here that drift is made
 * structurally impossible because the second side has no other function to
 * call.</p>
 *
 * <p>The rule, applied identically to every token of every string:</p>
 * <ol>
 *   <li>Case folding with {@link Locale#ROOT} (never the JVM default: a
 *   Turkish-default JVM would fold {@code MILANESA} to {@code mılanesa}).</li>
 *   <li>Diacritics stripped via NFD decomposition ({@code cafés} and
 *   {@code cafes} fold to the same token; note that Spanish {@code ñ} also
 *   folds to {@code n} — accepted, see the T6a report).</li>
 *   <li>Splits on every run of non letter-or-digit characters, so whitespace,
 *   commas, hyphens, line breaks and punctuation all become token
 *   boundaries — EXCEPT a decimal separator (dot or comma) BETWEEN numeric
 *   characters, which stays inside the number token: {@code "2.25"} and
 *   {@code "1,5"} are ONE numeral token, never the quantity {@code 25} or
 *   {@code 5} leaking out of a split (T6 verification FU-4; the comma fix is
 *   the same defect — the comma is the Spanish decimal separator, not merely
 *   a typo path). Because the exception lives here, it applies to both sides
 *   symmetrically. A separator not in numeric context is still an ordinary
 *   separator ({@code "a.b"} → {@code [a, b]}, {@code "2."} → {@code [2]},
 *   {@code "2 papas, 3 empanadas"} splits on the list comma). Numeric context
 *   on BOTH sides means multi-separator runs also stay one token
 *   ({@code "12.5.3"}, {@code "2..25"}), so no digit run can leak out of a
 *   split and become a phantom quantity.</li>
 *   <li>Spanish plural folding to a best-effort singular ({@code milanesas}
 *   → {@code milanesa}, {@code papas fritas} → {@code papa frita},
 *   {@code papeles} → {@code papel}, {@code panes} → {@code pan}). This
 *   folding is an extension of the base rule beyond what T6a's task text
 *   listed; it is deliberate — the task's own flagship message says
 *   {@code "2 milanesas con papas"} and without plural folding it matches
 *   nothing. Because it lives inside this single function it applies to both
 *   sides symmetrically and cannot drift. The {@code -es} drop only fires for
 *   consonant-stem plurals ({@code papel/papeles}, {@code pan/panes}); other
 *   {@code -es} words fall through to the {@code -s} drop ({@code cafes} →
 *   {@code cafe}, {@code leches} → {@code leche}). Number words and
 *   digit-only tokens are exempt from plural folding in BOTH branches
 *   ({@code dos} must not become {@code do}, and {@code tres} must not
 *   become {@code tr} — the exemption was originally applied only in the
 *   {@code -s} branch, so {@code tres} folded away and produced a wrong
 *   quantity from one of the design's own quantity words; fixed in the T6
 *   guarantee move, needing no lexicon because the exemption set already
 *   exists).</li>
 * </ol>
 *
 * <p>Plural folding is best-effort, not linguistic. It can collapse two
 * distinct words onto one token ({@code mes} → {@code me}), and it cannot
 * handle S-FINAL singulars (T6 verification FU-3, documented limit):
 * {@code gas} → {@code ga}, {@code mes} → {@code me}, {@code pais} →
 * {@code pai}, while their plurals fold to the bare stem ({@code gases} →
 * {@code gas}, {@code meses} → {@code mes}, {@code paises} → {@code pais})
 * — so a singular catalog name can MISS its plural message form ({@code Gas}
 * vs {@code "2 gases"}). No closed grammatical rule can separate s-final
 * singulars from plurals without a lexicon, and a lexicon is exactly the
 * open-ended word dump this rule forbids, so the folding stays and the limit
 * is documented. Symmetry contains the damage: because both sides fold
 * identically, identical spellings still match; a fold collision can only
 * merge catalog names onto shared tokens, which the matcher reports as
 * ambiguity instead of guessing. Even the number words are only protected
 * in their bare form: {@code treses} folds to {@code tres}, the quantity
 * word, so a nonsense token can supply quantity 3 when it precedes a
 * matched span — a quantity-only effect, never a wrong line.
 *
 * <p>Honest limit, stated where the guarantee now lives (T6, user decision
 * 2026-09-19): this rule CAN produce a wrong line when it is wrong — the
 * {@code tres} defect folded a quantity word into a non-word and produced
 * quantity 1 — so folding is NOT the guarantee. The guarantee is structural:
 * the normalizer's outcome types ({@code NormalizationResult}) make a prefix
 * match a {@code Suggested} outcome that the operator must acknowledge, so a
 * hole anywhere in this rule surfaces as a visible, rejectable proposal —
 * never as a silently resolved wrong line.</p>
 */
public final class TextNormalization {

	/** Spanish number words recognized as quantities (see {@code TextOrderNormalizer}). */
	public static final Map<String, Integer> NUMBER_WORDS = Map.ofEntries(
			Map.entry("un", 1), Map.entry("una", 1), Map.entry("uno", 1),
			Map.entry("dos", 2), Map.entry("tres", 3), Map.entry("cuatro", 4),
			Map.entry("cinco", 5), Map.entry("seis", 6), Map.entry("siete", 7),
			Map.entry("ocho", 8), Map.entry("nueve", 9), Map.entry("diez", 10),
			Map.entry("once", 11), Map.entry("doce", 12));

	/** Same words as keys, kept exempt from plural folding ({@code dos} ≠ {@code do}). */
	private static final Set<String> PLURAL_FOLD_EXEMPT = NUMBER_WORDS.keySet();

	/**
	 * The closed class of Spanish function words beyond the number words:
	 * articles and prepositions. The FULL class is
	 * {@code FUNCTION_WORDS ∪ NUMBER_WORDS.keySet()} — the number words are
	 * reused, never duplicated, exactly the one-place discipline this class
	 * exists for.
	 *
	 * <p>The FULL class is the union of three closed sub-classes, each one
	 * pinned by its own test (T6 verification FU-2):</p>
	 * <ol>
	 *   <li>{@link #FUNCTION_WORDS} — articles, the RAE prepositions and the
	 *   two mandatory RAE contractions ({@code al} = a+el, {@code del} =
	 *   de+el; fused preposition+article, so they inherit the article's
	 *   inability to name an item).</li>
	 *   <li>{@link #NUMBER_WORDS} — the quantity number words, reused, never
	 *   duplicated.</li>
	 *   <li>Numeral TOKENS — digit runs and digit runs with decimal separators
	 *   ({@code 2}, {@code 2.25}, {@code 1,5}): the orthographic form of a
	 *   numeral, the same grammatical role as the number words. A bare numeral
	 *   cannot name an item; a catalog name that STARTS with one
	 *   ("2 empanadas") still matches itself through the exact pass, which is
	 *   not gated.</li>
	 * </ol>
	 *
	 * <p>Members are stored in their FOLDED form because the class is a
	 * predicate over tokens produced by {@link #tokenize(String)}: the single
	 * rule folds first, so {@code los} plural-folds to {@code lo},
	 * {@code las} to {@code la}, {@code unos}/{@code unas} to
	 * {@code uno}/{@code una}, {@code tras} to {@code tra} and
	 * {@code según}/{@code vía} lose their accents. The indefinite articles
	 * un/una/uno are already quantity number words.</p>
	 *
	 * <p>What this list is NOT, by decision (T6, user decision 2026-09-19):
	 * not a synonym table (synonyms are unbounded and domain-specific and
	 * remain forbidden) and not a general stopword dump. It is a closed
	 * GRAMMATICAL class — finite and universal — consulted ONLY by the
	 * prefix pass of {@code TextOrderNormalizer} as a QUALITY FILTER: it
	 * decides whether a single-candidate prefix span is SUGGESTED at all.
	 * It is never applied to catalog names when indexing and never strips
	 * words from names: a complete catalog name always matches itself exactly.
	 * The class is deliberately NOT the guarantee against wrong lines (three
	 * consecutive reviews found holes in it: digits, contractions, then the
	 * bulk of conjunctions/pronouns/determiners): a curated lexical class
	 * cannot be proven complete. The guarantee is structural — a prefix match
	 * never resolves — so a hole here produces a silly, visible, rejectable
	 * Suggestion, never a wrong resolved line.</p>
	 */
	public static final Set<String> FUNCTION_WORDS = Set.of(
			// Definite articles (plus the neuter article); plural and
			// contracted-indefinite forms fold onto these three, and
			// un/una/uno come from NUMBER_WORDS.
			"el", "la", "lo",
			// The two MANDATORY RAE contractions: de+el -> del, a+el -> al.
			// They are a preposition fused with the definite article — a
			// closed, finite set by definition — and inherit the article's
			// inability to name an item (BLOCKING-2 fix).
			"al", "del",
			// The RAE closed preposition list, folded (segun, via; tras ->
			// tra). "versus" is excluded: a Latinism outside the Spanish
			// grammatical core. Archaic members (cabe, so) stay so the class
			// remains closed — pruning by expected usefulness is the road to
			// a stopword dump.
			"a", "ante", "bajo", "cabe", "con", "contra", "de", "desde",
			"durante", "en", "entre", "hacia", "hasta", "mediante", "para",
			"por", "segun", "sin", "so", "sobre", "tra", "via");

	/**
	 * True when {@code token} belongs to the closed class of content-less
	 * tokens: function words (articles, prepositions, contractions), quantity
	 * number words, or numeral tokens (digit and dotted digit runs). The
	 * single predicate over the single definition — there is no second copy
	 * anywhere.
	 */
	public static boolean isFunctionWord(String token) {
		return FUNCTION_WORDS.contains(token) || NUMBER_WORDS.containsKey(token) || isNumeralToken(token);
	}

	/** Digit runs and digit runs with decimal separators ({@code 2}, {@code 2.25}, {@code 1,5}) are numerals. */
	private static boolean isNumeralToken(String token) {
		boolean hasDigit = false;
		for (int i = 0; i < token.length(); i++) {
			char c = token.charAt(i);
			if (Character.isDigit(c)) {
				hasDigit = true;
			}
			else if (!isDecimalSeparator(c)) {
				return false;
			}
		}
		return hasDigit;
	}

	private TextNormalization() {
	}

	/**
	 * A raw (un-folded) message substring that was tokenized, with its offsets
	 * into the original string. Carrying offsets lets the matcher report the
	 * phrase exactly as the customer typed it without re-running any
	 * normalization on the report path.
	 */
	public record Token(String text, int start, int end) {
	}

	/**
	 * The single normalization rule, producing folded tokens with their
	 * offsets in the raw string. Used for BOTH the customer message and every
	 * catalog name.
	 */
	public static List<Token> tokenize(String raw) {
		if (raw == null) {
			return List.of();
		}
		String lowered = raw.toLowerCase(Locale.ROOT);
		List<Token> tokens = new ArrayList<>();
		int i = 0;
		while (i < lowered.length()) {
			if (!Character.isLetterOrDigit(lowered.charAt(i))) {
				i++;
				continue;
			}
			int start = i;
			while (i < lowered.length()) {
				char c = lowered.charAt(i);
				if (Character.isLetterOrDigit(c)) {
					i++;
					continue;
				}
				// A decimal separator BETWEEN numeric characters stays inside the
				// number token: "2.25" and "1,5" are one numeral token, never the
				// quantity 25 or 5 leaking out of a split (T6 verification FU-4;
				// comma fix = same defect, Spanish decimal separator). Requires
				// numeric context on BOTH sides, so "a.b" still splits, "2."
				// still ends at 2, and a list comma ("2 papas, 3 empanadas")
				// still splits — no digit run can leak out as a phantom quantity
				// ("12.5.3", "2..25").
				if (isDecimalSeparator(c) && i > start && i + 1 < lowered.length()
						&& isDecimalChar(lowered.charAt(i - 1))
						&& isDecimalChar(lowered.charAt(i + 1))) {
					i++;
					continue;
				}
				break;
			}
			// Folding is applied per RUN (never to the whole string), so
			// start/end always index the raw input exactly: NFD decomposition
			// changes a string's length, and folding whole strings first would
			// misalign every offset.
			String folded = stripDiacritics(lowered.substring(start, i));
			tokens.add(new Token(foldToken(folded), start, i));
		}
		return tokens;
	}

	/** Convenience view of {@link #tokenize(String)} without offsets. */
	public static List<String> tokens(String raw) {
		return tokenize(raw).stream().map(Token::text).toList();
	}

	private static String stripDiacritics(String input) {
		// NFD decomposition FIRST: precomposed characters (é) are not marks
		// themselves and would survive the filter without decomposition.
		String decomposed = Normalizer.normalize(input, Normalizer.Form.NFD);
		StringBuilder result = new StringBuilder(decomposed.length());
		decomposed.codePoints()
				.filter(cp -> Character.getType(cp) != Character.NON_SPACING_MARK
						&& Character.getType(cp) != Character.COMBINING_SPACING_MARK)
				.forEach(result::appendCodePoint);
		return result.toString();
	}

	/** Dot or comma: both are accepted decimal separators (comma = Spanish decimal separator). */
	private static boolean isDecimalSeparator(char c) {
		return c == '.' || c == ',';
	}

	/** A digit or a decimal separator: the context a decimal separator needs on BOTH sides. */
	private static boolean isDecimalChar(char c) {
		return Character.isDigit(c) || isDecimalSeparator(c);
	}

	private static final Set<Character> ES_DROP_STEMS = Set.of('l', 'd', 'r', 'n', 'j', 's');

	private static String foldToken(String token) {
		if (token.length() > 3 && token.endsWith("es")
				&& !PLURAL_FOLD_EXEMPT.contains(token)
				&& ES_DROP_STEMS.contains(token.charAt(token.length() - 3))) {
			return token.substring(0, token.length() - 2);
		}
		if (token.length() > 2 && token.endsWith("s") && !PLURAL_FOLD_EXEMPT.contains(token)
				&& token.chars().anyMatch(Character::isLetter)) {
			return token.substring(0, token.length() - 1);
		}
		return token;
	}
}
