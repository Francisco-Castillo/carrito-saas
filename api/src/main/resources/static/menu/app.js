
const slug = getRestaurantSlug()

//const API_URL = `/api/restaurants/slug/${slug}/products`
const API_URL = `/api/menu/${slug}`

let restaurant = null
let WHATSAPP = null

let products = []
let combos = []
let categoriesData = []

let cart = JSON.parse(localStorage.getItem("cart")) || {}

// The link must be complete before anything loads: a truncated QR link
// (missing ?restaurant=slug) gets a readable message and a dead end,
// never a broken menu that still lets the customer send an order to
// /api/orders/menu/undefined.
if (!slug) {
	showMenuLoadError(
		"No pudimos abrir la carta: el enlace del QR está incompleto o no es válido. " +
		"Pedile al local un nuevo QR o hacé tu pedido directamente en el mostrador."
	)
} else {
	init()
}

async function init() {

	resetApp()

	try {
		await loadRestaurant()
	} catch (error) {
		// The restaurant could not be loaded (bad slug, unregistered local,
		// network failure): stop here instead of rendering a page with a
		// broken name and an empty menu.
		showMenuLoadError(
			"No pudimos cargar la carta de este local. Revisá tu conexión y volvé a escanear el QR; " +
			"si el problema continúa, avisale al local para que te dé un enlace nuevo."
		)
		return
	}

	// Any failure to load the menu (non-2xx status, network rejection, malformed
	// JSON) must show a customer-readable message and stop the boot — the same
	// contract as the restaurant-load failure above, never a silently empty menu.
	let data
	try {
		const res = await fetch(API_URL)

		if (!res.ok) {
			throw new Error("Menu request failed with HTTP " + res.status)
		}

		data = await res.json()
	} catch (error) {
		showMenuLoadError(
			"No pudimos cargar los productos de la carta. Revisá tu conexión y probá de nuevo en unos minutos; " +
			"si sigue fallando, avisale al local o hacé tu pedido directamente en el mostrador."
		)
		return
	}

	// 🔥 SAFE ASSIGN (evita errores)
	products = data.products || []
	combos = data.combos || []
	categoriesData = data.categories || []

	// 🔥 Convertimos combos en pseudo productos
	const comboProducts = mapCombosToProducts(combos)

	// 🔥 Unificamos
	products = [...products, ...comboProducts]

	renderMenu()
	renderCart()
	updateCartVisibility()
}

/**
 * Convierte combos en productos visuales
 */
function mapCombosToProducts(combos) {

	if (!combos || combos.length === 0) return []

	return combos.map(c => ({
		id: `combo-${c.id}`,
		name: c.name,
		description: (c.items || [])
			.map(i => `${i.quantity} x ${i.productName}`)
			.join(", "),
		price: c.price,
		categoryId: c.categoryId,
		categoryName: "Combos",
		imageUrl: "https://images.unsplash.com/photo-1600891964599-f61ba0e24092",
		isCombo: true,
		items: c.items || []
	}))
}

/**
 * Obtener datos del negocio
 */
async function loadRestaurant() {

	const slug = getRestaurantSlug()

	const response = await fetch(`/api/restaurants/slug/${slug}`)

	// A 404 (or any error) means this link does not point at a real local:
	// throwing stops the boot instead of rendering an unnamed restaurant.
	if (!response.ok) {
		throw new Error("Restaurant request failed with HTTP " + response.status)
	}

	restaurant = await response.json()

	// Lectura defensiva: un valor que es solo espacios cuenta como ausente
	WHATSAPP =
		typeof restaurant.whatsappNumber === "string"
			? restaurant.whatsappNumber.trim()
			: ""

	document.getElementById("restaurantName").innerText = restaurant.name

	if (restaurant.logoUrl) {
		document.getElementById("restaurantLogo").src = restaurant.logoUrl
	} else {
		document.getElementById("restaurantLogo").style.display = "none"
	}

	if (restaurant.primaryColor) {
		document.documentElement.style.setProperty(
			"--primary-color",
			restaurant.primaryColor
		)
	}
}

function groupByCategory(products) {

	const categories = {}

	products.forEach(p => {

		if (!categories[p.categoryId]) {
			categories[p.categoryId] = {
				name: p.categoryName,
				products: []
			}
		}

		categories[p.categoryId].products.push(p)
	})

	return categories
}

function getRestaurantSlug() {
	const params = new URLSearchParams(window.location.search)
	return params.get("restaurant")
}

