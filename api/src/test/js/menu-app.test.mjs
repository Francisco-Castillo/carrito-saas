/**
 * Node-based contract tests for the public menu page (menu/app.js).
 *
 * The backend has no JS test runner, so this file runs outside Maven:
 *
 *   node api/src/test/js/menu-app.test.mjs
 *
 * It executes the real app.js inside a vm sandbox with a minimal DOM/fetch
 * stub and pins the customer-facing failure contract: when the `restaurant`
 * query parameter is missing, or the restaurant cannot be loaded, the page
 * must show a comprehensible in-page message and STOP — no fetch of
 * `/api/menu/undefined` (or `/null`), no broken render, no order panel through
 * which a broken link could still submit an order.
 *
 * The happy path (menu load, cart, order submission) is pinned too, so the
 * failure handling can never silently break it.
 *
 * A failed `/api/menu` fetch (HTTP error status, network rejection,
 * malformed JSON) belongs to the same contract: readable message, no
 * product list, and the order panel stays hidden.
 */
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import vm from "node:vm"

const APP_JS_PATH = new URL("../../main/resources/static/menu/app.js", import.meta.url)
const SOURCE = readFileSync(APP_JS_PATH, "utf8")

function createElement(registry, id) {
	if (!registry.has(id)) {
		registry.set(id, {
			id,
			tagName: "div",
			children: [],
			innerHTML: "",
			innerText: "",
			textContent: "",
			value: "",
			src: "",
			style: { display: "", setProperty() {} },
			classList: { add() {}, remove() {}, toggle() {} },
			appendChild(child) {
				this.children.push(child)
			},
			addEventListener() {},
			onclick: null,
		})
	}
	return registry.get(id)
}

async function settle(rounds = 50) {
	for (let i = 0; i < rounds; i++) {
		await new Promise((resolve) => setImmediate(resolve))
	}
}

async function runMenuPage({ search, menuFailure }) {
	const registry = new Map()
	const fetchCalls = []
	const alerts = []
	const store = new Map()

	const okJson = (data) => ({ ok: true, status: 200, json: async () => data })

	const sandbox = {
		URLSearchParams,
		console,
		alert: (msg) => alerts.push(String(msg)),
		localStorage: {
			getItem: (k) => (store.has(k) ? store.get(k) : null),
			setItem: (k, v) => store.set(k, String(v)),
			removeItem: (k) => store.delete(k),
		},
		fetch: async (url, options) => {
			const call = { url: String(url), method: options?.method || "GET", body: options?.body }
			fetchCalls.push(call)
			const u = call.url
			if (u.startsWith("/api/restaurants/slug/")) {
				return u === "/api/restaurants/slug/ok-local"
					? okJson({ name: "Local OK", whatsappNumber: "" })
					: { ok: false, status: 404, json: async () => ({}) }
			}
			if (u.startsWith("/api/menu/")) {
				// Optional failure injection for the valid restaurant's menu fetch:
				// "http-500" responds 500, "bad-json" responds 200 but res.json()
				// rejects like a malformed body, "network" rejects like a dead link.
				if (menuFailure && u === "/api/menu/ok-local") {
					if (menuFailure === "network") {
						throw new TypeError("Failed to fetch")
					}
					if (menuFailure === "bad-json") {
						return {
							ok: true,
							status: 200,
							json: async () => {
								throw new SyntaxError("Unexpected token '<' is not valid JSON")
							},
						}
					}
					return { ok: false, status: 500, json: async () => ({}) }
				}
				return u === "/api/menu/ok-local"
					? okJson({
							products: [
								{
									id: 101,
									name: "Milanesa",
									description: "con papas",
									price: 100,
									categoryId: 1,
									categoryName: "Platos",
									imageUrl: "img.jpg",
								},
							],
							combos: [],
						})
					: okJson({ products: [], combos: [], categories: [] })
			}
			if (u.startsWith("/api/orders/menu/")) {
				return okJson({ orderId: 42, status: "NEW" })
			}
			throw new Error("unexpected fetch: " + u)
		},
		document: {
			getElementById: (id) => createElement(registry, id),
			createElement: (tag) => createElement(registry, "created:" + tag + ":" + registry.size),
			querySelector: (selector) => createElement(registry, selector),
			documentElement: { style: { setProperty() {} } },
			body: createElement(registry, "body"),
		},
		window: {
			location: { search, href: "" },
		},
	}

	vm.runInNewContext(SOURCE, sandbox)
	await settle()

	return { sandbox, registry, fetchCalls, alerts }
}

