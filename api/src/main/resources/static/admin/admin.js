const API = "http://localhost:8080/api";
let currentView = "dashboard";
const businessId = 1; // TODO: obtener dinámico

function goTo(view) {
    currentView = view;
    document.getElementById("view-title").innerText = view.toUpperCase();

    if (view === "products") loadProducts();
    if (view === "categories") loadCategories();
    if (view === "combos") loadCombos();
    if (view === "dashboard") loadDashboard();
}

function logout() {
    localStorage.clear();
    window.location.href = "/login.html";
}

/* ================= PRODUCTS ================= */

async function loadProducts() {
    const res = await fetch(`${API}/products?businessId=${businessId}`);
    const data = await res.json();

    let html = `<table class="table">
    <tr><th>Nombre</th><th>Precio</th><th>Stock</th><th>Estado</th><th></th></tr>`;

    data.forEach(p => {
        html += `
        <tr>
            <td>${p.name}</td>
            <td>$${p.price}</td>
            <td>${p.stock ?? "∞"}</td>
            <td><span class="badge ${p.active ? "active" : "inactive"}">
                ${p.active ? "Activo" : "Inactivo"}
            </span></td>
            <td>
                <button onclick="toggleProduct(${p.id})">ON/OFF</button>
                <button onclick="deleteProduct(${p.id})">🗑</button>
            </td>
        </tr>`;
    });

    html += `</table>`;
    document.getElementById("content").innerHTML = html;
}

async function toggleProduct(id) {
    await fetch(`${API}/products/${id}/activar?activo=false`, { method: "PUT" });
    loadProducts();
}

async function deleteProduct(id) {
    await fetch(`${API}/products/${id}`, { method: "DELETE" });
    loadProducts();
}

/* ================= CATEGORIES ================= */

async function loadCategories() {

	try {
		const token = localStorage.getItem("token")
		const response = await fetch(`${API}/categorias`, {
			headers: {
				"Authorization": "Bearer " + token
			}
		})

		if (response.status === 404) {
			document.body.innerHTML = "<h1>Categorias no encontradas</h1>"
			return
		}
		if (!response.ok) throw new Error("Error cargando categorias")

		const data = await response.json()

		let html = `<table class="table">
		   <tr><th>Nombre</th><th>Estado</th></tr>`;

		data.forEach(c => {
			html += `
		       <tr>
		           <td>${c.name}</td>
		           <td>${c.active ? "Activo" : "Inactivo"}</td>
		       </tr>`;
		});

		html += `</table>`;
		document.getElementById("content").innerHTML = html;
	} catch (e) {
		console.error("Error cargando categorias", e)
	}

}


/* ================= COMBOS ================= */

async function loadCombos() {
    const res = await fetch(`${API}/combos`);
    const data = await res.json();

    let html = `<table class="table">
    <tr><th>Nombre</th><th>Precio</th></tr>`;

    data.forEach(c => {
        html += `
        <tr>
            <td>${c.name}</td>
            <td>$${c.price}</td>
        </tr>`;
    });

    html += `</table>`;
    document.getElementById("content").innerHTML = html;
}

/* ================= DASHBOARD ================= */

function loadDashboard() {
    document.getElementById("content").innerHTML = `
    <div class="card">
        <h2>📊 Dashboard</h2>
        <p>Aquí irá tu dashboard existente.</p>
        <!-- TODO: integrar métricas reales -->
    </div>`;
}

/* ================= INIT ================= */

goTo("dashboard");