/**
 * Desbloqueo de audio del KDS (kds/audio.ts).
 *
 * Regla: unlockAudio() NUNCA puede lanzar ni dejar errores no manejados, ni
 * siquiera en entornos donde HTMLMediaElement.play() no devuelve una promesa
 * (jsdom no lo implementa: devuelve undefined). Era exactamente el crash que
 * venía pintando de rojo el CI de main: el click de cualquier test que
 * interactuaba con la consola disparaba "Cannot read properties of undefined
 * (reading 'then')" dentro del listener y vitest salía con exit 1 pese a que
 * todos los tests pasaban.
 *
 * Casos:
 *   - play() devuelve undefined (jsdom): no lanza; no hay promesa que esperar.
 *   - play() devuelve una promesa resuelta: pausa el sonido, resetea currentTime
 *     y marca el audio como desbloqueado (una sola vez).
 */
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { unlockAudio } from "../src/pages/kds/audio";

function mountSound(): HTMLAudioElement {
  const sound = document.createElement("audio");
  sound.id = "newOrderSound";
  document.body.appendChild(sound);
  return sound;
}

beforeEach(() => {
  vi.spyOn(console, "log").mockImplementation(() => {});
});

afterEach(() => {
  document.getElementById("newOrderSound")?.remove();
  vi.restoreAllMocks();
});

describe("unlockAudio", () => {
  it("no lanza cuando play() no devuelve promesa (jsdom)", () => {
    const sound = mountSound();
    sound.play = vi.fn(() => undefined) as unknown as typeof sound.play;

    expect(() => unlockAudio()).not.toThrow();
  });

  it("con una promesa resuelta, pausa, resetea y marca desbloqueado una sola vez", async () => {
    const sound = mountSound();
    const log = vi.spyOn(console, "log");
    sound.play = vi.fn(() => Promise.resolve()) as unknown as typeof sound.play;

    unlockAudio();
    await Promise.resolve();

    expect(sound.play).toHaveBeenCalled();
    expect(sound.paused).toBe(true);
    expect(sound.currentTime).toBe(0);
    expect(log).toHaveBeenCalledWith("Audio desbloqueado");

    // Segunda llamada: ya desbloqueado, no vuelve a reproducir.
    sound.play = vi.fn(() => Promise.resolve()) as unknown as typeof sound.play;
    unlockAudio();
    expect(sound.play).not.toHaveBeenCalled();
  });
});
