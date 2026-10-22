import { useEffect, useLayoutEffect } from "react";
import Login from "./pages/Login";
import Menu from "./pages/Menu";
import Dashboard from "./pages/Dashboard";
import Admin from "./pages/Admin";
import Kds from "./pages/Kds";
import Orders from "./pages/Orders";
import Platform from "./pages/Platform";
import { guardRedirect, resolveView, titleForView, type ViewResolution } from "./view";
import {
  getSessionRestaurantSlug,
  hasUsableSession,
  sessionRole,
} from "./pages/dashboard/auth";

export default function App() {
  const resolution: ViewResolution = resolveView(window.location.pathname);
  const view = resolution.view;

  useEffect(() => {
    document.title = titleForView(view);
  }, [view]);

  // Guard de vistas por rol (odd/tasks/urls-por-slug.md, S4/S6): la decisión
  // vive en guardRedirect (src/view.ts, función pura y testeada); acá sólo
  // se aplica y se navega por asignación de href, el mismo mecanismo que el
  // post-login del Login.
  useLayoutEffect(() => {
    const target = guardRedirect(resolution, {
      role: sessionRole(),
      slug: getSessionRestaurantSlug(),
      hasSession: hasUsableSession(),
    });
    if (target) window.location.href = target;
  }, [view, resolution.legacy, resolution.slug]);

  if (view === "menu") return <Menu slug={resolution.slug} />;
  if (view === "dashboard") return <Dashboard />;
  if (view === "admin") return <Admin />;
  if (view === "kds") return <Kds />;
  if (view === "orders") return <Orders />;
  if (view === "platform") return <Platform />;
  return <Login />;
}
