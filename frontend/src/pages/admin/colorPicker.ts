/**
 * Conversión HSV ↔ hex para el selector de color del panel.
 *
 * El picker del panel (ColorPicker.tsx) trabaja en HSV porque su UI es una
 * región de saturación/brillo (2 ejes) + una barra de tono (1 eje), pero el
 * backend y el estado del perfil hablan hex #RRGGBB. Estas funciones son el
 * único traductor: puras, sin DOM, verificadas con ida y vuelta.
 */

export interface Hsv {
  /** Tono 0..360. */
  h: number;
  /** Saturación 0..1. */
  s: number;
  /** Valor (brillo) 0..1. */
  v: number;
}

/** Mismo contrato que valida el backend (BusinessProfileServiceImpl). */
const HEX_COLOR = /^#[0-9a-fA-F]{6}$/;

/** HSV → "#RRGGBB" (minúsculas, siempre válido para 0≤h≤360, 0≤s,v≤1). */
export function hsvToHex(h: number, s: number, v: number): string {
  const c = v * s;
  const hp = ((((h % 360) + 360) % 360) / 60);
  const x = c * (1 - Math.abs((hp % 2) - 1));
  let r = 0;
  let g = 0;
  let b = 0;
  if (hp < 1) [r, g, b] = [c, x, 0];
  else if (hp < 2) [r, g, b] = [x, c, 0];
  else if (hp < 3) [r, g, b] = [0, c, x];
  else if (hp < 4) [r, g, b] = [0, x, c];
  else if (hp < 5) [r, g, b] = [x, 0, c];
  else [r, g, b] = [c, 0, x];
  const m = v - c;
  const channel = (t: number) =>
    Math.round((t + m) * 255)
      .toString(16)
      .padStart(2, "0");
  return `#${channel(r)}${channel(g)}${channel(b)}`;
}

/** "#RRGGBB" (mayúsculas o minúsculas) → HSV; null si no es un hex válido. */
export function hexToHsv(hex: string): Hsv | null {
  const trimmed = typeof hex === "string" ? hex.trim() : "";
  if (!HEX_COLOR.test(trimmed)) return null;
  const int = parseInt(trimmed.slice(1), 16);
  const r = ((int >> 16) & 255) / 255;
  const g = ((int >> 8) & 255) / 255;
  const b = (int & 255) / 255;
  const max = Math.max(r, g, b);
  const min = Math.min(r, g, b);
  const d = max - min;
  let h = 0;
  if (d !== 0) {
    if (max === r) h = 60 * (((g - b) / d) % 6);
    else if (max === g) h = 60 * ((b - r) / d + 2);
    else h = 60 * ((r - g) / d + 4);
  }
  if (h < 0) h += 360;
  return { h, s: max === 0 ? 0 : d / max, v: max };
}
