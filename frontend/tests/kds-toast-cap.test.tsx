import { act, cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import KdsToastHost from "../src/pages/kds/KdsToastHost";
import { KDS_TOAST_EVENT, type KdsToastDetail } from "../src/pages/kds/toast";

/**
 * Toast cap contract: the board can receive a burst of order events (a busy
 * service pushes one toast per new order), so the host keeps at most 3
 * toasts on screen — the OLDEST beyond the cap is dropped immediately, not
 * queued. Without the cap a burst covers the columns (observed live with 12
 * simultaneous orders covering the whole board).
 */
function fireToast(message: string) {
  act(() => {
    window.dispatchEvent(
      new CustomEvent<KdsToastDetail>(KDS_TOAST_EVENT, {
        detail: { message, type: "info" },
      })
    );
  });
}

describe("kds toast host keeps at most 3 toasts", () => {
  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it("under the cap every toast is visible", () => {
    render(<KdsToastHost />);
    fireToast("uno");
    fireToast("dos");
    expect(screen.getByText("uno")).toBeInTheDocument();
    expect(screen.getByText("dos")).toBeInTheDocument();
  });

  it("a burst of 5 keeps only the 3 newest, dropping the oldest", () => {
    render(<KdsToastHost />);
    fireToast("t1");
    fireToast("t2");
    fireToast("t3");
    fireToast("t4");
    fireToast("t5");

    expect(screen.queryByText("t1")).not.toBeInTheDocument();
    expect(screen.queryByText("t2")).not.toBeInTheDocument();
    expect(screen.getByText("t3")).toBeInTheDocument();
    expect(screen.getByText("t4")).toBeInTheDocument();
    expect(screen.getByText("t5")).toBeInTheDocument();
  });
});
