import { useCallback, useEffect, useRef, useState } from "react";
import type { ManagementOrder } from "./api";

/**
 * Print jobs of the orders panel (delivery-ticket-copy S2).
 *
 * A job is an order plus WHAT to print:
 *
 *   - kitchen:  the kitchen comanda only (copy flag OFF — original behavior).
 *   - double:   comanda + delivery copy, one window.print(), two pages
 *     (copy flag ON: the single Imprimir button and the auto-print output
 *     both tickets automatically — S2 of delivery-ticket-copy).
 *
 * `printOrder(order, kind)` enqueues a job; the head renders its ticket(s)
 * (print-only components) and, once painted, window.print() opens the
 * browser dialog aimed at the machine's printer (thermal 80mm layout comes
 * from Comanda.css @media print). window.print() blocks the event loop until
 * the dialog closes, so the queue drains one job per dialog — a burst of
 * arriving orders prints sequentially instead of racing several dialogs.
 *
 * The last printed job stays mounted (hidden on screen) so the ticket DOM
 * remains inspectable after the dialog closes.
 */
export type PrintJobKind = "kitchen" | "double";

export interface ComandaPrintJob {
  order: ManagementOrder;
  kind: PrintJobKind;
}

export function useComandaPrint(
  printFn: () => void = () => window.print()
): {
  printOrder: (order: ManagementOrder, kind?: PrintJobKind) => void;
  /** Job currently being printed, or the last one printed. */
  current: ComandaPrintJob | null;
} {
  const [queue, setQueue] = useState<ComandaPrintJob[]>([]);
  const [lastPrinted, setLastPrinted] = useState<ComandaPrintJob | null>(null);
  const printFnRef = useRef(printFn);
  useEffect(() => {
    printFnRef.current = printFn;
  }, [printFn]);

  const printOrder = useCallback((order: ManagementOrder, kind: PrintJobKind = "kitchen") => {
    setQueue((q) => {
      // No duplicate jobs for the same order+kind while still pending.
      if (q.some((j) => j.order.orderId === order.orderId && j.kind === kind)) return q;
      return [...q, { order, kind }];
    });
  }, []);

  const current = queue[0] ?? lastPrinted;

  useEffect(() => {
    const head = queue[0];
    if (head === undefined) return;

    // Let the ticket paint (and its fonts settle) before opening the dialog.
    const timer = window.setTimeout(() => {
      printFnRef.current();
      setLastPrinted(head);
      setQueue((q) => q.slice(1));
    }, 50);
    return () => window.clearTimeout(timer);
  }, [queue]);

  return { printOrder, current };
}
