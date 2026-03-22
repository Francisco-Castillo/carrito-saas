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

/* ================= CATEGORIES ================= */

/* ================= CATEGORIES ================= */

/* ================= CATEGORIES ================= */
/* ================= FUNCIONES TOAST ================= */
function showSuccessToast(message) {
    const toast = document.createElement("div");
    toast.className = "toast-success";
    toast.innerText = message;
    document.body.appendChild(toast);

    setTimeout(() => toast.classList.add("show"), 50);

    setTimeout(() => {
        toast.classList.remove("show");
        setTimeout(() => document.body.removeChild(toast), 500);
    }, 2500);
}

function showToastModal(message) {
    return new Promise(resolve => {
        const backdrop = document.createElement("div");
        backdrop.className = "toast-modal-backdrop";

        const modal = document.createElement("div");
        modal.className = "toast-modal";
        modal.innerHTML = `<p>${message}</p>`;

        const btnConfirm = document.createElement("button");
        btnConfirm.innerText = "Confirmar";
        modal.appendChild(btnConfirm);

        backdrop.appendChild(modal);
        document.body.appendChild(backdrop);

        // Confirmar clic
        btnConfirm.addEventListener("click", () => {
            modal.style.animation = "modal-out 0.3s forwards";
            setTimeout(() => document.body.removeChild(backdrop), 300);
            document.removeEventListener("keydown", escHandler); // quitar listener
            resolve(true);
        });

        // Clic fuera del modal
        backdrop.addEventListener("click", (e) => {
            if (e.target === backdrop) {
                modal.style.animation = "modal-out 0.3s forwards";
                setTimeout(() => document.body.removeChild(backdrop), 300);
                document.removeEventListener("keydown", escHandler);
                resolve(false);
            }
        });

        // ESCAPE
        const escHandler = (e) => {
            if (e.key === "Escape") {
                modal.style.animation = "modal-out 0.3s forwards";
                setTimeout(() => document.body.removeChild(backdrop), 300);
                document.removeEventListener("keydown", escHandler);
                resolve(false);
            }
        };

        document.addEventListener("keydown", escHandler);
    });
}

/* ================= CATEGORÍAS PREMIUM CON DRAG & DROP ================= */
async function loadCategories() {
    try {
        const token = localStorage.getItem("token");
        const response = await fetch(`${API}/categorias`, {
            headers: { "Authorization": "Bearer " + token }
        });

        if (response.status === 404) {
            document.body.innerHTML = "<h1>Categorías no encontradas</h1>";
            return;
        }
        if (!response.ok) throw new Error("Error cargando categorías");

        const data = await response.json();

        let html = `<table class="table">
            <thead>
                <tr>
                    <th>Nombre</th>
                    <th>Estado</th>
                    <th>Acciones</th>
                </tr>
            </thead>
            <tbody>`;

        data.forEach(c => {
            html += `
            <tr data-id="${c.id}">
                <td>
                    <span class="drag-handle">≡</span>
                    <span class="name-text">${c.name}</span>
                    <input class="inline-input" type="text" value="${c.name}" data-original="${c.name}">
                </td>
                <td><span class="badge ${c.active ? "active" : "inactive"}">
                    ${c.active ? "Activo" : "Inactivo"}
                </span></td>
                <td>
                    <button class="toggle-btn ${c.active ? "deactivate" : "activate"}">
                        ${c.active ? "Desactivar" : "Activar"}
                    </button>
                </td>
            </tr>`;
        });

        html += `</tbody>
            <tfoot>
                <tr>
                    <td colspan="3">Total de registros: ${data.length}</td>
                </tr>
            </tfoot>
        </table>`;

        document.getElementById("content").innerHTML = html;

        const tbody = document.querySelector(".table tbody");

        // ================= DRAG & DROP =================
        Sortable.create(tbody, {
            handle: ".drag-handle",
            animation: 150,
            onEnd: async () => {
                const ids = Array.from(tbody.querySelectorAll("tr")).map(tr => parseInt(tr.dataset.id));
                try {
                    const res = await fetch(`${API}/categorias/orden`, {
                        method: "PUT",
                        headers: {
                            "Content-Type": "application/json",
                            "Authorization": "Bearer " + localStorage.getItem("token")
                        },
                        body: JSON.stringify(ids)
                    });
                    if (!res.ok) throw new Error("Error actualizando el orden");
                    showSuccessToast("Orden de categorías actualizado");
                } catch (e) {
                    console.error(e);
                    showSuccessToast("Error al actualizar el orden");
                }
            }
        });

        // ================= BOTONES DE TOGGLE =================
        document.querySelectorAll(".toggle-btn").forEach(btn => {
            btn.addEventListener("click", async (e) => {
                const row = e.target.closest("tr");
                const id = row.dataset.id;
                const activar = e.target.classList.contains("activate");

                const confirmModal = await showToastModal(`¿Seguro que deseas ${activar ? "activar" : "desactivar"} esta categoría?`);
                if (!confirmModal) return;

                await fetch(`${API}/categorias/${id}/activar?activo=${activar}`, { method: "PUT" });
                loadCategories();
                showSuccessToast(`Categoría ${activar ? "activada" : "desactivada"} con éxito`);
            });
        });

        // ================= EDICIÓN INLINE =================
        document.querySelectorAll(".name-text").forEach(span => {
            span.addEventListener("click", () => {
                const input = span.nextElementSibling;
                span.style.display = "none";
                input.style.display = "block";
                input.focus();
                input.select();
            });
        });

        document.querySelectorAll(".inline-input").forEach(input => {
            const span = input.previousElementSibling;

            input.addEventListener("keydown", async (e) => {
                const row = input.closest("tr");
                const id = row.dataset.id;

                if (e.key === "Enter") {
                    const newName = input.value.trim();
                    if (!newName) return alert("El nombre no puede estar vacío");

                    const confirmModal = await showToastModal(`¿Guardar nuevo nombre "${newName}"?`);
                    if (!confirmModal) {
                        input.value = input.dataset.original;
                        input.style.display = "none";
                        span.style.display = "inline";
                        return;
                    }

                    await fetch(`${API}/categorias/${id}`, {
                        method: "PUT",
                        headers: {
                            "Content-Type": "application/json",
                            "Authorization": "Bearer " + localStorage.getItem("token")
                        },
                        body: JSON.stringify({ name: newName })
                    });

                    loadCategories();
                    showSuccessToast(`Nombre actualizado correctamente`);
                }

                if (e.key === "Escape") {
                    input.value = input.dataset.original;
                    input.style.display = "none";
                    span.style.display = "inline";
                }
            });

            input.addEventListener("blur", () => {
                input.value = input.dataset.original;
                input.style.display = "none";
                span.style.display = "inline";
            });
        });

    } catch (e) {
        console.error("Error cargando categorías", e);
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