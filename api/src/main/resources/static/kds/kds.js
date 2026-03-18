const params = new URLSearchParams(window.location.search)
const restaurantSlug = params.get("restaurant")

if (!restaurantSlug) {
	document.body.innerHTML = "<h1>Restaurante no especificado</h1>"
	throw new Error("Missing restaurant slug")
}

const MAX_TIME = 900
let previousOrders = []

/* =========================
   SONIDOS
========================= */
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
		const response = await fetch(`/api/business/${restaurantSlug}/orders/active`, {
			headers: {
				"Authorization": "Bearer " + token
			}
		})

		if (response.status === 404) {
			document.body.innerHTML = "<h1>Restaurante no encontrado</h1>"
			return
		}
		if (!response.ok) throw new Error("Error cargando pedidos")

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
		if (!exists) playNewOrderSound()
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
			card.dataset.status = order.status
			card.dataset.id = order.orderId

			enableDrag(card) // se asegura de agregar los listeners solo una vez

			if (order.status === "NEW") {
				newCol.appendChild(card)
				newCount++
			} else if (order.status === "PREPARING") {
				prepCol.appendChild(card)
				prepCount++
			} else if (order.status === "READY") {
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

	/* ITEMS */

	let itemsHTML = ""

	order.items.forEach(i => {
		itemsHTML += `<div class="item">${i.quantity}x ${i.productName}</div>`
	})

	/* TIPO DE PEDIDO */

	let typeIcon = "🏃"
	let typeLabel = "Retiro"
	let typeClass = "pickup"

	if (order.orderType === "DELIVERY") {
		typeIcon = "📦"
		typeLabel = "Delivery"
		typeClass = "delivery"
	}

	const typeHTML = `
<div class="order-type ${typeClass}">
${typeIcon} ${typeLabel}
</div>
`

	/* NOTAS */

	let notesHTML = ""

	if (order.notes && order.notes.trim() !== "") {

		notesHTML = `
<div class="order-notes">
📝 ${order.notes}
</div>
`
	}

	/* BOTONES */

	let button = ""

	if (order.status === "NEW") {

		button = `
<button class="btn btn-start"
onclick="advanceOrder(${order.orderId},'PREPARING')">
COMENZAR
</button>
`
	}

	if (order.status === "PREPARING") {

		button = `
<button class="btn btn-ready"
onclick="advanceOrder(${order.orderId},'READY')">
LISTO
</button>
`
	}

	if (order.status === "READY") {

		button = `
<button class="btn btn-delivered"
onclick="advanceOrder(${order.orderId},'DELIVERED')">
ENTREGADO
</button>
`
	}

	div.innerHTML = `

<div>

<div class="order-number">#${order.orderNumber}</div>

<div class="customer">${order.customerName}</div>

${typeHTML}

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
		await fetch(`/api/orders/${orderId}/status?status=${status}`, {
			method: "PATCH",
			headers: { "Authorization": "Bearer " + token }
		})
		if (status === "READY") playReadySound()
		loadOrders()
	} catch (e) { console.error("Error actualizando estado", e) }
}

/* =========================
   RELOJ
========================= */
function updateClock() {
	const now = new Date()
	document.getElementById("time").innerText = now.toLocaleTimeString()
	const day = String(now.getDate()).padStart(2, '0')
	const month = String(now.getMonth() + 1).padStart(2, '0')
	const year = now.getFullYear()
	document.getElementById("date").innerText = `${day}/${month}/${year}`
}

/* =========================
   FULLSCREEN
========================= */
document.addEventListener("click", () => {
	const sound = document.getElementById("newOrderSound")
	if (sound) {
		sound.play().then(() => { sound.pause(); sound.currentTime = 0 }).catch(() => { })
	}
	if (!document.fullscreenElement) document.documentElement.requestFullscreen()
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

/* =========================
   DRAG & SWIPE
========================= */

let draggedCard = null
let startX = 0
let startY = 0
let currentX = 0
let currentY = 0
let longPressTimeout = null
let moved = false

function enableDrag(card) {

	if (card.dataset.dragAttached) return
	card.dataset.dragAttached = "true"

	card.addEventListener("mousedown", dragStart)
	card.addEventListener("touchstart", dragStart, { passive: true })

	card.addEventListener("mousemove", dragMove)
	card.addEventListener("touchmove", dragMove, { passive: false })

	card.addEventListener("mouseup", dragEnd)
	card.addEventListener("touchend", dragEnd)

	card.addEventListener("mouseleave", dragCancel)
	card.addEventListener("touchcancel", dragCancel)
}

function dragStart(e) {

	if (e.target.classList.contains("btn")) return

	draggedCard = e.currentTarget
	moved = false

	const rect = draggedCard.getBoundingClientRect()

	draggedCard.classList.add("dragging", "rotate")

	draggedCard.style.width = rect.width + "px"
	draggedCard.style.height = rect.height + "px"
	draggedCard.style.left = rect.left + "px"
	draggedCard.style.top = rect.top + "px"

	startX = e.type.includes("touch") ? e.touches[0].clientX : e.clientX
	startY = e.type.includes("touch") ? e.touches[0].clientY : e.clientY

	currentX = startX
	currentY = startY

	if (draggedCard.dataset.status === "NEW") {

		longPressTimeout = setTimeout(() => {

			if (!moved) {
				showCancelModal(draggedCard.dataset.id)
				dragCancel()
			}

		}, 1500)

	}

}

function dragMove(e) {

	if (!draggedCard) return

	const clientX = e.type.includes("touch") ? e.touches[0].clientX : e.clientX
	const clientY = e.type.includes("touch") ? e.touches[0].clientY : e.clientY

	const diffX = clientX - startX
	const diffY = clientY - startY

	if (Math.abs(diffX) > 5 || Math.abs(diffY) > 5) {
		moved = true
	}

	currentX = clientX
	currentY = clientY

	const rotate = diffX / 10

	draggedCard.style.transform = `translate(${diffX}px,${diffY}px) rotate(${rotate}deg)`

	e.preventDefault()
}

function dragEnd() {

	if (!draggedCard) return

	clearTimeout(longPressTimeout)

	const diffX = currentX - startX
	const threshold = 80

	const orderId = draggedCard.dataset.id
	let status = draggedCard.dataset.status

	if (diffX > threshold) {

		status = advanceNextState(orderId, status)

	} else if (diffX < -threshold) {

		if (status === "NEW") {

			showCancelModal(orderId)

		} else if (status === "PREPARING") {

			status = revertState(orderId, status)

		}

	} else {

		dragCancel()
		return
	}

	draggedCard.dataset.status = status

	let targetCol

	if (status === "NEW") targetCol = document.getElementById("newOrders")
	if (status === "PREPARING") targetCol = document.getElementById("preparingOrders")
	if (status === "READY") targetCol = document.getElementById("readyOrders")

	targetCol.appendChild(draggedCard)

	dragCancel()
}

function dragCancel() {

	if (!draggedCard) return

	draggedCard.classList.add("transition")

	draggedCard.style.transform = "translate(0px,0px) rotate(0deg)"

	setTimeout(() => {

		if (draggedCard) {

			draggedCard.classList.remove("dragging", "rotate", "transition")

			draggedCard.style.width = ""
			draggedCard.style.height = ""
			draggedCard.style.left = ""
			draggedCard.style.top = ""

			draggedCard = null
		}

	}, 300)

}

/* =========================
   ESTADOS
========================= */

function advanceNextState(orderId, status) {

	if (status === "NEW") {
		advanceOrder(orderId, "PREPARING")
		return "PREPARING"
	}

	if (status === "PREPARING") {
		advanceOrder(orderId, "READY")
		return "READY"
	}

	return status
}

function revertState(orderId, status) {

	if (status === "PREPARING") {
		advanceOrder(orderId, "NEW")
		return "NEW"
	}

	return status
}

/* =========================
   MODAL CANCELAR PEDIDO
========================= */

let cancelOrderId = null

function showCancelModal(orderId) {
	cancelOrderId = orderId
	const modal = document.getElementById("cancelModal")
	if (modal) modal.classList.remove("hidden")
}

function hideCancelModal() {
	const modal = document.getElementById("cancelModal")
	if (modal) modal.classList.add("hidden")
	cancelOrderId = null
}

async function confirmCancelOrder() {
	if (!cancelOrderId) return

	try {
		const token = localStorage.getItem("token")

		await fetch(`/api/orders/${cancelOrderId}/status?status=CANCELLED`, {
			method: "PATCH",
			headers: {
				"Authorization": "Bearer " + token,
				"Content-Type": "application/json"
			}
		})

		hideCancelModal()
		loadOrders()

	} catch (e) {
		console.error("Error cancelando pedido", e)
	}
}
