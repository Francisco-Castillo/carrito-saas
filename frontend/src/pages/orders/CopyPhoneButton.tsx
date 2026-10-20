import { useEffect, useRef, useState } from "react";
import { whatsappInternational } from "./wa";

interface CopyPhoneButtonProps {
  phone: string;
}

/**
 * Copy-to-clipboard for the customer's WhatsApp number, shown next to the
 * phone link in the orders console. Copies the SAME international digits the
 * wa.me link dials, so the number pastes straight into any chat app.
 *
 * The panel is served over plain HTTP on the LAN (not a secure context):
 * navigator.clipboard does not exist there, so the button falls back to the
 * legacy document.execCommand("copy") route. Confirmation is visual and
 * momentary — the button flips to a check for a couple of seconds.
 */
export default function CopyPhoneButton({ phone }: CopyPhoneButtonProps) {
  const [copied, setCopied] = useState(false);
  const resetTimer = useRef<number | undefined>(undefined);

  useEffect(() => () => window.clearTimeout(resetTimer.current), []);

  if (!whatsappInternational(phone)) return null;

  async function copy() {
    const digits = whatsappInternational(phone);
    if (!digits) return;

    // Secure contexts (HTTPS, o el dev server local): la API async de clipboard.
    if (navigator.clipboard) {
      try {
        await navigator.clipboard.writeText(digits);
        markCopied();
        return;
      } catch {
        // Permission denied or API flaked: try the legacy route below.
      }
    }

    // Legacy route (HTTP LAN): a temporary selection + execCommand.
    const helper = document.createElement("textarea");
    helper.value = digits;
    helper.setAttribute("readonly", "");
    helper.style.position = "fixed";
    helper.style.opacity = "0";
    document.body.appendChild(helper);
    helper.select();
    document.execCommand("copy");
    document.body.removeChild(helper);
    markCopied();
  }

  function markCopied() {
    window.clearTimeout(resetTimer.current);
    setCopied(true);
    resetTimer.current = window.setTimeout(() => setCopied(false), 2000);
  }

  return (
    <button
      type="button"
      className="copy-phone-btn"
      onClick={copy}
      aria-label={copied ? "Número copiado" : "Copiar el número de WhatsApp"}
      title={copied ? "¡Copiado!" : "Copiar el número de WhatsApp"}
    >
      {copied ? "✓" : "⧉"}
    </button>
  );
}
