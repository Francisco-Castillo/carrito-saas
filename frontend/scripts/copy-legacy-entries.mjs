#!/usr/bin/env node
/**
 * Post-build step for the Spring Boot static classpath.
 *
 * Vite emits static/index.html; Spring serves the legacy entry paths
 * (menu/index.html, login/login.html, dashboard/index.html, kds/index.html,
 * admin/admin.html) that QR codes and the app's own redirects still use.
 *
 * Because Vite emits absolute asset URLs (base: "/" → /assets/<hash>...),
 * copying index.html into subdirectories resolves the exact same bundle, so a
 * plain byte copy is enough — no rewriting needed.
 *
 * Stale hashed bundles are swept first: the build keeps `emptyOutDir: false`
 * (the classpath directory is shared with the rest of the backend resources),
 * so without this sweep every rebuild would leave the previous hashes behind
 * forever. Only assets the freshly built shell does not reference are removed.
 */
import { copyFile, mkdir, readdir, readFile, rm } from "node:fs/promises";
import { dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";

const scriptDir = dirname(fileURLToPath(import.meta.url));
const staticDir = resolve(scriptDir, "..", "..", "api", "src", "main", "resources", "static");

const LEGACY_ENTRIES = [
  "menu/index.html",
  "login/login.html",
  "dashboard/index.html",
  "kds/index.html",
  "admin/admin.html",
  "pedidos/index.html",
];

const source = join(staticDir, "index.html");
const shell = await readFile(source, "utf8");

/** Names under /assets/ that the built shell actually references. */
const referenced = new Set(
  [...shell.matchAll(/\/assets\/([^"'\s)]+)/g)].map((match) => match[1]),
);

const assetsDir = join(staticDir, "assets");
const present = await readdir(assetsDir, { withFileTypes: true }).catch(() => []);
for (const item of present) {
  if (referenced.has(item.name)) continue;
  await rm(join(assetsDir, item.name), { recursive: true, force: true });
  console.log(`removed stale static/assets/${item.name}`);
}

// Guard del efecto vidrio: el minificador de CSS conserva la ÚLTIMA de dos
// declaraciones equivalentes, así que cuando cada regla traía
// `backdrop-filter` y después `-webkit-backdrop-filter`, el bundle salía sólo
// con la prefijada — que Chrome y Firefox ignoran — y TODA la app quedaba sin
// blur sin que nada fallara. El orden ya está corregido en las fuentes; esto
// convierte una regresión silenciosa en un build roto.
const cssFile = [...referenced].find((name) => name.endsWith(".css"));
if (!cssFile) {
  throw new Error("no se encontró el CSS construido en el shell");
}
const builtCss = await readFile(join(assetsDir, cssFile), "utf8");
const pairs = [...builtCss.matchAll(/(-?)(-webkit-)?backdrop-filter:/g)];
const prefixed = pairs.filter(([, , webkit]) => Boolean(webkit)).length;
const unprefixed = pairs.length - prefixed;
if (unprefixed < prefixed || unprefixed === 0) {
  throw new Error(
    `el CSS construido tiene ${unprefixed} declaraciones 'backdrop-filter' sin prefijo y ` +
      `${prefixed} con prefijo: el navegador ignora las prefijadas, así que ese vidrio no se ` +
      "verá. Revisá el orden de las declaraciones -webkit-* en las fuentes (la estándar debe quedar última).",
  );
}
console.log(`glass guard ok: ${unprefixed} backdrop-filter sin prefijo, ${prefixed} prefijadas`);

for (const entry of LEGACY_ENTRIES) {
  const target = join(staticDir, entry);
  await mkdir(dirname(target), { recursive: true });
  await copyFile(source, target);
  console.log(`copied static/index.html → static/${entry}`);
}
