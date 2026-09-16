/**
 * Guard: no static frontend asset may hardcode the application's own origin.
 *
 * Node-based guard, run outside Maven:
 *
 *   node api/src/test/js/frontend-origin.test.mjs
 *
 * It scans every .js and .html file under api/src/main/resources/static/
 * recursively and fails when any of them contains "localhost" or "127.0.0.1".
 *
 * Why: the application's origin (scheme, host, port) is deployment data, and
 * duplicating it in a client makes the client lie about where the backend
 * lives. The server port has already flipped twice — 9090 -> 8080 -> 9090
 * (see git history) — and admin.js kept `http://localhost:8080/api` through
 * both changes, so every admin API call went to the wrong port and the admin
 * panel was broken. An absolute base is the wrong shape, not just the wrong
 * value: it also breaks the LAN-IP pilot (http://192.168.1.5:9090/...) and
 * the real public origin. A relative "/api" base works everywhere, which is
 * what every other static client already uses.
 *
 * External CDNs referenced through absolute `https://` URLs inside .html
 * files are third-party origins, not the application's own origin, and are
 * deliberately not covered by this guard.
 */
import { readdirSync, readFileSync } from "node:fs"
import { extname, join, relative } from "node:path"
import { fileURLToPath } from "node:url"

const STATIC_ROOT = fileURLToPath(new URL("../../main/resources/static/", import.meta.url))
const REPO_ROOT = fileURLToPath(new URL("../../../../", import.meta.url))

const OFFENDING_ORIGIN = /localhost|127\.0\.0\.1/i

function collectFiles(dir) {
	const files = []
	for (const entry of readdirSync(dir, { withFileTypes: true }).sort((a, b) =>
		a.name.localeCompare(b.name)
	)) {
		const path = join(dir, entry.name)
		if (entry.isDirectory()) {
			files.push(...collectFiles(path))
		} else if (entry.isFile() && [".js", ".html"].includes(extname(entry.name).toLowerCase())) {
			files.push(path)
		}
	}
	return files
}

let passed = 0
let failed = 0

function check(name, problems) {
	if (problems.length === 0) {
		passed++
		console.log("PASS " + name)
	} else {
		failed++
		console.error("FAIL " + name)
		for (const problem of problems) {
			console.error("  " + problem)
		}
	}
}

try {
	const files = collectFiles(STATIC_ROOT)

	check(
		"static asset scan found .js/.html files to guard",
		files.length > 0
			? []
			: [
					`no .js or .html files found under ${relative(REPO_ROOT, STATIC_ROOT)}; ` +
						"the guard must never pass silently on an empty scan",
				]
	)

	for (const file of files) {
		const displayPath = relative(REPO_ROOT, file)
		const problems = []
		readFileSync(file, "utf8")
			.split(/\r?\n/)
			.forEach((line, index) => {
				if (OFFENDING_ORIGIN.test(line)) {
					problems.push(`${displayPath}:${index + 1}: ${line.trim()}`)
				}
			})
		check(`no hardcoded application origin in ${displayPath}`, problems)
	}

	console.log(`scan: ${files.length} file(s) under ${relative(REPO_ROOT, STATIC_ROOT)}`)
} catch (err) {
	failed++
	console.error("FAIL static asset scan")
	console.error("  " + (err && err.message ? err.message : err))
}

console.log(passed + " test(s) passed")

if (failed > 0) {
	process.exit(1)
}
