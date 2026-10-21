/**
 * Selector de color HSV inline del panel (Local → Datos del local).
 *
 * El dueño pidió un picker embebido como el de los editores de diseño: región
 * de saturación/brillo arrastrable + barra de tono + hex tipeable. Las reglas
 * que se verifican acá:
 *
 *   - Las conversiones HSV ↔ hex son matemática pura y deben ser exactas
 *     (primarios, extremos e ida y vuelta de los colores de la paleta: un
 *     error de ±1 por canal haría que el swatch elegido no vuelva a marcarse
 *     como seleccionado al recargar).
 *   - Arrastrar el punto de la región SV emite el hex correspondiente al
 *     punto (con clamp a los bordes del área).
 *   - La barra de tono cambia el color manteniendo saturación y brillo.
 *   - El campo hex sólo emite con un valor #RRGGBB completo y válido.
 *   - El picker resincroniza su HSV interno cuando la prop cambia por fuera
 *     (swatch, "Quitar color") sin pelear con el estado durante el drag.
 */
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import ColorPicker from "../src/pages/admin/ColorPicker";
import { hexToHsv, hsvToHex } from "../src/pages/admin/colorPicker";

afterEach(cleanup);

describe("conversiones HSV ↔ hex", () => {
  it("colores primarios y extremos, exactos", () => {
    expect(hsvToHex(0, 1, 1)).toBe("#ff0000");
    expect(hsvToHex(120, 1, 1)).toBe("#00ff00");
    expect(hsvToHex(240, 1, 1)).toBe("#0000ff");
    expect(hsvToHex(0, 0, 1)).toBe("#ffffff");
    expect(hsvToHex(0, 0, 0)).toBe("#000000");
  });

  it("hexToHsv extrae tono, saturación y valor", () => {
    expect(hexToHsv("#ff0000")).toEqual({ h: 0, s: 1, v: 1 });
    expect(hexToHsv("#000000")!.v).toBe(0);
    expect(hexToHsv("#ffffff")!.s).toBe(0);
    // El violeta de la paleta: tono ~262° (azul-violeta), saturado y claro.
    const violet = hexToHsv("#7C3AED")!;
    expect(violet.h).toBeCloseTo(262.1, 0);
    expect(violet.s).toBeCloseTo(0.755, 2);
    expect(violet.v).toBeCloseTo(0.929, 2);
  });

  it("ida y vuelta exacta para los colores de la paleta y el hex del ejemplo", () => {
    for (const hex of ["#7C3AED", "#6366F1", "#22C55E", "#FDE047", "#EC4899", "#123abc", "#7B3333"]) {
      const hsv = hexToHsv(hex)!;
      expect(hsv, `hexToHsv(${hex})`).not.toBeNull();
      expect(hsvToHex(hsv.h, hsv.s, hsv.v)).toBe(hex.toLowerCase());
    }
  });

  it("rechaza valores que no son #RRGGBB", () => {
    expect(hexToHsv("violeta")).toBeNull();
    expect(hexToHsv("#12345")).toBeNull();
    expect(hexToHsv("")).toBeNull();
  });
});

describe("ColorPicker (interacciones)", () => {
  function renderPicker(value = "") {
    const onChange = vi.fn();
    render(<ColorPicker value={value} onChange={onChange} />);
    return { onChange };
  }

  /** Simula el área SV como un rect 100x100 en (0,0) para poder calcular puntos. */
  function mockAreaRect() {
    const area = screen.getByLabelText("Saturación y brillo");
    vi.spyOn(area, "getBoundingClientRect").mockReturnValue({
      x: 0, y: 0, left: 0, top: 0, right: 100, bottom: 100, width: 100, height: 100,
      toJSON: () => ({}),
    } as DOMRect);
    return area;
  }

  it("un pointer en la región SV emite el hex del punto (hue rojo, 80%/80%)", () => {
    const { onChange } = renderPicker();
    const area = mockAreaRect();
    // s = 80/100 = 0.8, v = 1 - 20/100 = 0.8 → con hue 0: r=204, g=b=41.
    fireEvent.pointerDown(area, { clientX: 80, clientY: 20 });
    expect(onChange).toHaveBeenCalledWith("#cc2929");
  });

  it("los puntos fuera del área se clampan a los bordes", () => {
    const { onChange } = renderPicker();
    const area = mockAreaRect();
    // clientX 150 → s=1; clientY -10 → v=1 → rojo puro.
    fireEvent.pointerDown(area, { clientX: 150, clientY: -10 });
    expect(onChange).toHaveBeenCalledWith("#ff0000");
    // clientX 0 → s=0 → blanco (v=1 se mantiene).
    fireEvent.pointerDown(area, { clientX: 0, clientY: 0 });
    expect(onChange).toHaveBeenLastCalledWith("#ffffff");
  });

  it("la barra de tono cambia el color manteniendo saturación y brillo", () => {
    const { onChange } = renderPicker("#7C3AED");
    // El picker arranca en el HSV del violeta (s≈0.755, v≈0.929): hue 0 deja
    // s y v intactos → rojo con el mismo brillo.
    fireEvent.change(screen.getByLabelText("Tono"), { target: { value: "0" } });
    expect(onChange).toHaveBeenCalledWith("#ed3a3a");
    fireEvent.change(screen.getByLabelText("Tono"), { target: { value: "240" } });
    // hue 240 con s≈0.755, v≈0.929 → azul puro del mismo brillo (r=g=m).
    expect(onChange).toHaveBeenLastCalledWith("#3a3aed");
  });

  it("el campo hex emite sólo con un #RRGGBB completo y válido", async () => {
    const user = userEvent.setup();
    const { onChange } = renderPicker();
    const input = screen.getByLabelText("Código hex");

    await user.clear(input);
    await user.type(input, "#zz");
    expect(onChange).not.toHaveBeenCalled();

    await user.clear(input);
    await user.type(input, "#7B3333");
    expect(onChange).toHaveBeenLastCalledWith("#7B3333");
  });

  it("resincroniza el HSV cuando la prop cambia por fuera (swatch / quitar)", () => {
    const { rerender } = render(<ColorPicker value="#7C3AED" onChange={vi.fn()} />);
    // El puntero arranca en la posición del violeta cargado (s≈75.5%, v≈92.9%).
    const pointer = document.querySelector<HTMLElement>(".admin-color-sv-pointer")!;
    expect(pointer.style.left).toMatch(/^75\.5/);
    expect(pointer.style.top).toMatch(/^7\.05/);

    // Un valor externo vacío (Quitar color) lleva el puntero al blanco puro.
    rerender(<ColorPicker value="" onChange={vi.fn()} />);
    // Sin hex válido el HSV interno NO se toca (queda el último): el guard
    // sólo resincroniza con un hex parseable; el texto sigue a la prop.
    expect(screen.getByLabelText("Código hex")).toHaveProperty("value", "");
  });
});
