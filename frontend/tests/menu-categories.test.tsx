/**
 * Public menu sections & category navigation. The redesigned carta renders one
 * ALWAYS-VISIBLE photo-first section per category (the legacy accordion hid
 * products behind clicks) with a heading, a muted item count and a sticky pill
 * nav that scrolls to the section. The customer must see food as soon as the
 * menu loads — same goal as the old first-screen contract, different DOM.
 */
import { render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import Menu from "../src/pages/Menu";

const SLUG = "ok-local";
const MENU_PAYLOAD = {
  products: [
    { id: 101, name: "Milanesa", description: "con papas", price: 100, categoryId: 1, categoryName: "Platos" },
    { id: 102, name: "Suprema", description: "con puré", price: 120, categoryId: 1, categoryName: "Platos" },
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

describe("menu sections & category nav", () => {
  it("renders one always-visible section per category with heading and item count", async () => {
    window.history.replaceState({}, "", `/?restaurant=${SLUG}`);
    stubFetch();

    render(<Menu />);

    await screen.findByText("Milanesa");
    // Every category section is in the DOM at once: nothing is hidden
    // behind accordion clicks any more.
    expect(screen.getByText("Suprema")).toBeInTheDocument();
    expect(screen.getByText("Flan")).toBeInTheDocument();
    // One section per category, in the API's order, with heading + count.
    const sections = document.querySelectorAll(".menu-section");
    expect(sections).toHaveLength(2);
    expect(sections[0]).toHaveTextContent("Platos");
    expect(sections[1]).toHaveTextContent("Postres");
    expect(screen.getByRole("heading", { name: "Platos" })).toBeInTheDocument();
    expect(screen.getByRole("heading", { name: "Postres" })).toBeInTheDocument();
    expect(screen.getByText("2 ítems")).toBeInTheDocument(); // Platos
    expect(screen.getByText("1 ítem")).toBeInTheDocument(); // Postres
    // The pill nav mirrors the categories in the same order.
    const nav = screen.getByRole("navigation", { name: "Categorías" });
    const pills = Array.from(nav.querySelectorAll(".nav-pill"));
    expect(pills.map((pill) => pill.textContent)).toEqual(["Platos", "Postres"]);
  });

  it("the category nav pill scrolls to its section and marks it active", async () => {
    window.history.replaceState({}, "", `/?restaurant=${SLUG}`);
    stubFetch();
    const scrollIntoView = vi.fn();
    Element.prototype.scrollIntoView = scrollIntoView;

    render(<Menu />);
    await screen.findByText("Milanesa");
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Postres" }));

    // The click scrolled to the Postres section…
    expect(scrollIntoView).toHaveBeenCalled();
    const scrolled = (scrollIntoView as ReturnType<typeof vi.fn>).mock.contexts[0] as HTMLElement;
    expect(scrolled.id).toBe("categoria-1");
    // …and the pill reflects the active section.
    expect(screen.getByRole("button", { name: "Postres" })).toHaveAttribute("aria-current", "true");
    expect(screen.getByRole("button", { name: "Platos" })).not.toHaveAttribute("aria-current");
  });
});
