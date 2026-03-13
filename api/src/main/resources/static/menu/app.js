/*const API_URL = "http://localhost:8080/api/restaurants/1/products"*/

const slug = getRestaurantSlug()

if(!slug){
alert("Restaurant not specified")
}

const API_URL = `/api/restaurants/slug/${slug}/products`

const WHATSAPP = "5490000000000"

let products = []
let cart = JSON.parse(localStorage.getItem("cart")) || {}

init()

async function init() {

	const res = await fetch(API_URL)

	products = await res.json()

	renderMenu()

	renderCart()

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

function getRestaurantSlug(){

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
		productsDiv.className = "category-products"

		header.onclick = () => {
			productsDiv.style.display =
				productsDiv.style.display === "block" ? "none" : "block"
		}

		cat.products.forEach(p => {

			const qty = cart[p.id]?.qty || 0

			const productDiv = document.createElement("div")
			productDiv.className = "product"

			productDiv.innerHTML = `

<img src="${p.imageUrl}">

<div class="product-info">

<div class="product-name">${p.name}</div>

<div>${p.description}</div>

<div class="product-price">$${p.price}</div>

</div>

<div class="product-controls">

<button class="btn btn-minus" onclick="removeItem(${p.id})">-</button>

<div class="quantity" id="qty-${p.id}">${qty}</div>

<button class="btn btn-plus" onclick="addItem(${p.id})">+</button>

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

	const product = products.find(p => p.id === id)

	if (!cart[id]) {
		cart[id] = {
			product: product,
			qty: 0
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

}


document.getElementById("sendOrder").onclick = () => {

	if (Object.keys(cart).length === 0) {
		alert("Agrega productos primero")
		return
	}

	const name = document.getElementById("customerName").value
	const type = document.getElementById("orderType").value
	const address = document.getElementById("address").value
	const notes = document.getElementById("notes").value
	
	if (name.trim() === "") {
		alert("Por favor ingresa tu nombre")
		return
	}

	if (type === "Delivery" && address.trim() === "") {
		alert("Por favor ingresa la dirección para el delivery")
		return
	}

	let message = "Hola! Quiero hacer el siguiente pedido:%0A%0A"

	Object.values(cart).forEach(item => {
		message += `${item.qty} x ${item.product.name}%0A`
	})

	let total = 0

	Object.values(cart).forEach(item => {
		total += item.qty * item.product.price
	})

	message += `%0ATotal: $${total}%0A%0A`

	message += `Nombre: ${name}%0A`
	message += `Tipo de pedido: ${type}%0A`

	if (type === "Delivery") {
		message += `Dirección: ${address}%0A`
	}

	if (notes) {
		message += `Observaciones: ${notes}%0A`
	}

	window.open(`https://wa.me/${WHATSAPP}?text=${message}`)

}


const orderTypeSelect = document.getElementById("orderType")
const addressContainer = document.getElementById("addressContainer")

orderTypeSelect.addEventListener("change", function(){

if(this.value === "Delivery"){
addressContainer.style.display = "block"
}else{
addressContainer.style.display = "none"
}

})