function el(registry, id) {
	return registry.get(id)
}

function hasFetch(fetchCalls, fragment) {
	return fetchCalls.some((call) => call.url.includes(fragment))
}

function loadErrorMessageShown(registry) {
	const menu = el(registry, "menu")
	return Boolean(menu) && menu.children.some((child) => (child.textContent || "").includes("carta"))
}

let passed = 0
async function test(name, fn) {
	try {
		await fn()
		passed++
		console.log("PASS " + name)
	} catch (err) {
		console.error("FAIL " + name)
		console.error(err && err.stack ? err.stack : err)
		process.exitCode = 1
	}
}

await test("missing restaurant parameter: shows a customer-readable message and stops before any fetch", async () => {
	const { registry, fetchCalls } = await runMenuPage({ search: "" })

	assert.ok(
		loadErrorMessageShown(registry),
		"an in-page message must be shown for a truncated QR link"
	)
	assert.ok(
		!hasFetch(fetchCalls, "/api/restaurants/"),
		"no data fetch may run when the restaurant parameter is missing"
	)
	assert.ok(
		!hasFetch(fetchCalls, "/api/menu/"),
		"the page must stop instead of fetching /api/menu/undefined or /api/menu/null"
	)
	assert.equal(
		el(registry, ".cart").style.display,
		"none",
		"the order panel must be hidden so a broken link can never submit an order"
	)
})

await test("restaurant cannot be loaded (404): shows a customer-readable message and stops", async () => {
	const { registry, fetchCalls } = await runMenuPage({ search: "?restaurant=ghost" })

	assert.ok(hasFetch(fetchCalls, "/api/restaurants/"), "the restaurant lookup itself may run")
	assert.ok(
		loadErrorMessageShown(registry),
		"an in-page message must be shown when the restaurant cannot be loaded"
	)
	assert.ok(
		!hasFetch(fetchCalls, "/api/menu/"),
		"the page must stop instead of fetching /api/menu/ghost"
	)
	assert.equal(
		el(registry, ".cart").style.display,
		"none",
		"the order panel must be hidden when the restaurant is unavailable"
	)
})

await test("valid link: menu still loads and an order can still be submitted (unchanged happy path)", async () => {
	const { sandbox, registry, fetchCalls, alerts } = await runMenuPage({ search: "?restaurant=ok-local" })

	assert.ok(!loadErrorMessageShown(registry), "the happy path must not show the error message")
	assert.ok(hasFetch(fetchCalls, "/api/menu/ok-local"), "the menu must be fetched for a valid restaurant")

	// Customer picks a product and submits the order: this flow must not change.
	sandbox.addItem("101")

	el(registry, "customerName").value = "Cliente Test"
	await el(registry, "sendOrder").onclick()
	await settle()

	const orderCall = fetchCalls.find((call) => call.url.startsWith("/api/orders/menu/"))
	assert.ok(orderCall, "the order must still be submitted to /api/orders/menu/{slug}")
	assert.equal(orderCall.url, "/api/orders/menu/ok-local")
	assert.equal(orderCall.method, "POST")
	const payload = JSON.parse(orderCall.body)
	assert.deepEqual(payload.items, [{ productId: 101, quantity: 1 }])
	assert.ok(
		alerts.some((message) => message.includes("Pedido recibido")),
		"the customer must still get the order confirmation"
	)
})

await test("menu fetch fails (HTTP 500, network error, malformed JSON): shows a customer-readable message and stops", async () => {
	for (const menuFailure of ["http-500", "bad-json", "network"]) {
		const { registry, fetchCalls } = await runMenuPage({ search: "?restaurant=ok-local", menuFailure })

		assert.ok(
			hasFetch(fetchCalls, "/api/restaurants/"),
			"the restaurant lookup itself may run"
		)
		assert.ok(
			loadErrorMessageShown(registry),
			`an in-page message must be shown when the menu fetch fails (${menuFailure})`
		)
		assert.ok(
			!el(registry, "menu").children.some((child) => child.className === "category"),
			`no product list may be rendered when the menu fetch fails (${menuFailure})`
		)
		assert.equal(
			el(registry, ".cart").style.display,
			"none",
			"the order panel must be hidden when the menu is unavailable"
		)
	}
})

console.log(passed + " test(s) passed")
