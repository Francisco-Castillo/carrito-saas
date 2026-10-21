import { useEffect } from "react";
import Login from "./pages/Login";
import Menu from "./pages/Menu";
import Dashboard from "./pages/Dashboard";
import Admin from "./pages/Admin";
import Kds from "./pages/Kds";
import Orders from "./pages/Orders";
import Platform from "./pages/Platform";
import { getView, titleForView } from "./view";

export default function App() {
  const view = getView();

  useEffect(() => {
    document.title = titleForView(view);
  }, [view]);

  if (view === "menu") return <Menu />;
  if (view === "dashboard") return <Dashboard />;
  if (view === "admin") return <Admin />;
  if (view === "kds") return <Kds />;
  if (view === "orders") return <Orders />;
  if (view === "platform") return <Platform />;
  return <Login />;
}
