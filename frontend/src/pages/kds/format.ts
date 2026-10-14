/**
 * Formato del celular del cliente para la pantalla de cocina.
 *
 * El `href` del link usa los dígitos crudos (`tel:1155551234`), así que esto es
 * sólo presentación: mostrar 1155551234 de corrido obliga al cocinero a contar
 * dígitos para marcar.
 *
 * Se reconocen los largos típicos de Argentina y, si el número no encaja en
 * ninguno, se devuelven los dígitos tal cual: mejor un número crudo que uno mal
 * formateado.
 */
export function formatPhone(value: string | null | undefined): string {
  const raw = (value ?? "").trim();
  const digits = raw.replace(/\D/g, "");

  // Sin dígitos no hay nada que formatear: se muestra lo que vino, no vacío.
  if (digits === "") return raw;

  // Área + número: 1155551234 → 11 5555-1234
  if (digits.length === 10) {
    return `${digits.slice(0, 2)} ${digits.slice(2, 6)}-${digits.slice(6)}`;
  }

  // Celular con el 9 de móvil: 91155551234 → 9 11 5555-1234
  if (digits.length === 11 && digits.startsWith("9")) {
    return `9 ${digits.slice(1, 3)} ${digits.slice(3, 7)}-${digits.slice(7)}`;
  }

  // Número sin área: 55551234 → 5555-1234
  if (digits.length === 8) {
    return `${digits.slice(0, 4)}-${digits.slice(4)}`;
  }

  return digits;
}
