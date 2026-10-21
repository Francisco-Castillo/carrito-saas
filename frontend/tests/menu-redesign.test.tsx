/**
 * Menu redesign (photo-first liquid glass): tests for the new surface —
 * category nav pills with scroll-spy, the product detail sheet, the mobile
 * cart bar/sheet, and the intentional photo placeholder. The pinned order
 * contract lives in menu-contract.test.tsx; nothing here weakens it.
 */
import { act, cleanup, render, screen, within } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import Menu from "../src/pages/Menu";

const SLUG = "ok-local";
const MENU_PAYLOAD = {
  products: [
    {
      id: 101,
      name: "Milanesa",
      description: "con papas",
      price: 100,
      categoryId: 1,
      categoryName: "Platos",
      imageUrl: "img-milanesa.jpg",
    },
    {
      id: 202,
      name: "Flan",
      description: "casero",
      price: 50,
      categoryId: 2,
      categoryName: "Postres",
    },
    {
      id: 303,
      name: "Agua",
      description: "botella 500ml",
      price: 30,
      categoryId: 3,
      categoryName: "Bebidas",
      imageUrl: "img-agua.jpg",
    },
  ],
  combos: [
    {
      id: 9,
      name: "Combo Milanesa",
      price: 150,
      categoryId: 4,
      items: [{ quantity: 1, productName: "Milanesa" }],
    },
    {
      id: 11,
      name: "Combo Bebida Cara",
      price: 300,
      categoryId: 4,
      items: [
        { quantity: 1, productName: "Agua" },
        { quantity: 1, productName: "Milanesa" },
      ],
    },
    {
      id: 10,
      name: "Combo Sorpresa",
      price: 200,
      categoryId: 4,
      items: [{ quantity: 1, productName: "Producto Inexistente" }],
    },
  ],
};

const okJson = (data: unknown) => ({ ok: true, status: 200, json: async () => data });

function stubFetch(business?: unknown) {
  vi.stubGlobal(
    "fetch",
    vi.fn(async (url: string | URL | Request) => {
      const u = String(url instanceof Request ? url.url : url);
      if (u.startsWith("/api/restaurants/slug/")) {
        return okJson({ name: "Local OK", whatsappNumber: "" });
      }
      if (u.startsWith("/api/menu/")) {
        return okJson(business === undefined ? MENU_PAYLOAD : { ...MENU_PAYLOAD, business });
      }
      return okJson({});
    }),
  );
}

async function renderMenu() {
  window.history.replaceState({}, "", `/?restaurant=${SLUG}`);
  stubFetch();
  render(<Menu />);
  await screen.findByText("Milanesa");
}

const STOREFRONT_BUSINESS = {
  id: 990000001,
  name: "Local Vidriería",
  slug: SLUG,
  whatsappNumber: "1155556666",
  logoUrl: null,
  bannerUrl: null,
  address: "Calle Falsa 123",
  instagramUrl: null,
  hours: [],
};

async function renderMenuWithStorefront() {
  window.history.replaceState({}, "", `/?restaurant=${SLUG}`);
  stubFetch(STOREFRONT_BUSINESS);
  render(<Menu />);
  await screen.findByText("Milanesa");
}

/** jsdom has no IntersectionObserver: a controllable fake for the scroll-spy. */
class FakeIntersectionObserver {
  static instances: FakeIntersectionObserver[] = [];
  callback: IntersectionObserverCallback;
  elements: Element[] = [];

  constructor(callback: IntersectionObserverCallback) {
    this.callback = callback;
    FakeIntersectionObserver.instances.push(this);
  }
  observe(el: Element) {
    this.elements.push(el);
  }
  unobserve(el: Element) {
    this.elements = this.elements.filter((e) => e !== el);
  }
  disconnect() {
    this.elements = [];
  }
  /** Fires the observer callback as if elements[index] entered the viewport band. */
  trigger(index: number, isIntersecting = true) {
    const entry = {
      isIntersecting,
      target: this.elements[index],
    } as unknown as IntersectionObserverEntry;
    this.callback([entry], this as unknown as IntersectionObserver);
  }
}

