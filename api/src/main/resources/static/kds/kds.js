const businessId = 1

let previousOrders = []

function loadOrders(){

fetch(`/api/business/${businessId}/orders/active`)
.then(r=>r.json())
.then(data=>{

detectNewOrders(data)

renderOrders(data)

previousOrders = data

})

}

function detectNewOrders(orders){

orders.forEach(o=>{

const exists = previousOrders.find(p=>p.orderId === o.orderId)

if(!exists){

document.getElementById("newOrderSound").play()

}

})

}

function renderOrders(orders){

clearColumns()

orders.forEach(order=>{

const card = createOrderCard(order)

if(order.status === "NEW")
document.getElementById("newOrders").appendChild(card)

if(order.status === "PREPARING")
document.getElementById("preparingOrders").appendChild(card)

if(order.status === "READY")
document.getElementById("readyOrders").appendChild(card)

})

}

function clearColumns(){

document.getElementById("newOrders").innerHTML=""
document.getElementById("preparingOrders").innerHTML=""
document.getElementById("readyOrders").innerHTML=""

}

function createOrderCard(order){

const div = document.createElement("div")

div.className="order"

const created = new Date(order.createdAt)

const minutes = Math.floor((Date.now() - created)/60000)

let timerClass="green"

if(minutes>5) timerClass="orange"
if(minutes>10) timerClass="red"

let itemsHTML=""

order.items.forEach(i=>{

itemsHTML += `<div class="item">${i.quantity}x ${i.productName}</div>`

})

let button=""

if(order.status==="NEW"){

button = `
<button class="btn btn-prepare"
onclick="updateStatus(${order.orderId},'PREPARING')">
COMENZAR
</button>
`

}

if(order.status==="PREPARING"){

button = `
<button class="btn btn-ready"
onclick="updateStatus(${order.orderId},'READY')">
LISTO
</button>
`

}

if(order.status==="READY"){

button = `
<button class="btn btn-delivered"
onclick="updateStatus(${order.orderId},'DELIVERED')">
ENTREGADO
</button>
`

}

div.innerHTML=`

<div class="order-number">#${order.orderNumber}</div>

<div class="customer">${order.customerName}</div>

<div class="items">
${itemsHTML}
</div>

<div class="timer ${timerClass}">
${minutes} min
</div>

${button}

`

return div

}

function updateStatus(orderId,status){

fetch(`/api/orders/${orderId}/status?status=${status}`,{
method:"PATCH"
})
.then(loadOrders)

}

function updateClock(){

const now = new Date()

document.getElementById("clock").innerText =
now.toLocaleTimeString()

}

setInterval(loadOrders,4000)
setInterval(updateClock,1000)

loadOrders()
updateClock()