const params = new URLSearchParams(window.location.search)

const restaurantSlug = params.get("restaurant")

if (!restaurantSlug) {

	document.body.innerHTML = "<h1>Restaurante no especificado</h1>"

	throw new Error("Missing restaurant slug")

}

const MAX_TIME = 900

let ordersMap = new Map()
let previousOrders = []


function playNewOrderSound() {

	const sound = document.getElementById("newOrderSound")

	if (sound) {

		sound.currentTime = 0
		sound.play().catch(() => { })

	}

}

function playReadySound() {

	const sound = document.getElementById("readySound")

	if (sound) {

		sound.currentTime = 0
		sound.play().catch(() => { })

	}

}


/* =========================
   CARGAR PEDIDOS
========================= */

async function loadOrders() {

	try {

		const token = localStorage.getItem("token")

		const response = await fetch(`/api/business/${restaurantSlug}/orders/active`,{
		headers:{
		"Authorization": "Bearer " + token
		}
		})

		if (response.status === 404) {

			document.body.innerHTML = "<h1>Restaurante no encontrado</h1>"
			return

		}

		if (!response.ok) {

			throw new Error("Error cargando pedidos")

		}


		const orders = await response.json()

		detectNewOrders(orders)

		syncOrders(orders)

		previousOrders = orders

	} catch (e) {

		console.error("Error cargando pedidos", e)

	}

}

/* =========================
   DETECTAR PEDIDOS NUEVOS
========================= */

function detectNewOrders(orders) {

	orders.forEach(order => {

		const exists = previousOrders.find(p => p.orderId === order.orderId)

		if (!exists) {

			playNewOrderSound()

		}

	})

}

/* =========================
   SINCRONIZAR UI
========================= */

function syncOrders(orders) {

	const newCol = document.getElementById("newOrders")
	const prepCol = document.getElementById("preparingOrders")
	const readyCol = document.getElementById("readyOrders")

	newCol.innerHTML = ""
	prepCol.innerHTML = ""
	readyCol.innerHTML = ""

	let newCount = 0
	let prepCount = 0
	let readyCount = 0

	orders
		.sort((a, b) => new Date(a.createdAt) - new Date(b.createdAt))
		.forEach(order => {

			const card = createCard(order)

			if (order.status === "NEW") {

				newCol.appendChild(card)
				newCount++

			}

			if (order.status === "PREPARING") {

				prepCol.appendChild(card)
				prepCount++

			}

			if (order.status === "READY") {

				readyCol.appendChild(card)
				readyCount++

			}

		})

	document.getElementById("countNew").innerText = newCount
	document.getElementById("countPreparing").innerText = prepCount
	document.getElementById("countReady").innerText = readyCount

}

/* =========================
   CREAR TARJETA
========================= */

function createCard(order) {

	const div = document.createElement("div")
	div.className = "order-card"
	div.dataset.id = order.orderId

	const created = new Date(order.createdAt)

	const seconds = Math.floor((Date.now() - created) / 1000)
	const minutes = Math.floor(seconds / 60)

	let timerClass = "green"
	if (minutes > 5) timerClass = "orange"
	if (minutes > 10) timerClass = "red"
	if (minutes > 10) div.classList.add("urgent")

	let progress = (seconds / MAX_TIME) * 100
	if (progress > 100) progress = 100

	// Items del pedido
	let itemsHTML = ""
	order.items.forEach(i => {
		itemsHTML += `<div class="item">${i.quantity}x ${i.productName}</div>`
	})

	// Observaciones / notas
	let notesHTML = ""
	if (order.notes && order.notes.trim() !== "") {
		notesHTML = `<div class="order-notes">📝 ${order.notes}</div>`
	}

	// Botón según estado
	let button = ""
	if (order.status === "NEW") {
		button = `<button class="btn btn-start" onclick="advanceOrder(${order.orderId},'PREPARING')">COMENZAR</button>`
	}
	if (order.status === "PREPARING") {
		button = `<button class="btn btn-ready" onclick="advanceOrder(${order.orderId},'READY')">LISTO</button>`
	}
	if (order.status === "READY") {
		button = `<button class="btn btn-delivered" onclick="advanceOrder(${order.orderId},'DELIVERED')">ENTREGADO</button>`
	}

	div.innerHTML = `
<div>
	<div class="order-number">#${order.orderNumber}</div>
	<div class="customer">${order.customerName}</div>
	${itemsHTML}
	${notesHTML}
</div>

<div>
	<div class="timer ${timerClass}">
		${minutes}:${String(seconds % 60).padStart(2, "0")}
	</div>
	<div class="progress">
		<div class="progress-bar" style="width:${progress}%"></div>
	</div>
	${button}
</div>
`
	return div
}

/* =========================
   AVANZAR ESTADO
========================= */

async function advanceOrder(orderId, status) {

	try {

		const token = localStorage.getItem("token")

		await fetch(`/api/orders/${orderId}/status?status=${status}`,{
		method:"PATCH",
		headers:{
		"Authorization":"Bearer "+token
		}
		})

		/* sonido cuando queda listo */

		if (status === "READY") {
			playReadySound()
		}

		/* recargar pedidos */

		loadOrders()

	} catch (e) {

		console.error("Error actualizando estado", e)

	}

}

/* =========================
   RELOJ
========================= */

function updateClock() {

	const now = new Date()

	const time = now.toLocaleTimeString()

	const day = String(now.getDate()).padStart(2, '0')
	const month = String(now.getMonth() + 1).padStart(2, '0')
	const year = now.getFullYear()

	const date = `${day}/${month}/${year}`

	document.getElementById("time").innerText = time
	document.getElementById("date").innerText = date

}

/* =========================
   FULLSCREEN
========================= */

document.addEventListener("click", () => {

	/* habilitar audio */

	const sound = document.getElementById("newOrderSound")

	if (sound) {
		sound.play().then(() => {
			sound.pause()
			sound.currentTime = 0
		}).catch(() => { })
	}

	/* fullscreen */

	if (!document.fullscreenElement) {
		document.documentElement.requestFullscreen()
	}

}, { once: true })

/* =========================
   LOOP PRINCIPAL
========================= */

setInterval(loadOrders, 4000)
setInterval(updateClock, 1000)

loadOrders()
updateClock()

function logout() {

	localStorage.removeItem("token");

	window.location.href = "/login/login.html";

}