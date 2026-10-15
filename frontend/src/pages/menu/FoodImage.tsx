/**
 * Food photo with an intentional fallback: when there is no `imageUrl`, render
 * a category-tinted gradient panel with a food glyph instead of an empty box
 * or a broken-image icon. The glyph is decorative; the product name stays
 * exposed to assistive tech via role="img" + aria-label.
 */
interface FoodImageProps {
  src?: string;
  alt: string;
  categoryName?: string;
  className?: string;
}

const GLYPH_RULES: Array<[RegExp, string]> = [
  [/postre|dulce|flan|brownie|torta|helad/i, "🍰"],
  [/bebida|gaseosa|agua|cerveza|vino|jugo/i, "🥤"],
  [/combo|promo/i, "🍱"],
  [/veggie|vegano|ensalad|salad/i, "🥗"],
  [/hamburg|burger|sándwich|sandwich|wrap/i, "🍔"],
  [/pizz|empanad|tarta/i, "🍕"],
  [/pasta|fideos|ñoquis/i, "🍝"],
  [/caf|capuchino|latte/i, "☕"],
];

/** Food glyph for the placeholder, matched from product/category wording. */
export function glyphFor(name: string, category: string): string {
  const text = `${name} ${category}`;
  for (const [pattern, glyph] of GLYPH_RULES) {
    if (pattern.test(text)) return glyph;
  }
  return "🍽️";
}

const TINTS = ["indigo", "violet", "fuchsia", "cyan"] as const;

/**
 * Deterministic tint per category (stable hash of the normalized name) so the
 * placeholder panel is "category-tinted" without needing per-category config.
 */
export function tintFor(category: string): string {
  const norm = category
    .normalize("NFD")
    .replace(/\p{Diacritic}/gu, "")
    .toLowerCase();
  let hash = 0;
  for (let i = 0; i < norm.length; i++) {
    hash = (hash * 31 + norm.charCodeAt(i)) >>> 0;
  }
  return TINTS[hash % TINTS.length];
}

export default function FoodImage({ src, alt, categoryName = "", className }: FoodImageProps) {
  if (src) {
    return <img src={src} alt={alt} loading="lazy" decoding="async" className={className} />;
  }
  return (
    <span
      className={`food-placeholder${className ? ` ${className}` : ""}`}
      data-tint={tintFor(categoryName)}
      role="img"
      aria-label={alt}
    >
      <span aria-hidden="true">{glyphFor(alt, categoryName)}</span>
    </span>
  );
}
