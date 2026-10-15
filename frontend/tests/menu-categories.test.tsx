/**
 * Public menu first-screen contract: the customer must see food as soon as the
 * menu loads. Categories are an accordion (legacy behaviour), but starting with
 * every one collapsed showed an apparently empty menu on the first screen, so
 * the first category is expanded by default while the rest stay collapsed and
 * every header keeps toggling.
 */
import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import Menu from "../src/pages/Menu";

const SLUG = "ok-local";
const MENU_PAYLOAD = {
  products: [
    { id: 101, name: "Milanesa", description: "con papas", price: 100, categoryId: 1, categoryName: "Platos" },
    { id: 202, name: "Flan", description: "casero", price: 50, categoryId: 2, categoryName: "Postres" },
  ],
  combos: [],
};

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data });

function stubFetch() {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string | URL | Request) => {
      const u = String(url instanceof Request ? url.url : url);
      if (u.startsWith("/api/restaurants/slug/")) return okJson({ name: "Local OK", whatsappNumber: "" });
      if (u.startsWith("/api/menu/")) return okJson(MENU_PAYLOAD);
      return okJson({});
    }),
  );
}

describe("menu first screen", () => {
  it("expands the first category by default and keeps the rest collapsed", async () => {
    window.history.replaceState({}, "", `/?restaurant=${SLUG}`);
    stubFetch();

    render(<Menu />);

    await waitFor(() => expect(screen.getByText("Milanesa")).toBeInTheDocument());
    // The first category is open: its products are in the DOM without a click.
    expect(screen.getByRole("button", { name: /Platos/ })).toHaveAttribute("aria-expanded", "true");
    // The second one is still collapsed: nothing rendered for it yet.
    expect(screen.getByRole("button", { name: /Postres/ })).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByText("Flan")).not.toBeInTheDocument();
  });

  it("lets the customer collapse the open category and open another one", async () => {
    window.history.replaceState({}, "", `/?restaurant=${SLUG}`);
    stubFetch();
    const user = userEvent.setup();

    render(<Menu />);
    await waitFor(() => expect(screen.getByText("Milanesa")).toBeInTheDocument());

    await user.click(screen.getByRole("button", { name: /Platos/ }));
    expect(screen.queryByText("Milanesa")).not.toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: /Postres/ }));
    expect(screen.getByText("Flan")).toBeInTheDocument();
  });
});
