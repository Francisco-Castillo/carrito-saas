import { act, renderHook } from "@testing-library/react";
import { describe, expect, it } from "vitest";

import { useConfirmDialog } from "../src/pages/admin/useConfirmDialog";

/**
 * Contrato del hook de confirmación compartido por las tres tablas del
 * panel (Productos, Categorías, Combos): la promesa responde true SOLO con
 * "Confirmar", y si el componente dueño se desmonta con una confirmación
 * pendiente, la promesa resuelve false en lugar de quedar colgada.
 * (Hallazgos de la revisión nativa del PR #46 sobre las dos tablas que
 * duplicaban el patrón; el cleanup vivía en dos de las tres sin test.)
 */
describe("useConfirmDialog", () => {
  it("opens with the message and resolves true only through Confirmar", async () => {
    const { result } = renderHook(() => useConfirmDialog());

    let promise!: Promise<boolean>;
    act(() => {
      promise = result.current.confirmAsync("¿Seguro que deseas eliminar?");
    });

    expect(result.current.confirmState?.message).toBe("¿Seguro que deseas eliminar?");

    act(() => {
      result.current.settleConfirm(true);
    });

    await expect(promise).resolves.toBe(true);
    expect(result.current.confirmState).toBeNull();
  });

  it("resolves false through Cancelar and clears the dialog state", async () => {
    const { result } = renderHook(() => useConfirmDialog());

    let promise!: Promise<boolean>;
    act(() => {
      promise = result.current.confirmAsync("¿Seguro?");
    });

    act(() => {
      result.current.settleConfirm(false);
    });

    await expect(promise).resolves.toBe(false);
    expect(result.current.confirmState).toBeNull();
  });

  it("resolves a PENDING confirm with false when the owner unmounts", async () => {
    const { result, unmount } = renderHook(() => useConfirmDialog());

    let promise!: Promise<boolean>;
    act(() => {
      promise = result.current.confirmAsync("¿Seguro?");
    });

    // El dueño se desmonta con el confirm abierto: sin cleanup la promesa
    // queda colgada y el flujo que la espera nunca continúa.
    unmount();

    await expect(promise).resolves.toBe(false);
  });
});
