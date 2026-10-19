/**
 * WhatsApp deep link for the customer phone (orders console).
 *
 * Rule, in order:
 *  1. keep digits only (spaces, dashes, parentheses and + are formatting);
 *  2. a number already carrying the country code ("54…") stays untouched;
 *  3. otherwise strip one leading trunk "0" ("011…" → "11…") and prepend "54".
 *
 * Returns null when there are no digits at all, so callers can fall back to
 * their previous rendering instead of linking to a broken chat.
 */
export function whatsappUrl(phone: string): string | null {
  const digits = phone.replace(/\D/g, "");
  if (!digits) return null;
  if (digits.startsWith("54")) return `https://wa.me/${digits}`;
  const withoutTrunkZero = digits.startsWith("0") ? digits.slice(1) : digits;
  return `https://wa.me/54${withoutTrunkZero}`;
}
