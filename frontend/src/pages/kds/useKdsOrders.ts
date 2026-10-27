import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import {
  advanceOrder,
  loadOrders as apiLoadOrders,
  normalizeKdsOrder,
  RestaurantNotFoundError,
  UnauthenticatedError,
  type KdsOrder,
} from "./api";
import { playNewOrderSound, playReadySound } from "./audio";
import { showToast } from "./toast";

export type KdsFatalError = "restaurant-not-found" | "unauthenticated";

/**
 * Puerto del bucle principal del legacy kds/kds.js:
 * - carga inicial de pedidos (kds.js:309-311: loadOrders() al arrancar),
 * - conexión WebSocket STOMP a /ws/orders suscrita a /topic/orders/{slug}
 *   (kds.js:663-690) con reintento cada 5 segundos,
 * - detección de pedidos nuevos → sonido (kds.js:81-89 y 692-708).
 *
 * El legacy tenía un setInterval(loadOrders, 4000) COMENTADO
 * (kds.js:308): la actualización llega por WebSocket, así que aquí también.
 * En React el estado es una lista de pedidos que se reemplaza/elimina por
 * orderId; el orden se calcula al renderizar (kds.js:91-93: sort asc por
 * createdAt).
 */
export function useKdsOrders(
  slug: string | null,
  enabled: boolean
): {
  orders: KdsOrder[];
  sortedOrders: KdsOrder[];
  lastUpdated: Date | null;
  live: boolean;
  fatal: KdsFatalError | null;
  refresh: () => void;
  advance: (orderId: number, status: string) => void;
} {
  const [orders, setOrders] = useState<KdsOrder[]>([]);
  const [lastUpdated, setLastUpdated] = useState<Date | null>(null);
  const [live, setLive] = useState(false);
  const [fatal, setFatal] = useState<KdsFatalError | null>(null);

  // kds.js:10 — previousOrders: referencia previa para detectNewOrders.
  const previousOrdersRef = useRef<KdsOrder[]>([]);
  // Espejo del estado para chequeos de existencia fuera del updater
  // (los efectos secundarios nunca van dentro de setState: StrictMode
  // ejecuta los updaters dos veces en dev).
  const ordersRef = useRef<KdsOrder[]>([]);

  useEffect(() => {
    ordersRef.current = orders;
  }, [orders]);

  // kds.js:81-89 — detectNewOrders(): suena por cada orderId que no existed.
  const detectNewOrders = useCallback((next: KdsOrder[]): void => {
    next.forEach((order) => {
      const exists = previousOrdersRef.current.find(
        (p) => p.orderId === order.orderId
      );
      if (!exists) {
        playNewOrderSound();
        showToast(`Nuevo pedido #${order.orderNumber}`, "info");
      }
    });
  }, []);

  // kds.js:54-78 — loadOrders()
  const refresh = useCallback(() => {
    apiLoadOrders()
      .then((next) => {
        detectNewOrders(next);
        previousOrdersRef.current = next; // kds.js:78
        setOrders(next);
        setLastUpdated(new Date());
        setFatal(null);
      })
      .catch((e) => {
        if (e instanceof RestaurantNotFoundError) {
          // kds.js:55-57: "Restaurante no encontrado"
          setFatal("restaurant-not-found");
          return;
        }
        if (e instanceof UnauthenticatedError) {
          // Sesión muerta en pleno turno (S4): fatal propio, la página
          // limpia la sesión y vuelve al login. Nunca datos vacíos en
          // silencio (odd/tasks/kds-session-errors.md S1).
          setFatal("unauthenticated");
          return;
        }
        // kds.js:76-77: el error de red solo se loguea; la UI conserva datos.
      });
  }, [detectNewOrders]);

  // kds.js:692-728 — handleOrderEvent(): pedido nuevo o actualizado.
  const handleOrderEvent = useCallback((raw: KdsOrder): void => {
    // El evento del WebSocket viaja en OrderDTO: las opciones del ítem son
    // el snapshot de objetos, no strings de cocina. Normalizar ANTES de
    // tocar el estado (renderizar el objeto rompe el tablero).
    const order = normalizeKdsOrder(raw);
    const exists = ordersRef.current.some((p) => p.orderId === order.orderId);

    // PEDIDO NUEVO (kds.js:698-711): solo suena si entra como NEW.
    if (!exists && order.status === "NEW") {
      playNewOrderSound();
      showToast(`Nuevo pedido #${order.orderNumber}`, "info");
    }

    setOrders((prev) =>
      exists
        ? prev.map((p) => (p.orderId === order.orderId ? order : p)) // kds.js:714-721
        : [...prev, order]
    );
    setLastUpdated(new Date());
  }, []);

  /**
   * advanceOrder para la UI: PATCH al backend (kds.js:264-277) + actualización
   * optimista del estado local (el legacy movía la tarjeta en el DOM en
   * dragEnd; con el botón esperaba el eco del WebSocket).
   */
  const advance = useCallback(
    (orderId: number, status: string): void => {
      if (status === "DELIVERED" || status === "CANCELLED") {
        // kds.js:744-753 — moveCardToColumn(): entregado/cancelado elimina.
        setOrders((prev) => prev.filter((p) => p.orderId !== orderId));
      } else {
        setOrders((prev) =>
          prev.map((p) => (p.orderId === orderId ? { ...p, status } : p))
        );
      }
      advanceOrder(orderId, status).then((ok) => {
        if (ok && status === "READY") {
          playReadySound(); // kds.js:270-271
          showToast("Pedido listo", "success");
        }
        if (!ok) refresh(); // sin eco garantizado, re-sincroniza con el backend
      }).catch((e) => {
        if (e instanceof UnauthenticatedError) {
          // Sesión muerta al avanzar una card (S4): mismo fatal que la carga.
          setFatal("unauthenticated");
          return;
        }
        // Otros rechazos ya mostraron su toast dentro de advanceOrder.
        console.error("Error actualizando estado", e);
      });
    },
    [refresh]
  );

  useEffect(() => {
    if (!enabled || !slug) return;

    refresh(); // kds.js:311

    // kds.js:663-690 — connectWebSocket(): SockJS('/ws/orders') + Stomp.over.
    // El endpoint SockJS del backend también expone el transporte WebSocket
    // crudo en /ws/orders/websocket, que usamos con @stomp/stompjs (los CDNs
    // sockjs-client/stompjs del legacy son clients que hoy no aportan fallbacks
    // relevantes). El reintento de 5s del legacy queda como reconnectDelay.
    const client = new Client({
      webSocketFactory: () => {
        const proto = window.location.protocol === "https:" ? "wss" : "ws";
        return new WebSocket(`${proto}://${window.location.host}/ws/orders/websocket`);
      },
      reconnectDelay: 5000, // kds.js:687
      onConnect: () => {
        console.log("KDS conectado"); // kds.js:671
        setLive(true);
        // kds.js:672-676 — subscribe /topic/orders/{slug}
        client.subscribe(`/topic/orders/${slug}`, (message) => {
          const order = JSON.parse(message.body) as KdsOrder;
          handleOrderEvent(order); // kds.js:677-679
        });
      },
      onWebSocketClose: () => {
        setLive(false);
        // kds.js:684-686
        console.log("WebSocket desconectado. Reintentando en 5s");
      },
    });

    client.activate();

    return () => {
      client.deactivate();
      setLive(false);
    };
  }, [enabled, slug, refresh, handleOrderEvent]);

  // kds.js:91-93 — syncOrders(): sort asc por createdAt antes de renderizar.
  const sortedOrders = useMemo(
    () =>
      [...orders].sort(
        (a, b) => new Date(a.createdAt).getTime() - new Date(b.createdAt).getTime()
      ),
    [orders]
  );

  return { orders, sortedOrders, lastUpdated, live, fatal, refresh, advance };
}