function renderMenu() {

	const menu = document.getElementById("menu")
	const categories = groupByCategory(products)

	menu.innerHTML = ""

	Object.values(categories).forEach(cat => {

		const categoryDiv = document.createElement("div")
		categoryDiv.className = "category"

		const header = document.createElement("div")
		header.className = "category-header"
		header.textContent = cat.name

		const productsDiv = document.createElement("div")
		productsDiv.className = "category-content"

		header.onclick = () => {
			categoryDiv.classList.toggle("open")
		}

		cat.products.forEach(p => {

			const qty = cart[p.id]?.qty || 0

			const productDiv = document.createElement("div")
			productDiv.className = "product"

			productDiv.innerHTML = `

<img src="${p.imageUrl}">

<div class="product-info">

<div class="product-name">${p.name}</div>

<div>${p.description || ""}</div>

<div class="product-price">$${p.price}</div>

</div>

<div class="product-controls">

<button class="btn btn-minus" onclick="removeItem('${p.id}')">-</button>

<div class="quantity" id="qty-${p.id}">${qty}</div>

<button class="btn btn-plus" onclick="addItem('${p.id}')">+</button>

</div>

`

			productsDiv.appendChild(productDiv)
		})

		categoryDiv.appendChild(header)
		categoryDiv.appendChild(productsDiv)

		menu.appendChild(categoryDiv)
	})
}

function addItem(id) {

	const product = products.find(p => p.id == id)

	if (!product) return

	if (!cart[id]) {
		cart[id] = {
			product: product,
			qty: 0,
			isCombo: product.isCombo || false
		}
	}

	cart[id].qty++

	saveCart()
	updateQty(id)
	renderCart()
}

function removeItem(id) {

	if (!cart[id]) return

	if (cart[id].qty <= 0) return

	cart[id].qty--

	if (cart[id].qty === 0) {
		delete cart[id]
	}

	saveCart()
	updateQty(id)
	renderCart()
}

function updateQty(id) {

	const el = document.getElementById(`qty-${id}`)

	if (!el) return

	el.innerText = cart[id] ? cart[id].qty : 0
}

function saveCart() {
	localStorage.setItem("cart", JSON.stringify(cart))
}

function renderCart() {

	const items = document.getElementById("cart-items")
	const totalEl = document.getElementById("total")

	items.innerHTML = ""

	let total = 0

	Object.values(cart).forEach(item => {

		const line = document.createElement("div")
		line.innerText = `${item.qty} x ${item.product.name}`

		items.appendChild(line)

		total += item.qty * item.product.price
	})

	totalEl.textContent = total

	updateCartVisibility()
}

document.getElementById("sendOrder").onclick = async () => {

	if (Object.keys(cart).length === 0) {
		alert("Agrega productos primero")
		return
	}

	const name = document.getElementById("customerName").value
	const type = document.getElementById("orderType").value
	const address = document.getElementById("address").value
	const notes = document.getElementById("notes").value
	const payment = document.getElementById("paymentMethod").value

	if (name.trim() === "") {
		alert("Por favor ingresa tu nombre")
		return
	}

	if (type === "DELIVERY" && address.trim() === "") {
		alert("Por favor ingresa la dirección para el delivery")
		return
	}

	// El pedido entra primero a la cocina
	const items = Object.entries(cart).map(([id, item]) => {

		if (item.isCombo) {
			return { comboId: Number(id.replace("combo-", "")), quantity: item.qty }
		}

		return { productId: Number(id), quantity: item.qty }
	})

	const payload = {
		customerName: name,
		orderType: type,
		paymentMethod: payment,
		notes: notes,
		items: items
	}

	if (type === "DELIVERY") {
		payload.customerAddress = address
	}

	// Evita doble envío: un doble tap no debe crear dos pedidos
	const sendButton = document.getElementById("sendOrder")

	sendButton.disabled = true

	try {

		const response = await fetch(`/api/orders/menu/${slug}`, {
			method: "POST",
			headers: { "Content-Type": "application/json" },
			body: JSON.stringify(payload)
		})

		if (!response.ok) {
			throw new Error("El local rechazó el pedido (HTTP " + response.status + ")")
		}

		alert("¡Pedido recibido! Ya lo enviamos a la cocina.")

		resetOrderState()
		return

	} catch (error) {

		// Fallback: el pedido no se pierde, pero nunca prometemos un canal que
		// no existe. Sin número de WhatsApp configurado no hay a dónde navegar.
		if (hasWhatsappNumber()) {
			alert("No pudimos enviar tu pedido al local. Vamos a confirmarlo por WhatsApp.")
			sendOrderByWhatsApp(name, type, address, notes, payment)
		} else {
			alert(
				"No pudimos enviar tu pedido al local. " +
				"Este local no tiene un número de WhatsApp configurado para confirmarlo automáticamente. " +
				"Por favor confirmá tu pedido directamente con el local " +
				"(en el mostrador o por cualquier otro medio de contacto que tenga publicado)."
			)
		}

	} finally {
		sendButton.disabled = false
	}
}

