import { cleanup, render } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import OrderCard from "../src/pages/kds/OrderCard";
import type { KdsOrder } from "../src/pages/kds/api";

/**
 * Urgency contract of the redesigned card: the card root carries an
 * urgency-{green|orange|red} class derived from the elapsed minutes
 * (<=5 green, >5 orange, >10 red) — the same thresholds as the legacy
 * timer classes. The top accent strip (progress bar) and the timer are
 * styled from that class, so the class IS the observable contract.
 */
function buildOrder(minutesAgo: number): KdsOrder {
  return {
    orderId: 1,
    orderNumber: 14,
    customerName: "Mesa 3",
    customerPhone: null,
    orderType: "RETIRO",
    status: "NEW",
    notes: null,
    createdAt: new Date(Date.now() - minutesAgo * 60_000).toISOString(),
    items: [{ quantity: 2, productName: "Doble cheddar" }],
  };
}

function renderCard(minutesAgo: number): HTMLElement {
  render(
    <OrderCard
      order={buildOrder(minutesAgo)}
      now={new Date()}
      animate={false}
      onAdvance={() => {}}
      onRequestCancel={() => {}}
    />
  );

  const root = document.querySelector(".order-card") as HTMLElement;
  expect(root).not.toBeNull();
  return root;
}

describe("order card urgency classes", () => {
  afterEach(() => cleanup());

  it("up to 5 minutes the card is urgency-green", () => {
    expect(renderCard(2).className).toContain("urgency-green");
  });

  it("over 5 minutes the card is urgency-orange", () => {
    expect(renderCard(6).className).toContain("urgency-orange");
  });

  it("over 10 minutes the card is urgency-red", () => {
    expect(renderCard(11).className).toContain("urgency-red");
  });

  it("the card exposes the progress as a CSS variable for the top accent strip", () => {
    const root = renderCard(2); // 120s of 900s max → 13.33%
    expect(root.style.getPropertyValue("--progress")).toBe("13.33%");
  });
});