function stubScrollSpy() {
  FakeIntersectionObserver.instances = [];
  vi.stubGlobal(
    "IntersectionObserver",
    FakeIntersectionObserver as unknown as typeof IntersectionObserver,
  );
  Element.prototype.scrollIntoView = vi.fn();
}

afterEach(() => {
  cleanup();
  delete (window as unknown as Record<string, unknown>).matchMedia;
  delete (Element.prototype as unknown as Record<string, unknown>).scrollIntoView;
});

describe("category nav pills (scroll-spy)", () => {
  it("renders one pill per category in API order and follows the section in view", async () => {
    stubScrollSpy();
    await renderMenu();

    const nav = screen.getByRole("navigation", { name: "Categorías" });
    const pills = within(nav).getAllByRole("button");
    expect(pills.map((p) => p.textContent)).toEqual([
      "Platos",
      "Postres",
      "Bebidas",
      "Combos",
    ]);
    // Before any scroll event the first section is the active one.
    expect(pills[0]).toHaveAttribute("aria-current", "true");

    // The observer watches one section per category.
    const observer = FakeIntersectionObserver.instances[0];
    expect(observer.elements).toHaveLength(4);

    // The Bebidas section scrolls into the viewport band → its pill activates.
    act(() => observer.trigger(2));
    expect(
      within(nav).getByRole("button", { name: "Bebidas" }),
    ).toHaveAttribute("aria-current", "true");
    expect(
      within(nav).getByRole("button", { name: "Platos" }),
    ).not.toHaveAttribute("aria-current");
  });

  it("clicking a pill smooth-scrolls to its section and marks it active", async () => {
    stubScrollSpy();
    await renderMenu();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Postres" }));

    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith(
      expect.objectContaining({ behavior: "smooth" }),
    );
    expect(screen.getByRole("button", { name: "Postres" })).toHaveAttribute(
      "aria-current",
      "true",
    );
    expect(screen.getByRole("button", { name: "Platos" })).not.toHaveAttribute(
      "aria-current",
    );
  });

  it("respects prefers-reduced-motion: pill click scrolls without smooth behavior", async () => {
    stubScrollSpy();
    (window as unknown as Record<string, unknown>).matchMedia = (query: string) => ({
      matches: query.includes("prefers-reduced-motion"),
      media: query,
      addEventListener() {},
      removeEventListener() {},
      addListener() {},
      removeListener() {},
    });
    await renderMenu();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Postres" }));

    expect(Element.prototype.scrollIntoView).toHaveBeenCalledWith(
      expect.objectContaining({ behavior: "auto" }),
    );
  });
});

