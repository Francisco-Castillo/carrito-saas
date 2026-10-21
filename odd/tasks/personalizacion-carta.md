# Personalización de la carta del menú

Sección "Personalización" en el panel para que el dueño ajuste el look de la
carta: color de marca (se muda desde Datos del local), tipografía (fuentes
curadas de Google Fonts con lista blanca en el backend) y, en un segundo PR,
sugerencias de colores extraídas del logo subido. Entrega en dos PRs
encadenados según decisión del dueño.

## Specs

- S1: "podemos hacer una seccion para la personalizacion del menu" → nueva
  pestaña "Personalización" dentro de Local (junto a "Datos del local" y
  "Horarios"); el control de color de la carta se MUDA ahí y queda fuera de
  DatosSection.
- S2: "tambien que pueda cambiar la fuente" → lista cerrada de ~8 fuentes de
  Google Fonts elegidas para carta (decisión: "Curadas de Google"); el dueño
  elige en el panel; el backend valida contra lista blanca (400 con mensaje
  en español para cualquier otra cosa); la carta carga la fuente on demand y
  la aplica sin tocar las demás vistas. Vacío = Inter (tema actual).
- S3: "detecte los colores del logo y para que el admin pueda usarlos" →
  sugerencias al hacer clic (decisión: "Sugerencias al hacer clic"): el panel
  extrae 5-6 colores dominantes del logo vía canvas y los ofrece como
  swatches; el dueño elige uno y confirma guardando. Sin persistencia nueva
  (decisión explícita contra "Extraer y persistir").
- S4: "Dos PRs encadenados" → PR 1: sección Personalización + fuentes
  (full-stack). PR 2: extracción de colores del logo (solo frontend, encima
  del 1).

## Tasks

- T1 (S2, backend): migración documentada `docs/migrations/` + columna
  `menu_font` en la entidad. Commit: 5717dbf (con T2).
- T2 (S2, backend): `Business.menuFont`, `BusinessProfileDTO.menuFont`,
  `BusinessDTO.menuFont`, mapper, whitelist + validación en
  `BusinessProfileServiceImpl`, tests backend espejando PrimaryColorTests.
  Commit: 5717dbf.
- T3 (S1, admin): pestaña "Personalización" en LocalSection; color se muda
  desde DatosSection; selector de fuente con vista previa; tests frontend.
  Commit: 582a71f.
- T4 (S2, carta): aplicar la fuente elegida (`--menu-font` en :root, carga
  on demand del link de Google Fonts, fallback Inter) + tests. Commit:
  f425243.
- T5 (S3, PR 2): extracción de dominantes del logo vía canvas + swatches
  sugeridos + tests. Commit: —

## Log

- L1: "podemos hacer una seccion para la personalizacion del menu ? aparte de
  lo que hicimos me gustaria tambien agregar que detecte los colores del logo
  y para que el admin pueda usarlos y tambien que pueda cambiar la fuente.
  Que te parece ?"
- L2: decisiones del dueño (cuestionario): "Pestaña en Local (recomendado)",
  "Curadas de Google (recomendado)", "Sugerencias al hacer clic
  (recomendado)", "Dos PRs encadenados (recomendado)".
