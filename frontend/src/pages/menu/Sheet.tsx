import type { ReactNode } from "react";
import { useModalSheet } from "./useModalSheet";

interface SheetProps {
  /** Accessible name of the dialog. */
  label: string;
  closeLabel: string;
  onClose: () => void;
  /** Extra class on the sheet panel (e.g. the cart sheet width). */
  className?: string;
  children: ReactNode;
}

/**
 * Glass sheet shell: backdrop click and Escape close it, focus moves into the
 * sheet on open and back to the trigger on close. Bottom sheet on phones,
 * centred dialog from 768px up (CSS).
 */
export default function Sheet({ label, closeLabel, onClose, className, children }: SheetProps) {
  const sheetRef = useModalSheet<HTMLDivElement>(onClose);

  return (
    <div
      className="sheet-backdrop"
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
