import { cleanup } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { advanceOrder } from "../src/pages/kds/api";
import { KDS_TOAST_EVENT, type KdsToastDetail } from "../src/pages/kds/toast";

/**
 * advanceOrder error contract: the backend now answers 409 with a body that
 * names the rejected transition (fix fb07a32). The KDS must NOT swallow it:
 * the failure reaches the cook as a warning toast carrying the server
 * message, and the function reports failure so the caller re-syncs the
 * board. Success stays silent (the READY sound/toast belongs to the caller).
 */
function fireToasts(): KdsToastDetail[] {
  const seen: KdsToastDetail[] = [];
  const listener = (e: Event) => seen.push((e as CustomEvent<KdsToastDetail>).detail);
  window.addEventListener(KDS_TOAST_EVENT, listener);
  return seen;
}

describe("advanceOrder surfaces backend rejections", () => {
  afterEach(() => {
    cleanup();
    vi.unstubAllGlobals();
    localStorage.clear();
  });

  it("a 409 rejection fires a warning toast with the server message and returns false", async () => {
    const toasts = fireToasts();
    vi.stubGlobal(
      "fetch",
      vi.fn(async () =>
        new Response(
          JSON.stringify({
            status: 409,
            error: "Conflict",
            message: "Transición inválida: DELIVERED -> READY",
            path: "/api/orders/841/status",
          }),
          { status: 409 }
        )
      )
    );

    const ok = await advanceOrder(841, "READY");

    expect(ok).toBe(false);
    expect(toasts).toHaveLength(1);
    expect(toasts[0].type).toBe("warning");
    expect(toasts[0].message).toContain("Transición inválida: DELIVERED -> READY");
  });

  it("a response with no JSON body still toasts a fallback warning and returns false", async () => {
    const toasts = fireToasts();
    vi.stubGlobal("fetch", vi.fn(async () => new Response("", { status: 500 })));

    const ok = await advanceOrder(1, "PREPARING");

    expect(ok).toBe(false);
    expect(toasts).toHaveLength(1);
    expect(toasts[0].type).toBe("warning");
    expect(toasts[0].message.length).toBeGreaterThan(0);
  });

  it("success returns true and stays silent", async () => {
    const toasts = fireToasts();
    vi.stubGlobal("fetch", vi.fn(async () => new Response("", { status: 200 })));
    localStorage.setItem("token", "header.payload.sig");
    localStorage.setItem("restaurantSlug", "demo-e2e");

    const ok = await advanceOrder(1, "PREPARING");

    expect(ok).toBe(true);
    expect(toasts).toHaveLength(0);
  });
});
