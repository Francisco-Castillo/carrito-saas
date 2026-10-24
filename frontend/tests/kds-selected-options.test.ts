/**
 * KDS selected options: the kitchen board ingests orders from TWO channels
 * with DIFFERENT option shapes —
 *
 *   - REST GET /api/business/orders/active → OrderKitchenDTO: options are
 *     already "Grupo: Opción" strings,
 *   - WebSocket /topic/orders/{slug} → OrderDTO: options are the raw
 *     snapshot objects {groupName, optionId, optionName, priceDelta}.
 *
 * Rendering the object shape as a React child crashes the whole board
 * ("Objects are not valid as a React child") the moment a status change
 * echoes back over the WebSocket. Both shapes must be normalized to
 * "Grupo: Opción" strings at ingestion, before any render.
 */
import { describe, expect, it } from "vitest";
import {
  normalizeKdsOrder,
  normalizeSelectedOptions,
  type KdsOrder,
} from "../src/pages/kds/api";

describe("normalizeSelectedOptions", () => {
  it("maps WebSocket snapshot objects to kitchen strings", () => {
    expect(
      normalizeSelectedOptions([
        { groupName: "Tipo de pan", optionId: 3, optionName: "Integral", priceDelta: 200 },
        { groupName: "Extras", optionId: 7, optionName: "Cheddar", priceDelta: 0 },
      ])
    ).toEqual(["Tipo de pan: Integral", "Extras: Cheddar"]);
  });

  it("passes REST 'Grupo: Opción' strings through untouched", () => {
    expect(normalizeSelectedOptions(["Tipo de pan: Integral"])).toEqual([
      "Tipo de pan: Integral",
    ]);
  });

  it("returns [] for missing or non-array payloads", () => {
    expect(normalizeSelectedOptions(undefined)).toEqual([]);
    expect(normalizeSelectedOptions(null)).toEqual([]);
    expect(normalizeSelectedOptions("Tipo de pan: Integral")).toEqual([]);
  });

  it("falls back to the option name when the group is absent", () => {
    expect(normalizeSelectedOptions([{ optionName: "Sin cebolla" }])).toEqual([
      "Sin cebolla",
    ]);
  });
});

describe("normalizeKdsOrder", () => {
  it("normalizes item options of a WebSocket order event in place", () => {
    const order = {
      orderId: 9,
      orderNumber: 40,
      customerName: "Cliente",
      customerPhone: null,
      orderType: "RETIRO",
      status: "PREPARING",
      notes: null,
      createdAt: new Date().toISOString(),
      items: [
        {
          quantity: 1,
          productName: "Hamburguesa",
          selectedOptions: [
            { groupName: "Tipo de pan", optionId: 3, optionName: "Integral", priceDelta: 200 },
          ],
        },
        { quantity: 2, productName: "Papas", selectedOptions: [] },
      ],
    } as unknown as KdsOrder;

    const normalized = normalizeKdsOrder(order);
    expect(normalized.items[0].selectedOptions).toEqual(["Tipo de pan: Integral"]);
    expect(normalized.items[1].selectedOptions).toEqual([]);
  });
});
