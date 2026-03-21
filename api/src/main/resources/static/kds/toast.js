function showToast(message, type = "success") {

	const container = document.getElementById("toastContainer")

	const toast = document.createElement("div")
	toast.className = `toast ${type}`
	toast.innerText = message

	container.appendChild(toast)

	setTimeout(() => {
		toast.style.animation = "toastOut 0.3s forwards"
		setTimeout(() => toast.remove(), 300)
	}, 3000)
}