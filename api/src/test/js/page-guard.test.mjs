/**
 * Node-based contract tests for the protected page guards and the login
 * redirect chain.
 *
 * The backend has no JS test runner, so this file runs outside Maven:
 *
 *   node api/src/test/js/page-guard.test.mjs
 *
 * It extracts the real inline guard <script> from kds/index.html and
 * dashboard/index.html and executes the real login/login.js inside vm
 * sandboxes, pinning the access contract:
 *
 *   - A page without a token must land on /login/login.html, keeping the
 *     `restaurant` slug so the login can bind the session to the right
 *     restaurant, and must stop there (nothing else may run or throw).
 *   - A page with a token must not redirect and must not inspect the token:
 *     role enforcement is the server's job (403 on the API), and a client-side
 *     role gate crashes on tokens without a `roles` claim.
 *   - After login, every role must land on its own page with the restaurant
 *     slug: OWNER -> /dashboard, KITCHEN -> /kds, ADMIN -> /admin.
 *
 * The kds guard used to send visitors to a nonexistent /login.html and both
 * guards kept executing after the redirect decision, throwing TypeError on
 * `token.split` for anonymous visits and on `roles.includes` for tokens
 * without a roles claim. This file exists so none of that can silently
 * happen again.
 *
 * Hardening (AC10-AC13): the guard must never throw and must never leave the
 * page session-less. A failing `localStorage.getItem` (storage disabled), a
 * missing `URLSearchParams`, and dead token strings ("null", "undefined",
 * empty, whitespace-only) must all still end in a redirect to the login
 * page, while a real JWT-shaped token must never redirect.
 */
import assert from "node:assert/strict"
import { readFileSync } from "node:fs"
import vm from "node:vm"

const KDS_HTML = readFileSync(new URL("../../main/resources/static/kds/index.html", import.meta.url), "utf8")
const DASHBOARD_HTML = readFileSync(new URL("../../main/resources/static/dashboard/index.html", import.meta.url), "utf8")
const LOGIN_JS = readFileSync(new URL("../../main/resources/static/login/login.js", import.meta.url), "utf8")

const LOGIN_TARGET = "/login/login.html"
const RESTAURANT_SLUG = "demo-pizzeria"

// The guard lives in the first attribute-less <script> block of the page head.
function extractGuardScript(html, label) {
	const match = html.match(/<script>([\s\S]*?)<\/script>/)
	if (!match) {
		throw new Error(`cannot find the inline guard <script> block in ${label}: the page guard contract cannot be tested`)
	}
	return match[1]
}

function makeLocation({ search = "", pathname = "" } = {}) {
	const location = {
		href: "",
		search,
		pathname,
		replace(target) {
			location.href = target
		},
	}
	return location
}

function runPageGuard({ html, label, token, search, pathname, failGetItem = false, withoutURLSearchParams = false }) {
	const location = makeLocation({ search, pathname })
	const sandbox = {
		console,
		atob: (value) => Buffer.from(value, "base64").toString("binary"),
		localStorage: {
			getItem: (key) => {
				if (failGetItem) {
					throw new Error("localStorage is blocked (storage disabled or private mode)")
				}
				return key === "token" ? token : null
			},
			setItem() {},
			removeItem() {},
		},
		window: { location },
	}
	if (!withoutURLSearchParams) {
		sandbox.URLSearchParams = URLSearchParams
	}
	let thrown
	try {
		vm.runInNewContext(extractGuardScript(html, label), sandbox)
	} catch (error) {
		thrown = error
	}
	return { location, thrown }
}

// A real-looking JWT whose payload carries no `roles` claim: role enforcement
// belongs to the server, so the page guard must accept it untouched.
function jwtWithoutRoles() {
	const encode = (value) => Buffer.from(JSON.stringify(value)).toString("base64url")
	const header = encode({ alg: "HS256", typ: "JWT" })
	const payload = encode({ sub: "demo", businessId: 1, iat: 1700000000, exp: 1700003600 })
	return `${header}.${payload}.signature`
}

async function settle(rounds = 50) {
	for (let i = 0; i < rounds; i++) {
		await new Promise((resolve) => setImmediate(resolve))
	}
}

