/**
 * Paleta curada para el color de la carta (Local → Personalización): tonos
 * que se ven bien sobre el fondo oscuro glass (el contraste del texto encima
 * lo resuelve brandColor en la carta; la curaduría es estética). El nombre es
 * el accessible name del swatch (los tests lo usan). Vive en su propio
 * módulo para que DatosSection y PersonalizationSection no la compartan por
 * accidente: el color es de la pestaña Personalización.
 */

export const CARTA_COLORS: Array<{ name: string; hex: string }> = [
  { name: "Violeta", hex: "#7C3AED" },
  { name: "Índigo", hex: "#6366F1" },
  { name: "Azul", hex: "#3B82F6" },
  { name: "Celeste", hex: "#22D3EE" },
  { name: "Verde", hex: "#22C55E" },
  { name: "Lima", hex: "#84CC16" },
  { name: "Amarillo", hex: "#FDE047" },
  { name: "Naranja", hex: "#F97316" },
  { name: "Rojo", hex: "#EF4444" },
  { name: "Rosa", hex: "#EC4899" },
  { name: "Fucsia", hex: "#D946EF" },
  { name: "Blanco humo", hex: "#E2E8F0" },
];