/**
 * Customer-facing dead end: shows a readable message in the page and STOPS
 * (no menu fetch, no broken render). The cart is emptied and the order
 * panel hidden so a broken link can never submit an order.
 */
function showMenuLoadError(message) {

	cart = {}

	const menu = document.getElementById("menu")

	if (menu) {
		menu.innerHTML = ""

		const notice = document.createElement("div")
		notice.className = "menu-load-error"
		notice.textContent = message

		menu.appendChild(notice)
	}

	updateCartVisibility()
}

/**
 * ¿Hay un número de WhatsApp utilizable? Defensivo: vacío, null o solo
 * espacios cuenta como ausente.
 */
function hasWhatsappNumber() {
	return typeof WHATSAPP === "string" && WHATSAPP.trim() !== ""
}

/**
 * Fallback: confirma el pedido por WhatsApp (flujo original, sin cambios)
 */
function sendOrderByWhatsApp(name, type, address, notes, payment) {

	// Defensivo: jamás armar ni abrir un enlace wa.me sin un número válido,
	// así ningún llamador futuro puede reintroducir la pérdida silenciosa.
	if (!hasWhatsappNumber()) {
		alert(
			"No pudimos enviar tu pedido al local y este local no tiene WhatsApp configurado. " +
			"Por favor confirmá tu pedido directamente con el local."
		)
		return
	}

	let message = "Hola! Quiero hacer el siguiente pedido:%0A%0A"

	Object.values(cart).forEach(item => {
		message += `• ${item.qty} x ${item.product.name}%0A`
	})

	let total = 0
	Object.values(cart).forEach(item => {
		total += item.qty * item.product.price
	})

	message += `%0A*Total:* $${total}%0A`
	message += "----------------%0A"
	message += `*Nombre:* ${name}%0A`
	message += `*Tipo de pedido:* ${type}%0A`

	if (type === "DELIVERY") {
		message += `*Dirección:* ${address}%0A`
	}

	message += `*Forma de pago:* ${payment}%0A`

	if (notes) {
		message += `*Observaciones:* ${notes}%0A`
	}

	// Navigate instead of window.open(): this runs after an await, so the
	// click's transient activation may already have lapsed and the popup would
	// be blocked silently, losing the order. A navigation is never blocked.
	window.location.href = `https://wa.me/${WHATSAPP}?text=${message}`
}

/**
 * Estado limpio tras un pedido confirmado: carrito vacío y formulario nuevo
 */
function resetOrderState() {

	cart = {}
	localStorage.removeItem("cart")

	document.getElementById("customerName").value = ""
	document.getElementById("address").value = ""
	document.getElementById("notes").value = ""
	document.getElementById("orderType").value = "RETIRO"
	document.getElementById("addressContainer").style.display = "none"

	renderCart()
}

const orderTypeSelect = document.getElementById("orderType")
const addressContainer = document.getElementById("addressContainer")

orderTypeSelect.addEventListener("change", function () {

	if (this.value === "DELIVERY") {
		addressContainer.style.display = "block"
	} else {
		addressContainer.style.display = "none"
	}
})

function resetApp() {

	cart = {}

	document.getElementById("cart-items").innerHTML = ""
	document.getElementById("total").innerText = "0"
	document.getElementById("menu").innerHTML = ""

	document.getElementById("customerName").value = ""
	document.getElementById("address").value = ""
	document.getElementById("notes").value = ""
	document.getElementById("orderType").value = "RETIRO"
	document.getElementById("addressContainer").style.display = "none"

	updateCartVisibility()
}

function updateCartVisibility() {

	const cartPanel = document.querySelector(".cart")

	if (Object.keys(cart).length === 0) {
		cartPanel.style.display = "none"
		document.body.classList.remove("cart-visible")
	} else {
		cartPanel.style.display = "block"
		document.body.classList.add("cart-visible")
	}
}