async function runLoginPage({ role }) {
	const registry = new Map()
	const store = new Map()
	const location = makeLocation({ search: `?restaurant=${RESTAURANT_SLUG}`, pathname: "/login/login.html" })

	function element(id) {
		if (!registry.has(id)) {
			registry.set(id, {
				id,
				innerText: "",
				value: "",
				checked: false,
				disabled: false,
				style: { display: "" },
				querySelector: (selector) => element(id + " " + selector),
				addEventListener: (type, handler) => {
					if (type === "submit") registry.set("submitHandler", handler)
				},
			})
		}
		return registry.get(id)
	}

	const okJson = (data) => ({ ok: true, status: 200, json: async () => data })

	const sandbox = {
		URLSearchParams,
		console,
		localStorage: {
			getItem: (k) => (store.has(k) ? store.get(k) : null),
			setItem: (k, v) => store.set(k, String(v)),
			removeItem: (k) => store.delete(k),
		},
		fetch: async (url) => {
			const u = String(url)
			if (u.startsWith("/api/restaurants/slug/")) {
				return okJson({ name: "Demo Pizzeria" })
			}
			if (u === "/api/auth/login") {
				return okJson({
					token: "header.payload.signature",
					businessId: 1,
					role,
					username: "demo",
					expiresAt: "2030-01-01T00:00:00Z",
					restaurantSlug: RESTAURANT_SLUG,
				})
			}
			throw new Error("unexpected fetch: " + u)
		},
		document: { getElementById: (id) => element(id) },
		window: { location },
	}

	vm.runInNewContext(LOGIN_JS, sandbox)
	await settle()

	const handler = registry.get("submitHandler")
	assert.ok(handler, "login.js must register a submit handler on #loginForm")
	element("username").value = "demo"
	element("password").value = "secret"
	await handler({ preventDefault() {} })
	await settle()

	return { location }
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

const PAGES = [
	{ label: "kds/index.html", html: KDS_HTML, pathname: "/kds/index.html" },
	{ label: "dashboard/index.html", html: DASHBOARD_HTML, pathname: "/dashboard/index.html" },
]

for (const page of PAGES) {
	await test(`${page.label}: no token and a restaurant slug lands on ${LOGIN_TARGET} with the slug and stops`, () => {
		const { location, thrown } = runPageGuard({ ...page, token: null, search: `?restaurant=${RESTAURANT_SLUG}` })
		assert.equal(
			location.href,
			`${LOGIN_TARGET}?restaurant=${RESTAURANT_SLUG}`,
			"an anonymous visitor with a restaurant slug must land on the login page keeping the slug"
		)
		assert.ifError(thrown)
	})

	await test(`${page.label}: no token and no slug lands on the bare ${LOGIN_TARGET} and stops`, () => {
		const { location, thrown } = runPageGuard({ ...page, token: null, search: "" })
		assert.equal(
			location.href,
			LOGIN_TARGET,
			"an anonymous visitor without a slug must land on the bare login page"
		)
		assert.ifError(thrown)
	})

	await test(`${page.label}: a token without a roles claim is accepted without redirect or error`, () => {
		const { location, thrown } = runPageGuard({ ...page, token: jwtWithoutRoles(), search: `?restaurant=${RESTAURANT_SLUG}` })
		assert.equal(location.href, "", "a present token must not trigger any redirect")
		assert.ifError(thrown)
	})

	// AC10: storage blocked -> the guard must still redirect with the slug and
	// must not let the exception escape (no dead screen).
	await test(`${page.label}: a failing localStorage.getItem still redirects to ${LOGIN_TARGET} with the slug and stops (AC10)`, () => {
		const { location, thrown } = runPageGuard({ ...page, token: null, search: `?restaurant=${RESTAURANT_SLUG}`, failGetItem: true })
		assert.equal(
			location.href,
			`${LOGIN_TARGET}?restaurant=${RESTAURANT_SLUG}`,
			"blocked storage must still end in a redirect that keeps the restaurant slug"
		)
		assert.ifError(thrown)
	})

	// AC11: tokens that are not a usable session (literal "null"/"undefined",
	// empty, whitespace-only, and the same dead literals padded with whitespace)
	// must be treated as no session.
	await test(`${page.label}: dead token strings land on ${LOGIN_TARGET} with the slug (AC11)`, () => {
		for (const dead of ["null", "undefined", "", "   ", " null ", "null\n", "\tnull\t", " undefined "]) {
			const { location, thrown } = runPageGuard({ ...page, token: dead, search: `?restaurant=${RESTAURANT_SLUG}` })
			assert.equal(
				location.href,
				`${LOGIN_TARGET}?restaurant=${RESTAURANT_SLUG}`,
				`the unusable token ${JSON.stringify(dead)} must be treated as no session and redirect with the slug`
			)
			assert.ifError(thrown)
		}
	})

	// AC12: a missing URLSearchParams must not kill the guard: it redirects to
	// the bare login page because the slug could not be read.
	await test(`${page.label}: a missing URLSearchParams still lands on the bare ${LOGIN_TARGET} (AC12)`, () => {
		const { location, thrown } = runPageGuard({ ...page, token: null, search: `?restaurant=${RESTAURANT_SLUG}`, withoutURLSearchParams: true })
		assert.equal(
			location.href,
			LOGIN_TARGET,
			"without URLSearchParams the guard must still redirect to the bare login page"
		)
		assert.ifError(thrown)
	})

	// AC13: no regression of the acceptance of a real JWT-shaped session
	// through the hardening checks (trim + dead-string filter).
	await test(`${page.label}: a JWT-shaped token without a roles claim still does not redirect (AC13)`, () => {
		const { location, thrown } = runPageGuard({ ...page, token: jwtWithoutRoles(), search: `?restaurant=${RESTAURANT_SLUG}` })
		assert.equal(location.href, "", "a real JWT-shaped session must not trigger any redirect")
		assert.ifError(thrown)
	})
}

await test(`login.js: OWNER still lands on /dashboard/index.html?restaurant=${RESTAURANT_SLUG}`, async () => {
	const { location } = await runLoginPage({ role: "OWNER" })
	assert.equal(
		location.href,
		`/dashboard/index.html?restaurant=${RESTAURANT_SLUG}`,
		"OWNER must land on the dashboard page with the restaurant slug"
	)
})

await test(`login.js: KITCHEN still lands on /kds/index.html?restaurant=${RESTAURANT_SLUG}`, async () => {
	const { location } = await runLoginPage({ role: "KITCHEN" })
	assert.equal(
		location.href,
		`/kds/index.html?restaurant=${RESTAURANT_SLUG}`,
		"KITCHEN must land on the kds page with the restaurant slug"
	)
})

await test(`login.js: ADMIN lands on /admin/admin.html?restaurant=${RESTAURANT_SLUG}`, async () => {
	const { location } = await runLoginPage({ role: "ADMIN" })
	assert.equal(
		location.href,
		`/admin/admin.html?restaurant=${RESTAURANT_SLUG}`,
		"ADMIN must land on the admin page with the restaurant slug"
	)
})

console.log(passed + " test(s) passed")
