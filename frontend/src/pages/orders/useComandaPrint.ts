import { useCallback, useEffect, useRef, useState } from "react";
import type { ManagementOrder } from "./api";

/**
 * Comanda print queue for the orders panel.
 *
 * `printOrder(order)` enqueues an order; the head of the queue is rendered
 * by <Comanda> (print-only ticket) and, once it has painted, window.print()
 * opens the browser print dialog aimed at the machine's printer (thermal
 * 80mm layout comes from Comanda.css @media print). window.print() blocks
 * the event loop until the dialog closes, so the queue drains one ticket
 * per dialog — a burst of arriving orders prints sequentially instead of
 * racing several dialogs.
 *
 * The last printed order stays mounted (hidden on screen) so the ticket
 * DOM remains inspectable after the dialog closes.
 */
export function useComandaPrint(
  printFn: () => void = () => window.print()
): {
  printOrder: (order: ManagementOrder) => void;
  /** Order currently being printed, or the last one printed. */
  current: ManagementOrder | null;
} {
  const [queue, setQueue] = useState<ManagementOrder[]>([]);
  const [lastPrinted, setLastPrinted] = useState<ManagementOrder | null>(null);
  const printFnRef = useRef(printFn);
  useEffect(() => {
    printFnRef.current = printFn;
  }, [printFn]);

  const printOrder = useCallback((order: ManagementOrder) => {
    setQueue((q) => {
      // No duplicate tickets for the same order while it is still pending.
      if (q.some((o) => o.orderId === order.orderId)) return q;
      return [...q, order];
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
