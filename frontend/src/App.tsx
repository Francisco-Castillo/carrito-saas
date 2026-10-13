import Login from "./pages/Login";
import Menu from "./pages/Menu";
import Dashboard from "./pages/Dashboard";
import { getView } from "./view";

export default function App() {
  const view = getView();
  return view === "menu" ? <Menu /> : view === "dashboard" ? <Dashboard /> : <Login />;
}
