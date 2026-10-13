import Login from "./pages/Login";
import Menu from "./pages/Menu";
import Dashboard from "./pages/Dashboard";
import Admin from "./pages/Admin";
import { getView } from "./view";

export default function App() {
  const view = getView();
  if (view === "menu") return <Menu />;
  if (view === "dashboard") return <Dashboard />;
  if (view === "admin") return <Admin />;
  return <Login />;
}
