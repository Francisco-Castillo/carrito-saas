/**
 * Formato del celular en el KDS: se reconoce el largo, se agrupa para leerlo y
 * lo desconocido se deja crudo (nunca se inventa un formato).
 */
import { describe, expect, it } from "vitest";
import { formatPhone } from "../src/pages/kds/format";

describe("formatPhone", () => {
  it("groups an area code plus number", () => {
    expect(formatPhone("1155551234")).toBe("11 5555-1234");
  });

  it("groups a mobile number carrying the 9 prefix", () => {
    expect(formatPhone("91155551234")).toBe("9 11 5555-1234");
  });

  it("groups a number without area code", () => {
    expect(formatPhone("55551234")).toBe("5555-1234");
  });

  it("strips the separators the customer may have typed", () => {
    expect(formatPhone("11 5555-1234")).toBe("11 5555-1234");
    // Con código de país los dígitos no encajan en ningún largo conocido: se
    // muestran crudos, sin inventar un formato.
    expect(formatPhone("+54 9 11 5555 1234")).toBe("5491155551234");
  });

  it("leaves an unrecognized length untouched instead of guessing", () => {
    expect(formatPhone("123456789012")).toBe("123456789012");
    expect(formatPhone("123")).toBe("123");
  });

  it("returns what came in when there are no digits at all", () => {
    expect(formatPhone("sin teléfono")).toBe("sin teléfono");
    expect(formatPhone(null)).toBe("");
  });
});
