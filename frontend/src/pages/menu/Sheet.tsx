import type { ReactNode } from "react";
import { useModalSheet } from "./useModalSheet";

interface SheetProps {
  /** Accessible name of the dialog. */
  label: string;
  closeLabel: string;
  onClose: () => void;
  /** Extra class on the sheet panel (e.g. the cart sheet width). */
  className?: string;
  /**
   * Right-side drawer from 1024px up instead of a centred dialog: the cart uses
   * it so opening the order never reflows the menu behind it.
   */
  drawer?: boolean;
  children: ReactNode;
}

/**
 * Glass sheet shell: backdrop click and Escape close it, focus moves into the
 * sheet on open and back to the trigger on close. Bottom sheet on phones,
 * centred dialog from 768px up, or a right drawer with `drawer` (CSS).
 */
export default function Sheet({ label, closeLabel, onClose, className, drawer, children }: SheetProps) {
  const sheetRef = useModalSheet<HTMLDivElement>(onClose);

  return (
    <div
      className={`sheet-backdrop${drawer ? " sheet-backdrop--drawer" : ""}`}
      onClick={(e) => {
        if (e.target === e.currentTarget) onClose();
      }}
    >
      <div
        ref={sheetRef}
        className={`sheet glass-panel${className ? ` ${className}` : ""}`}
        role="dialog"
        aria-modal="true"
        aria-label={label}
        tabIndex={-1}
      >
        <button
          type="button"
          className="sheet-close"
          onClick={onClose}
          aria-label={closeLabel}
        >
          ×
        </button>
        {children}
      </div>
    </div>
  );
}
