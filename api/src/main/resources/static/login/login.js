const params = new URLSearchParams(window.location.search)
const restaurantSlug = params.get("restaurant")

const label = document.getElementById("restaurantName")

// manejo elegante si falta slug
if (!restaurantSlug) {

    if (label) label.innerText = "Restaurante no especificado"

    console.error("Missing restaurant slug")

    throw new Error("Missing restaurant slug")
}

// cargar info restaurante
async function loadRestaurantInfo() {

    try {

        // cache simple (performance PRO)
        const cachedName = localStorage.getItem("restaurantName_" + restaurantSlug)

        if (cachedName) {
            label.innerText = cachedName
            return
        }

        // estado loading
        if (label) label.innerText = "Cargando..."

        const res = await fetch(`/api/restaurants/slug/${restaurantSlug}`)

        if (!res.ok) throw new Error("Restaurante no encontrado")

        const data = await res.json()

        const name = data.name || restaurantSlug

        // render
        if (label) label.innerText = name

        // guardar cache
        localStorage.setItem("restaurantName_" + restaurantSlug, name)

    } catch (e) {

        console.error("Error cargando restaurante:", e)

        // fallback elegante
        if (label) {
            label.innerText = formatSlug(restaurantSlug)
        }
    }
}


function formatSlug(slug) {
    return slug
        .replace(/-/g, " ")
        .replace(/\b\w/g, l => l.toUpperCase())
}


loadRestaurantInfo()

const btn = document.getElementById("loginBtn")
const loader = btn.querySelector(".loader")
const text = btn.querySelector(".btn-text")

function setLoading(state){
    loader.style.display = state ? "block" : "none"
    text.style.display = state ? "none" : "inline"
    btn.disabled = state
}

const remember = document.getElementById("rememberMe")

if(localStorage.getItem("savedUser")){
    document.getElementById("username").value = localStorage.getItem("savedUser")
    remember.checked = true
}

document.getElementById("loginForm").addEventListener("submit", async function(e) {

	e.preventDefault();

	const username = document.getElementById("username").value.trim();
	const password = document.getElementById("password").value.trim();
	const errorBox = document.getElementById("errorMessage");

	// obtener slug correctamente
	const params = new URLSearchParams(window.location.search);
	const restaurantSlug = params.get("restaurant");

	// limpiar error anterior
	errorBox.style.display = "none";
	errorBox.innerText = "";

	// validación básica
	if (!username || !password) {
		errorBox.innerText = "Completa todos los campos";
		errorBox.style.display = "block";
		return;
	}

	if (!restaurantSlug) {
		errorBox.innerText = "Falta el restaurante en la URL";
		errorBox.style.display = "block";
		return;
	}

	try {

		setLoading(true);

		// recordar usuario
		if (remember.checked) {
			localStorage.setItem("savedUser", username);
		} else {
			localStorage.removeItem("savedUser");
		}

		const res = await fetch("/api/auth/login", {
			method: "POST",
			headers: {
				"Content-Type": "application/json"
			},
			body: JSON.stringify({
				username,
				password,
				restaurantSlug
			})
		});

		// manejo correcto de errores HTTP
		if (!res.ok) {
			let msg = "Error de autenticación";

			try {
				const errData = await res.json();
				msg = errData.message || msg;
			} catch (_) {}

			throw new Error(msg);
		}

		const data = await res.json();

		// guardar sesión
		localStorage.setItem("token", data.token);
		localStorage.setItem("businessId", data.businessId);
		localStorage.setItem("role", data.role);
		localStorage.setItem("username", data.username);
		localStorage.setItem("expiresAt", data.expiresAt);

		const role = data.role;
		const slug = data.restaurantSlug;

		//  redirección
		if (role.includes("OWNER")) {

			window.location.href = `/dashboard/index.html?restaurant=${slug}`;

		} else if (role.includes("KITCHEN")) {

			//window.location.href = `/kds/index.html?restaurant=${slug}`;
			window.location.href = `/admin/admin.html?restaurant=${slug}`;

		} else {

			throw new Error("Rol no permitido");

		}

	} catch (err) {

		setLoading(false);

		errorBox.innerText = err.message || "Error inesperado";
		errorBox.style.display = "block";

		console.error("Login error:", err);
	}
});
