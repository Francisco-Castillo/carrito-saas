/**
 * Colores sugeridos desde el logo (spec S3 de odd/tasks/personalizacion-carta.md).
 *
 * Dos capas con responsabilidades separadas:
 *
 *   - `extractDominantColors` es PURA: recibe los bytes RGBA (canvas en
 *     producción, arrays sintéticos en tests) y devuelve los colores
 *     dominantes en hex mayúscula. Reglas:
 *       · Los píxeles transparentes (alfa < 128) no aportan — los logos PNG
 *         con fondo transparente no deben "contar" el vacío.
 *       · El fondo casi-blanco / casi-negro se descarta POR CANALES
 *         (min(r,g,b) > 225 o max(r,g,b) < 30), no por luminancia: un logo
 *         azul puro tiene luminancia WCAG ~18 y una regla por luminancia lo
 *         perdería.
 *       · Cuantización a 4 bits por canal + distancia RGB mínima: los rojos
 *         casi iguales se fusionan en un solo dominante.
 *   - `suggestColorsFromImage` es el wrapper de IO: carga la imagen, la
 *     dibuja en un canvas off-screen y delega en la función pura. Falla con
 *     mensajes legibles (la UI los muestra tal cual).
 */
export interface DominantColorOptions {
  /** Máximo de colores a devolver (default 6). */
  max?: number;
  /** Distancia RGB mínima entre dominantes (default 48). */
  minDistance?: number;
}

function toHex(r: number, g: number, b: number): string {
  const hex = (c: number) => c.toString(16).padStart(2, "0");
  return `#${hex(r)}${hex(g)}${hex(b)}`.toUpperCase();
}

export function extractDominantColors(
  data: ArrayLike<number>,
  width: number,
  height: number,
  options: DominantColorOptions = {},
): string[] {
  const max = options.max ?? 6;
  const minDistance = options.minDistance ?? 48;

  interface Bucket {
    count: number;
    r: number;
    g: number;
    b: number;
  }
  const buckets = new Map<number, Bucket>();
  const total = width * height;
  // Muestreo con tope: logos de 1600x400 serían 640k píxeles; con ~20k
  // muestras la paleta dominante es idéntica y el análisis es instantáneo.
  const stride = Math.max(1, Math.floor(total / 20000));

  for (let i = 0; i < total; i += stride) {
    const o = i * 4;
    if ((data[o + 3] ?? 0) < 128) continue;
    const r = data[o] ?? 0;
    const g = data[o + 1] ?? 0;
    const b = data[o + 2] ?? 0;
    // Descarte de fondo POR CANALES (ver comentario del módulo): casi blanco
    // = los tres canales altos; casi negro = los tres bajos.
    if (Math.min(r, g, b) > 225 || Math.max(r, g, b) < 30) continue;

    const key = ((r >> 4) << 8) | ((g >> 4) << 4) | (b >> 4);
    const bucket = buckets.get(key) ?? { count: 0, r: 0, g: 0, b: 0 };
    bucket.count += 1;
    bucket.r += r;
    bucket.g += g;
    bucket.b += b;
    buckets.set(key, bucket);
  }

  const sorted = [...buckets.values()]
    .sort((a, b) => b.count - a.count)
    .map((bk) => ({
      r: Math.round(bk.r / bk.count),
      g: Math.round(bk.g / bk.count),
      b: Math.round(bk.b / bk.count),
    }));

  const picked: Array<{ r: number; g: number; b: number }> = [];
  for (const candidate of sorted) {
    if (picked.length >= max) break;
    const farEnough = picked.every(
      (p) => Math.hypot(p.r - candidate.r, p.g - candidate.g, p.b - candidate.b) >= minDistance,
    );
    if (farEnough) picked.push(candidate);
  }

  return picked.map((c) => toHex(c.r, c.g, c.b));
}

/** Carga la imagen del logo, la pasa por canvas y devuelve sus dominantes. */
export function suggestColorsFromImage(url: string): Promise<string[]> {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => {
      const canvas = document.createElement("canvas");
      canvas.width = img.naturalWidth || img.width;
      canvas.height = img.naturalHeight || img.height;
      if (!canvas.width || !canvas.height) {
        reject(new Error("No se pudo analizar el logo"));
        return;
      }
      const ctx = canvas.getContext("2d");
      if (!ctx) {
        reject(new Error("No se pudo analizar el logo"));
        return;
      }
      ctx.drawImage(img, 0, 0);
      const imageData = ctx.getImageData(0, 0, canvas.width, canvas.height);
      resolve(extractDominantColors(imageData.data, canvas.width, canvas.height));
    };
    img.onerror = () =>
      reject(new Error("No se pudo cargar el logo para analizar sus colores"));
    img.src = url;
  });
}