describe("menu block: heading + pills in place", () => {
  it("renders the pills inside the menu block, after the storefront, under a real heading", async () => {
    stubScrollSpy();
    await renderMenuWithStorefront();

    // El bloque de menú existe y viene DESPUÉS de la portada.
    const block = document.querySelector(".menu-block") as HTMLElement;
    expect(block).not.toBeNull();
    const storefront = document.querySelector(".storefront")!;
    expect(storefront).not.toBeNull();
    expect(
      storefront.compareDocumentPosition(block) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();

    // Encabezado real que precede a las píldoras, dentro del mismo bloque.
    const heading = within(block).getByRole("heading", { name: "Menú" });
    const nav = within(block).getByRole("navigation", { name: "Categorías" });
    expect(
      heading.compareDocumentPosition(nav) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();

    // Las píldoras quedan por encima de la primera sección (y dentro del bloque).
    const firstSection = block.querySelector(".menu-section");
    expect(firstSection).not.toBeNull();
    expect(block.contains(nav)).toBe(true);
    expect(
      nav.compareDocumentPosition(firstSection!) & Node.DOCUMENT_POSITION_FOLLOWING,
    ).toBeTruthy();
  });

  it("toggles is-stuck with the sentinel observer: glass only while the bar is actually stuck", async () => {
    stubScrollSpy();
    await renderMenu();

    const bar = document.querySelector(".menu-sticky")!;
    expect(bar).not.toBeNull();
    // En su lugar natural (dentro del bloque de menú) NO hay vidrio.
    expect(bar).not.toHaveClass("is-stuck");

    // El observer del sentinel es uno aparte del scroll-spy de secciones.
    const observer = FakeIntersectionObserver.instances.find((o) =>
      o.elements.some((el) => el.classList.contains("menu-sticky-sentinel")),
    );
    expect(observer).toBeDefined();
    expect(observer!.elements).toHaveLength(1);

    // El sentinel dejó de verse → la barra quedó pegada arriba → vidrio.
    act(() => observer!.trigger(0, false));
    expect(bar).toHaveClass("is-stuck");

    // El sentinel volvió a verse → la barra está en su lugar natural.
    act(() => observer!.trigger(0, true));
    expect(bar).not.toHaveClass("is-stuck");
  });
});

describe("product detail sheet", () => {
  it("opens from the card, adds the chosen quantity and restores focus", async () => {
    await renderMenu();
    const user = userEvent.setup();

    // Tapping the card body (not the add control) opens the sheet.
    await user.click(screen.getByText("Milanesa"));
    const dialog = screen.getByRole("dialog", { name: "Detalles de Milanesa" });
    expect(dialog).toHaveAttribute("aria-modal", "true");
    expect(within(dialog).getByText("con papas")).toBeInTheDocument();
    expect(within(dialog).getByText("$ 100")).toBeInTheDocument();

    await user.click(within(dialog).getByRole("button", { name: "Agregar Milanesa" }));
    expect(within(dialog).getByLabelText("Cantidad de Milanesa")).toHaveTextContent("2");

    await user.click(within(dialog).getByRole("button", { name: "Agregar al pedido" }));
    expect(
      screen.queryByRole("dialog", { name: "Detalles de Milanesa" }),
    ).not.toBeInTheDocument();

    // The mobile cart bar reflects the two added units (scoped to the bar:
    // section headings show similar item counts).
    const bar = document.querySelector(".cart-bar")!;
    expect(within(bar).getByText("2 ítems")).toBeInTheDocument();
    expect(within(bar).getByText("$ 200")).toBeInTheDocument();

    // Focus returns to the card that opened the sheet.
    expect(document.activeElement).toHaveTextContent("Milanesa");
  });

  it("closes on Escape and on backdrop click, but not on clicks inside the sheet", async () => {
    await renderMenu();
    const user = userEvent.setup();

    await user.click(screen.getByText("Flan"));
    expect(screen.getByRole("dialog", { name: "Detalles de Flan" })).toBeInTheDocument();

    await user.keyboard("{Escape}");
    expect(
      screen.queryByRole("dialog", { name: "Detalles de Flan" }),
    ).not.toBeInTheDocument();

    // Reopen: the backdrop closes it…
    await user.click(screen.getByText("Flan"));
    const backdrop = document.querySelector(".sheet-backdrop")!;
    expect(backdrop).not.toBeNull();
    await user.click(backdrop);
    expect(
      screen.queryByRole("dialog", { name: "Detalles de Flan" }),
    ).not.toBeInTheDocument();

    // …but a click inside the sheet must not.
    await user.click(screen.getByText("Flan"));
    await user.click(screen.getByRole("dialog", { name: "Detalles de Flan" }));
    expect(screen.getByRole("dialog", { name: "Detalles de Flan" })).toBeInTheDocument();
  });
});

describe("cart bar and cart sheet", () => {
  it("shows item count and total, opens the cart as a sheet and lets the customer step lines", async () => {
    await renderMenu();
    const user = userEvent.setup();

    await user.click(screen.getByRole("button", { name: "Agregar Milanesa" }));
    await user.click(screen.getByRole("button", { name: "Agregar Agua" }));
    // Scoped to the bar: category section headings carry similar counts.
    const bar = () => document.querySelector(".cart-bar")!;
    expect(within(bar()).getByText("2 ítems")).toBeInTheDocument();
    expect(within(bar()).getByText("$ 130")).toBeInTheDocument();

    await user.click(screen.getByRole("button", { name: "Ver pedido" }));
    const cart = screen.getByRole("complementary", { name: "Tu pedido" });
    expect(within(cart).getByText("Milanesa")).toBeInTheDocument();
    expect(within(cart).getByText("Agua")).toBeInTheDocument();
    expect(within(cart).getByLabelText("Cantidad de Milanesa")).toHaveTextContent("1");
    expect(within(cart).getByText("$ 100")).toBeInTheDocument();
    expect(within(cart).getByText("$ 30")).toBeInTheDocument();
    expect(within(cart).getByText("Total: $ 130")).toBeInTheDocument();

    // The line steppers update quantity, line and grand total.
    await user.click(within(cart).getByRole("button", { name: "Sumar Milanesa" }));
    expect(within(cart).getByLabelText("Cantidad de Milanesa")).toHaveTextContent("2");
    expect(within(cart).getByText("Total: $ 230")).toBeInTheDocument();

    // Removing every unit of a line drops the line from the cart.
    await user.click(within(cart).getByRole("button", { name: "Restar Milanesa" }));
    await user.click(within(cart).getByRole("button", { name: "Restar Milanesa" }));
    expect(within(cart).queryByText("Milanesa")).not.toBeInTheDocument();
    expect(within(cart).getByText("Total: $ 30")).toBeInTheDocument();

    // Closing the sheet keeps the bar with the remaining order.
    await user.click(screen.getByRole("button", { name: "Cerrar el pedido" }));
    expect(
      screen.queryByRole("complementary", { name: "Tu pedido" }),
    ).not.toBeInTheDocument();
    expect(within(bar()).getByText("1 ítem")).toBeInTheDocument();
    expect(within(bar()).getByText("$ 30")).toBeInTheDocument();
  });
});

describe("photo placeholders", () => {
  it("renders an intentional tinted placeholder for products without a photo", async () => {
    await renderMenu();

    const flanCard = screen.getByText("Flan").closest("article")!;
    // No broken <img>, but a styled gradient panel with a food glyph.
    expect(flanCard.querySelector("img")).toBeNull();
    const placeholder = flanCard.querySelector(".food-placeholder");
    expect(placeholder).not.toBeNull();
    expect(placeholder).toHaveAttribute("data-tint");
    // The glyph is decorative; the product name stays accessible.
    expect(placeholder).toHaveAttribute("aria-label", "Flan");
    expect(placeholder).toHaveTextContent("🍰"); // category-tinted glyph (Postres)
  });

  it("combos show their most expensive component's photo and fall back to the placeholder", async () => {
    await renderMenu();

    const comboCard = screen.getByText("Combo Milanesa").closest("article")!;
    expect(comboCard.querySelector("img")).toHaveAttribute("src", "img-milanesa.jpg");
    expect(within(comboCard).getByText("Combo")).toBeInTheDocument(); // badge

    // El componente caro manda aunque venga segundo: "Agua" ($30) antes que
    // "Milanesa" ($100) no debe mostrar la foto de la bebida.
    const mainCourseCard = screen.getByText("Combo Bebida Cara").closest("article")!;
    expect(mainCourseCard.querySelector("img")).toHaveAttribute("src", "img-milanesa.jpg");

    const fallbackCard = screen.getByText("Combo Sorpresa").closest("article")!;
    expect(fallbackCard.querySelector("img")).toBeNull();
    expect(fallbackCard.querySelector(".food-placeholder")).not.toBeNull();
  });
});
