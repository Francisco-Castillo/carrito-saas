# React + TypeScript + Vite

This template provides a minimal setup to get React working in Vite with HMR and some Oxlint rules.

Currently, two official plugins are available:

- [@vitejs/plugin-react](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react) uses [Oxc](https://oxc.rs)
- [@vitejs/plugin-react-swc](https://github.com/vitejs/vite-plugin-react/blob/main/packages/plugin-react-swc) uses [SWC](https://swc.rs/)

## React Compiler

The React Compiler is not enabled on this template because of its impact on dev & build performances. To add it, see [this documentation](https://react.dev/learn/react-compiler/installation).

## Expanding the Oxlint configuration

If you are developing a production application, we recommend enabling type-aware lint rules by installing `oxlint-tsgolint` and editing `.oxlintrc.json`:

```json
{
  "$schema": "./node_modules/oxlint/configuration_schema.json",
  "plugins": ["react", "typescript", "oxc"],
  "options": {
    "typeAware": true
  },
  "rules": {
    "react/rules-of-hooks": "error",
    "react/only-export-components": ["warn", { "allowConstantExport": true }]
  }
}
```

See the [Oxlint rules documentation](https://oxc.rs/docs/guide/usage/linter/rules) for the full list of rules and categories.

## The SPA bundle the backend tests need

`npm run build:spring` writes the built shell into `api/src/main/resources/static/`,
which the Spring app serves. **That directory is gitignored except
`favicon.ico`**: a fresh clone has no shell, no assets and no `admin/` entry.

Consequences worth knowing before they bite:

- **A fresh clone cannot run `mvn verify` until the bundle exists.**
  `SpaRoutingHttpTests` guards the contract of the SPA entry paths, so it
  refuses to run without the shell instead of silently skipping.
- **The one-step way, and what CI runs:**
  `mvn -B --no-transfer-progress -Pfrontend verify`. The `frontend` profile in
  `api/pom.xml` runs `npm ci` and `npm run build:spring` for you during
  `generate-resources`.
- **By hand** you need both steps, in this order:
  `npm ci && npm run build:spring` (a fresh checkout has no `node_modules`).

Why it fails instead of skipping: JUnit counts a disabled container as one
placeholder per *method*, so the class used to report "4 skipped" while losing
21 executions — 1 `@Test` plus 20 `@ParameterizedTest` cases that were never
expanded. The suite stayed green covering less than it claimed, and that is the
failure mode this guard exists to prevent (`odd/tasks/cross-tenant-followups.md`,
L6 and L7, has the measurements).
