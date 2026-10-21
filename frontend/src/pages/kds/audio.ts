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
    sound.play().catch(() => {});
  }
}

/** kds.js:25-31 — playReadySound() */
export function playReadySound(): void {
  const sound = document.getElementById("readySound") as HTMLAudioElement | null;
  if (sound) {
    sound.currentTime = 0;
    sound.play().catch(() => {});
  }
}

/** kds.js:33-45 — unlockAudio(): reproduce y pausa para desbloquear el audio. */
export function unlockAudio(): void {
  if (audioUnlocked) return;

  const sound = document.getElementById("newOrderSound") as HTMLAudioElement | null;
  if (!sound) return;

  const playing = sound.play();
  // jsdom (y entornos sin implementación de media) devuelve undefined: sin
  // el guard, unlockAudio revienta como excepción no capturada en tests.
  if (!playing) return;

  playing
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
