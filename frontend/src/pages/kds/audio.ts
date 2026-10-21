/**
 * Puerto 1:1 de la sección SONIDOS del legacy kds/kds.js (líneas 17-45 y
 * 292-294): ding.mp3 para pedidos nuevos, ready.mp3 para pedidos listos,
 * con desbloqueo de audio en la primera interacción del usuario (un browser
 * no permite autoplay antes de un gesto).
 *
 * Los <audio> se renderizan desde Kds.tsx con los mismos ids que el legacy
 * (index.html:110-115) y las rutas públicas de Vite /ding.mp3 y /ready.mp3.
 */

let audioUnlocked = false;

/** kds.js:17-23 — playNewOrderSound() */
export function playNewOrderSound(): void {
  const sound = document.getElementById("newOrderSound") as HTMLAudioElement | null;
  if (sound) {
    sound.currentTime = 0;
    // play() puede no devolver promesa (jsdom no implementa HTMLMediaElement):
    // sin este guard, .catch revienta igual que .then lo hacía en unlockAudio.
    sound.play()?.catch(() => {});
  }
}

/** kds.js:25-31 — playReadySound() */
export function playReadySound(): void {
  const sound = document.getElementById("readySound") as HTMLAudioElement | null;
  if (sound) {
    sound.currentTime = 0;
    sound.play()?.catch(() => {});
  }
}

/** kds.js:33-45 — unlockAudio(): reproduce y pausa para desbloquear el audio. */
export function unlockAudio(): void {
  if (audioUnlocked) return;

  const sound = document.getElementById("newOrderSound") as HTMLAudioElement | null;
  if (!sound) return;

  const playback = sound.play();
  // jsdom (y cualquier entorno sin HTMLMediaElement real) devuelve undefined:
  // encadenar .then ahí lanzaba TypeError dentro del listener de click y
  // vitest salía con exit 1 aunque todos los tests pasaran.
  if (!playback) return;

  playback
    .then(() => {
      sound.pause();
      sound.currentTime = 0;
      audioUnlocked = true;
      console.log("Audio desbloqueado");
    })
    .catch(() => {});
}

/**
 * kds.js:292-294 — listeners de desbloqueo en click/touchstart/keydown,
 * cada uno con { once: true } como el legacy.
 */
export function attachAudioUnlockListeners(): void {
  document.addEventListener("click", unlockAudio, { once: true });
  document.addEventListener("touchstart", unlockAudio, { once: true });
  document.addEventListener("keydown", unlockAudio, { once: true });
}
