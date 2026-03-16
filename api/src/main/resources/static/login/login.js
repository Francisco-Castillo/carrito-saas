document.getElementById("loginForm").addEventListener("submit", async function(e) {

    e.preventDefault(); // evita que el form se envíe normal

    const username = document.getElementById("username").value;
    const password = document.getElementById("password").value;

    try {

        const res = await fetch("/api/auth/login", {
            method: "POST",
            headers: {
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ username, password })
        });

        if (!res.ok) {
            throw new Error("Usuario o contraseña incorrectos");
        }

        const data = await res.json();

        localStorage.setItem("token", data.token);

        const roles = data.role;
        const slug = data.restaurantSlug;

        if (roles.includes("OWNER")) {

			window.location.href = `/dashboard/index.html?restaurant=${slug}`;

        } else if (roles.includes("KITCHEN")) {

			window.location.href = `/kds/index.html?restaurant=${slug}`;

        } else {

            throw new Error("Rol no permitido");

        }

    } catch (err) {

        console.error(err);

    }

});