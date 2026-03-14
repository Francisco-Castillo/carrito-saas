import { api } from "./apiClient.js"

async function loadDashboard(){

const data = await api.getDashboardToday()

document.getElementById("ordersToday").innerText = data.orders
document.getElementById("revenueToday").innerText = "$"+data.revenue
document.getElementById("avgTicket").innerText = "$"+data.avgTicket
document.getElementById("avgPrep").innerText = Math.floor(data.avgPrepTime/60)+"m"

}

async function loadStatus(){

const data = await api.getOrderStatusSummary()

document.getElementById("newOrders").innerText = data.NEW || 0
document.getElementById("preparingOrders").innerText = data.PREPARING || 0
document.getElementById("readyOrders").innerText = data.READY || 0

}

async function loadTopProducts(){

const data = await api.getTopProducts()

const list = document.getElementById("topProducts")
list.innerHTML=""

data.forEach(p=>{

const li = document.createElement("li")
li.innerText = `${p.productName} (${p.quantity})`
list.appendChild(li)

})

}

async function loadSalesChart(){

const data = await api.getSalesByHour()

const labels = data.map(d=>d.hour)
const values = data.map(d=>d.revenue)

new Chart(document.getElementById("salesChart"),{

type:"line",

data:{

labels:labels,

datasets:[{

label:"Ventas",

data:values,

borderColor:"#6366f1",

backgroundColor:"rgba(99,102,241,0.2)",

fill:true,

tension:0.3

}]

},

options:{

plugins:{legend:{display:false}},

scales:{
x:{grid:{display:false}},
y:{grid:{color:"rgba(255,255,255,0.05)"}}
}

}

})

}

function updateDate(){

const now = new Date()

document.getElementById("date").innerText =
now.toLocaleDateString("es-ES")

}

updateDate()

loadDashboard()
loadStatus()
loadTopProducts()
loadSalesChart()