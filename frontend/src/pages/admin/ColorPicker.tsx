/**
 * Selector de color HSV inline para el color de la carta (panel admin).
 *
 * Reemplaza al input nativo type="color" (que abre el diálogo del SO) por un
 * picker embebido como el de los editores de diseño: región de saturación y
 * brillo con punto arrastrable, barra de tono arcoíris y campo de hex tipeable
 * con preview. El dueño pidió explícitamente este formato (referencia visual:
 * área de gradiente + slider de tono + input "#7B3333").
 *
 * El contrato con el perfil NO cambia: el componente recibe el hex actual y
 * emite hex #RRGGBB por onChange; el backend valida igual que siempre.
 *
 * Detalles de implementación:
 *   - El estado interno es HSV (la UI vive en esos ejes). Se resincroniza
 *     desde la prop cuando el valor externo difiere del que representamos
 *     (guard de ida y vuelta) para no pelear con el arrastre en curso.
 *   - El arrastre usa listeners en window durante el pointerdown (sin
 *     setPointerCapture: jsdom no lo implementa y los tests lo ejercitan).
 *   - El campo de hex sólo emite onChange con un valor #RRGGBB completo y
 *     válido; mientras se tipea, el estado del perfil no se toca.
 */
import { useEffect, useRef, useState } from "react";
import type { ChangeEvent, PointerEvent as ReactPointerEvent } from "react";
import { hexToHsv, hsvToHex } from "./colorPicker";

/** Mismo contrato que valida el backend. */
const HEX_COLOR = /^#[0-9a-fA-F]{6}$/;

interface HsvState {
  h: number;
  s: number;
  v: number;
}

export default function ColorPicker({
  value,
  onChange,
}: {
  value: string;
  onChange: (hex: string) => void;
}) {
  const [hsv, setHsv] = useState<HsvState>(() => hexToHsv(value) ?? { h: 0, s: 1, v: 1 });
  const [text, setText] = useState(value);
  const areaRef = useRef<HTMLDivElement>(null);

  // Resincronización externa: si la prop trae un hex que NO es el que estamos
  // mostrando (carga del perfil, swatch elegido, "Quitar color"), el HSV
  // interno y el texto siguen al valor. Durante un drag propio el hex emitido
  // coincide con el representado y este guard no hace nada.
  useEffect(() => {
    setText(value);
    const parsed = hexToHsv(value);
    setHsv((prev) =>
      parsed && hsvToHex(prev.h, prev.s, prev.v).toUpperCase() !== value.toUpperCase()
        ? parsed
        : prev,
    );
  }, [value]);

  /** Traduce un punto de la región SV a s/v (con clamp) y emite el hex. */
  function applyPointer(clientX: number, clientY: number) {
    const area = areaRef.current;
    if (!area) return;
    const rect = area.getBoundingClientRect();
    const s = Math.min(1, Math.max(0, (clientX - rect.left) / rect.width));
    const v = 1 - Math.min(1, Math.max(0, (clientY - rect.top) / rect.height));
    setHsv((prev) => ({ ...prev, s, v }));
    onChange(hsvToHex(hsv.h, s, v));
  }

  function onAreaPointerDown(e: ReactPointerEvent<HTMLDivElement>) {
    e.preventDefault();
    applyPointer(e.clientX, e.clientY);
    const move = (ev: globalThis.PointerEvent) => applyPointer(ev.clientX, ev.clientY);
    const up = () => {
      window.removeEventListener("pointermove", move);
      window.removeEventListener("pointerup", up);
    };
    window.addEventListener("pointermove", move);
    window.addEventListener("pointerup", up);
  }

  function onHueChange(e: ChangeEvent<HTMLInputElement>) {
    const h = Number(e.target.value);
    setHsv((prev) => ({ ...prev, h }));
    onChange(hsvToHex(h, hsv.s, hsv.v));
  }

  function onHexChange(e: ChangeEvent<HTMLInputElement>) {
    const next = e.target.value;
    setText(next);
    const parsed = hexToHsv(next);
    if (parsed) {
      setHsv(parsed);
      onChange(next);
    }
  }

  const current = hsvToHex(hsv.h, hsv.s, hsv.v);

  return (
    <div className="admin-color-picker">
      <div
        ref={areaRef}
        className="admin-color-sv"
        role="slider"
        aria-label="Saturación y brillo"
        aria-valuemin={0}
        aria-valuemax={100}
        aria-valuenow={Math.round(hsv.s * 100)}
        aria-valuetext={`Saturación ${Math.round(hsv.s * 100)}%, brillo ${Math.round(hsv.v * 100)}%`}
        style={{
          background: `linear-gradient(to top, #000, rgba(0, 0, 0, 0)), linear-gradient(to right, #fff, hsl(${hsv.h} 100% 50%))`,
        }}
        onPointerDown={onAreaPointerDown}
      >
        <span
          className="admin-color-sv-pointer"
          style={{ left: `${hsv.s * 100}%`, top: `${(1 - hsv.v) * 100}%`, backgroundColor: current }}
        />
      </div>

      <input
        type="range"
        className="admin-color-hue"
        aria-label="Tono"
        min={0}
        max={360}
        step={1}
        value={Math.round(hsv.h)}
        onChange={onHueChange}
        style={{
          background:
            "linear-gradient(to right, hsl(0 100% 50%), hsl(60 100% 50%), hsl(120 100% 50%), hsl(180 100% 50%), hsl(240 100% 50%), hsl(300 100% 50%), hsl(360 100% 50%))",
        }}
      />

      <div className="admin-color-row">
        <input
          type="text"
          className="admin-input admin-color-hex-input"
          aria-label="Código hex"
          placeholder="#RRGGBB"
          maxLength={7}
          spellCheck={false}
          value={text}
          onChange={onHexChange}
        />
        <span
          className="admin-color-preview"
          style={{ backgroundColor: HEX_COLOR.test(value) ? value : "transparent" }}
          aria-hidden="true"
        />
      </div>
    </div>
  );
}
