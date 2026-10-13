import Login from "./pages/Login";
import Menu from "./pages/Menu";
import { getView } from "./view";

export default function App() {
  const view = getView();
  return view === "menu" ? <Menu /> : <Login />;
}
