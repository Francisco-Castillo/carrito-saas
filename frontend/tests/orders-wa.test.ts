import { describe, expect, it } from "vitest";
import { whatsappUrl } from "../src/pages/orders/wa";

/**
 * WhatsApp deep link for the customer phone (orders console, T-phones of
 * odd/tasks/orders-console.md): clicking the customer number opens the chat
 * instead of dialing. The URL is a pure string transform — no browser APIs.
 */
describe("whatsappUrl", () => {
  it("keeps an already-international Argentine number as is", () => {
    expect(whatsappUrl("5491123456789")).toBe("https://wa.me/5491123456789");
  });

  it("prefixes the country code when the number is local", () => {
    expect(whatsappUrl("1123456789")).toBe("https://wa.me/541123456789");
  });

  it("strips formatting characters (spaces, dashes, parentheses, +)", () => {
    expect(whatsappUrl("+54 9 11 2345-6789")).toBe("https://wa.me/5491123456789");
    expect(whatsappUrl("(011) 4-5-6-7")).toBe("https://wa.me/54114567");
  });

  it("returns null for a number with no digits", () => {
    expect(whatsappUrl("no hay teléfono")).toBeNull();
    expect(whatsappUrl("")).toBeNull();
  });

  it("never returns a link to an unrelated number when digits exist", () => {
    expect(whatsappUrl("15-1123-4567")).toBe("https://wa.me/541511234567");
  });
});
