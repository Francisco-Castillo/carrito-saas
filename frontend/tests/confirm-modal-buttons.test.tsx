/**
 * ConfirmModal — botones visibles del diálogo de eliminación: además de los
 * caminos invisibles que ya existían (Escape, clic fuera), el modal tiene que
 * ofrecer un botón "Cancelar" explícito al lado de "Confirmar" (el usuario
 * pidió no depender de atajos para abortar una baja).
 */
import { cleanup, render, screen } from "@testing-library/react"
import userEvent from "@testing-library/user-event"
import { afterEach, describe, expect, it, vi } from "vitest"
import ConfirmModal from "../src/pages/admin/ConfirmModal"

describe("ConfirmModal — botones visibles", () => {
  afterEach(cleanup)

  it("tiene un botón Cancelar que resuelve onCancel", async () => {
    const onCancel = vi.fn()
    const user = userEvent.setup()

    render(<ConfirmModal message="¿Eliminar?" onConfirm={vi.fn()} onCancel={onCancel} />)

    await user.click(screen.getByRole("button", { name: "Cancelar" }))
    expect(onCancel).toHaveBeenCalledTimes(1)
  })

  it("mantiene Confirmar resolviendo onConfirm", async () => {
    const onConfirm = vi.fn()
    const user = userEvent.setup()

    render(<ConfirmModal message="¿Eliminar?" onConfirm={onConfirm} onCancel={vi.fn()} />)

    await user.click(screen.getByRole("button", { name: "Confirmar" }))
    expect(onConfirm).toHaveBeenCalledTimes(1)
  })
})
