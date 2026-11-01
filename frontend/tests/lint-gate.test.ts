/**
 * El gate de lint es parte del contrato del repo, no una preferencia local: si
 * alguien revierte `--deny-warnings` o saca el paso del workflow, las
 * advertencias vuelven a acumularse sin que nadie lo note (era exactamente el
 * estado anterior: 36 advertencias y `npm run lint` saliendo 0).
 *
 * Estos dos asserts son baratos y no ejecutan oxlint: pinchan la config, no el
 * comportamiento del linter (que ya se probó a mano: `--deny-warnings` sale 1
 * con una advertencia, verificado con la versión fijada en el lockfile).
 */
import { readFileSync } from "node:fs";
import { join } from "node:path";
import { describe, expect, it } from "vitest";

// Mismo acceso a disco que build-artifacts.test.ts: `__dirname` en vez de
// `import.meta.url`, que bajo jsdom no es una URL de tipo file.
const PACKAGE_JSON = join(__dirname, "..", "package.json");
const CI_WORKFLOW = join(__dirname, "..", "..", ".github", "workflows", "ci.yml");

describe("lint gate", () => {
  it("el script lint niega advertencias", () => {
    const pkg = JSON.parse(readFileSync(PACKAGE_JSON, "utf8")) as { scripts: Record<string, string> };

    expect(pkg.scripts.lint, "sin --deny-warnings el gate no existe en los hechos").toContain(
      "--deny-warnings",
    );
  });

  it("el CI corre el lint del frontend", () => {
    const workflow = readFileSync(CI_WORKFLOW, "utf8");

    expect(workflow).toMatch(
      /- name: Frontend lint\n(?:.*\n)*? {8}working-directory: frontend\n {8}run: npm run lint/,
    );
  });
});
