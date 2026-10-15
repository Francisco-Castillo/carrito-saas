import { useEffect, useRef } from "react";

/**
 * Focus + keyboard behaviour shared by the detail sheet and the cart sheet:
 * moves focus into the sheet when it opens, closes it on Escape, and restores
 * focus to the trigger element when it closes.
 */
export function useModalSheet<E extends HTMLElement>(onClose: () => void) {
  const ref = useRef<E | null>(null);
  const onCloseRef = useRef(onClose);
  onCloseRef.current = onClose;

  useEffect(() => {
    const previous = document.activeElement instanceof HTMLElement ? document.activeElement : null;
    const node = ref.current;
    if (node) node.focus();

    function onKeyDown(event: KeyboardEvent) {
      if (event.key === "Escape") onCloseRef.current();
    }
    document.addEventListener("keydown", onKeyDown);

    return () => {
      document.removeEventListener("keydown", onKeyDown);
      if (previous && typeof previous.focus === "function") previous.focus();
    };
  }, []);

  return ref;
}
