import { api } from "./apiClient.js"

/* =========================
   AUTH
========================= */

function isTokenExpired(token) {
	try {
		const payload = JSON.parse(atob(token.split(".")[1]));
		const exp = payload.exp * 1000;
		return Date.now() > exp;
	} catch (e) {
		return true;
	}
}

function logout() {
	localStorage.removeItem("token");
	window.location.href = "/login/login.html";
}

function checkAuth() {
	const token = localStorage.getItem("token");
	if (!token || isTokenExpired(token)) {
		logout();
	}
}

/* =========================
   UTIL
========================= */

function formatARS(value) {
	return "$ " + new Intl.NumberFormat("es-AR", {
		minimumFractionDigits: 2,
		maximumFractionDigits: 2
	}).format(value);
}

/* =========================
   DASHBOARD
========================= */

async function loadDashboard() {
	try {
		const data = await api.getDashboardToday();

		const revenue = document.getElementById("revenueToday");
		const avgTicket = document.getElementById("avgTicket");
		const orders = document.getElementById("ordersToday");
		const avgPrep = document.getElementById("avgPrep");

		if (revenue) revenue.innerText = formatARS(data.revenue);
		if (avgTicket) avgTicket.innerText = formatARS(data.avgTicket);
		if (orders) orders.innerText = data.orders;
		if (avgPrep) avgPrep.innerText = Math.floor(data.avgPrepTime / 60) + "m";

	} catch (e) {
		console.error("Error cargando dashboard", e);
	}
}

async function loadStatus() {
	try {
		const data = await api.getOrderStatusSummary();

		const newOrders = document.getElementById("newOrders");
		const preparing = document.getElementById("preparingOrders");
		const ready = document.getElementById("readyOrders");

		if (newOrders) newOrders.innerText = data.NEW || 0;
		if (preparing) preparing.innerText = data.PREPARING || 0;
		if (ready) ready.innerText = data.READY || 0;

	} catch (e) {
		console.error("Error cargando estados", e);
	}
}

async function loadTopProducts() {
	try {
		const data = await api.getTopProducts();

		const list = document.getElementById("topProducts");
		if (!list) return;

		list.innerHTML = "";

		data.forEach(p => {
			const li = document.createElement("li");
			li.innerText = `${p.productName} (${p.quantity})`;
			list.appendChild(li);
		});

	} catch (e) {
		console.error("Error cargando top productos", e);
	}
}

async function loadSalesChart() {
	try {
		const data = await api.getSalesByHour();

		const labels = data.map(d => d.hour);
		const values = data.map(d => d.revenue);

		const canvas = document.getElementById("salesChart");
		if (!canvas) return;

		// 🔥 FIX DEFINITIVO
		const existingChart = Chart.getChart(canvas);
		if (existingChart) {
			existingChart.destroy();
		}

		new Chart(canvas, {
			type: "line",
			data: {
				labels,
				datasets: [{
					label: "Ventas",
					data: values,
					borderColor: "#6366f1",
					backgroundColor: "rgba(99,102,241,0.2)",
					fill: true,
					tension: 0.3
				}]
			},
			options: {
				responsive: true,
				maintainAspectRatio: false,
				plugins: {
					legend: { display: false },
					tooltip: {
						callbacks: {
							label: function(context) {
								return "Ventas: $ " + new Intl.NumberFormat("es-AR").format(context.parsed.y);
							}
						}
					}
				},
				scales: {
					x: {
						grid: { display: false }
					},
					y: {
						ticks: {
							callback: value => "$ " + new Intl.NumberFormat("es-AR").format(value)
						},
						grid: { color: "rgba(255,255,255,0.05)" }
					}
				}
			}
		});

	} catch (e) {
		console.error("Error cargando grafico", e);
	}
}

/* =========================
   DATE
========================= */

function updateDate() {
	const now = new Date();
	const date = document.getElementById("date");

	if (date) {
		date.innerText = now.toLocaleDateString("es-ES");
	}
}

/* =========================
   INIT (SOLO PARA SPA)
========================= */

export function initDashboard() {
	checkAuth();

	updateDate();
	loadDashboard();
	loadStatus();
	loadTopProducts();
	loadSalesChart();
}