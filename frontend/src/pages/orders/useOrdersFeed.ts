import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import {
  loadManagementOrders,
  type ManagementOrder,
} from "./api";
// Mismo audio del KDS (ding.mp3 nuevo pedido, ready.mp3 listo): la consola
// comparte los <audio> por id y el desbloqueo de autoplay en el gesto.
import { playNewOrderSound, playReadySound } from "../kds/audio";

/**
 * Live feed of the orders console (T3), following the same STOMP pattern as
 * the KDS (useKdsOrders): raw WebSocket to /ws/orders/websocket with a 5s
 * reconnect delay, subscribed to /topic/orders/{slug}.
 *
 * Difference against the KDS loop: the management view needs the RICH
 * OrderManagementDTO (total, payment, table, timeline, cancellation reason),
 * and the WS event body is the slim OrderDTO the kitchen consumes — so an
 * event never patches local state directly; it schedules a debounced re-fetch
 * of the full-day view. The same refresh is the fallback when the socket is
 * down (the header shows "Reconectando…" and the manual refresh button stays
 * available).
 */
export function useOrdersFeed(
  slug: string | null,
  enabled: boolean,
  onNewOrder?: (order: ManagementOrder) => void
): {
  orders: ManagementOrder[];
  lastUpdated: Date | null;
  live: boolean;
  loadFailed: boolean;
  refresh: () => Promise<void>;
} {
  const [orders, setOrders] = useState<ManagementOrder[]>([]);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [live, setLive] = useState(false);
  const [loadFailed, setLoadFailed] = useState(false);

  const refreshTimerRef = useRef<number | null>(null);

  // Snapshot anterior para detectar QUÉ cambió: el evento STOMP no trae el
  // detalle (dispara un re-fetch debounced), así que el diff contra la lista
  // previa es lo que decide qué sonido corresponde. null = primera carga:
  // no suena nada, o abrir la consola con pedidos del día sonaría en ráfaga.
  const previousOrdersRef = useRef<ManagementOrder[] | null>(null);

  // El callback vive en un ref: el efecto del WebSocket depende de
  // refresh/scheduleRefresh (que dependen de detectChanges), y un callback
  // recreado en cada render reconectaria el socket en cada render. El ref
  // mantiene detectChanges estable sin congelar la callback del caller.
  const onNewOrderRef = useRef(onNewOrder);
  useEffect(() => {
    onNewOrderRef.current = onNewOrder;
  }, [onNewOrder]);

  /** Igual que detectNewOrders del KDS, pero contra la lista del día: suena
   *  el ding por cada pedido que no existía y entra como NEW o como
   *  PENDING_PAYMENT (transferencia por confirmar: la consola es donde se
   *  atiende), y el ready cuando un pedido existente PASA a READY. Un
   *  pedido nuevo que ya nace READY o DELIVERED (creado por POS) no suena. */
  const detectChanges = useCallback((next: ManagementOrder[]): void => {
    const prev = previousOrdersRef.current;
    if (prev === null) return;
    for (const order of next) {
      const before = prev.find((p) => p.orderId === order.orderId);
      if (!before && (order.status === "NEW" || order.status === "PENDING_PAYMENT")) {
        playNewOrderSound();
        // Mismo disparador del ding: el panel imprime la comanda del pedido
        // recién entrado (useComandaPrint). Nada en la primera carga: prev
        // es null y el guard de arriba ya retornó.
        onNewOrderRef.current?.(order);
      } else if (before && before.status !== "READY" && order.status === "READY") {
        playReadySound();
      }
    }
  }, []);

  const refresh = useCallback(async () => {
    try {
      const next = await loadManagementOrders();
      detectChanges(next);
      previousOrdersRef.current = next;
      setOrders(next);
      setLastUpdated(new Date());
      setLoadFailed(false);
    } catch (e) {
      // Igual que el KDS: un fallo de red conserva los datos en pantalla.
      console.error("Error cargando pedidos", e);
      setLoadFailed(true);
    }
  }, [detectChanges]);

  // Debounce: una ráfaga de eventos (pedido creado + avance) dispara una
  // sola recarga. El timer se limpia al desmontar con el efecto principal.
  const scheduleRefresh = useCallback(() => {
    if (refreshTimerRef.current !== null) {
      window.clearTimeout(refreshTimerRef.current);
    }
    refreshTimerRef.current = window.setTimeout(() => {
      refreshTimerRef.current = null;
      void refresh();
    }, 250);
  }, [refresh]);

  useEffect(() => {
    if (!enabled || !slug) return;

    void refresh(); // carga inicial, equivalente al loadOrders() del KDS

    const client = new Client({
      webSocketFactory: () => {
        const proto = window.location.protocol === "https:" ? "wss" : "ws";
        return new WebSocket(`${proto}://${window.location.host}/ws/orders/websocket`);
      },
      reconnectDelay: 5000,
      onConnect: () => {
        setLive(true);
        // Al recuperar la conexión, re-sincronizar por si llegó algo caída.
        void refresh();
        client.subscribe(`/topic/orders/${slug}`, () => {
          scheduleRefresh();
        });
      },
      onWebSocketClose: () => {
        setLive(false);
        console.log("WebSocket desconectado. Reintentando en 5s");
      },
    });

    client.activate();

    return () => {
      if (refreshTimerRef.current !== null) {
        window.clearTimeout(refreshTimerRef.current);
        refreshTimerRef.current = null;
      }
      client.deactivate();
      setLive(false);
    };
  }, [enabled, slug, refresh, scheduleRefresh]);

  // Más nuevos primero: la consola revisa el día completo, no una cola de
  // cocina — el pedido recién entrado es el que hay que atender primero.
  const sortedOrders = useMemo(
    () =>
      [...orders].sort(
        (a, b) =>
          new Date(b.createdAt).getTime() - new Date(a.createdAt).getTime()
      ),
    [orders]
  );

  return { orders: sortedOrders, lastUpdated, live, loadFailed, refresh };
}
