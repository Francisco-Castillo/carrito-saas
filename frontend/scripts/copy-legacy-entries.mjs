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
 */
import { copyFile, mkdir } from "node:fs/promises";
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
];

const source = join(staticDir, "index.html");

for (const entry of LEGACY_ENTRIES) {
  const target = join(staticDir, entry);
  await mkdir(dirname(target), { recursive: true });
  await copyFile(source, target);
  console.log(`copied static/index.html → static/${entry}`);
}
