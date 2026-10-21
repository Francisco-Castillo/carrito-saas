import { useCallback, useEffect, useRef, useState } from "react";
import { Client } from "@stomp/stompjs";
import { api, type OrderStatusSummary } from "./api";
import { getSessionRestaurantSlug } from "./auth";
import { attachAudioUnlockListeners, playNewOrderSound } from "../kds/audio";

/**
 * Indicador en vivo del dashboard (S9): mismo patrón STOMP de la consola de
 * pedidos (useOrdersFeed) — raw WebSocket a /ws/orders/websocket con
 * reconexión a 5s, suscripto a /topic/orders/{slug}.
 *
 * El evento no trae detalle: ante cualquier cambio de pedidos (1) dispara el
 * refetch del dashboard vía onOrdersChanged y (2) re-lee el resumen de
 * estados para el badge. El ding suena SOLO cuando el contador de NEW sube —
 * comparando contra el snapshot previo, igual que hace la consola — así un
 * pedido que pasa a PREPARING/READY no hace ruido, y la primera carga
 * tampoco (snapshot null = sin sonido).
 */
const REFETCH_DEBOUNCE_MS = 800;

export function useLiveOrders(
  enabled: boolean,
  onOrdersChanged: () => void
): {
  /** Pedidos activos (NEW + PREPARING + READY) para el badge de la topbar. */
  activeCount: number;
  /** true = WebSocket conectado; false = mostramos "reconectando". */
  live: boolean;
} {
  const [activeCount, setActiveCount] = useState(0);
  const [live, setLive] = useState(false);

  const previousNewCountRef = useRef<number | null>(null);
  const refreshTimerRef = useRef<number | null>(null);

  const loadSummary = useCallback(async () => {
    try {
      const summary: OrderStatusSummary = await api.getOrderStatusSummary();
      const newCount = summary.NEW ?? 0;
      setActiveCount(newCount + (summary.PREPARING ?? 0) + (summary.READY ?? 0));

      if (
        previousNewCountRef.current !== null &&
        newCount > previousNewCountRef.current
      ) {
        playNewOrderSound();
      }
      previousNewCountRef.current = newCount;
    } catch (e) {
      // El badge es cosmético: si falla, no rompemos el dashboard ni logueamos
      // en ráfaga — el auto-refresh lo reintenta.
      console.error("Error cargando resumen de estados", e);
    }
  }, []);

  const scheduleRefresh = useCallback(() => {
    if (refreshTimerRef.current !== null) {
      window.clearTimeout(refreshTimerRef.current);
    }
    refreshTimerRef.current = window.setTimeout(() => {
      refreshTimerRef.current = null;
      void loadSummary();
      onOrdersChanged();
    }, REFETCH_DEBOUNCE_MS);
  }, [loadSummary, onOrdersChanged]);

  useEffect(() => {
    if (!enabled) return;

    // El navegador bloquea autoplay antes de un gesto del usuario: mismo
    // desbloqueo que el KDS, contra el mismo <audio id="newOrderSound">.
    attachAudioUnlockListeners();

    void loadSummary();

    const slug = getSessionRestaurantSlug();
    if (!slug) return;

    const client = new Client({
      webSocketFactory: () => {
        const proto = window.location.protocol === "https:" ? "wss" : "ws";
        return new WebSocket(`${proto}://${window.location.host}/ws/orders/websocket`);
      },
      reconnectDelay: 5000,
      onConnect: () => {
        setLive(true);
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
  }, [enabled, loadSummary, scheduleRefresh]);

  return { activeCount, live };
}
