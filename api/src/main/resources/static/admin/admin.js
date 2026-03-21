let products = [
    {id:1, name:"Pollo", price:18000, stock:10, active:true},
    {id:2, name:"Papas", price:2500, stock:50, active:true}
]

let editingId = null

function renderProducts(list = products){

    const table = document.getElementById("productTable")
    table.innerHTML = ""

    list.forEach(p => {

        const tr = document.createElement("tr")

        tr.innerHTML = `
            <td>${p.name}</td>
            <td>$${p.price}</td>
            <td>
                ${p.stock}
                <button onclick="updateStock(${p.id},10)">+10</button>
                <button onclick="updateStock(${p.id},-10)">-10</button>
            </td>
            <td>${p.active ? "Activo" : "Inactivo"}</td>
            <td>
                <button onclick="editProduct(${p.id})">✏️</button>
                <button onclick="toggleProduct(${p.id})">ON/OFF</button>
            </td>
        `

        table.appendChild(tr)
    })
}

function filterProducts(){
    const q = document.getElementById("search").value.toLowerCase()
    const filtered = products.filter(p => p.name.toLowerCase().includes(q))
    renderProducts(filtered)
}

function updateStock(id, delta){

    const p = products.find(p=>p.id===id)
    p.stock += delta

    renderProducts()

    //TODO: llamar endpoint PATCH /products/{id}/stock
}

function toggleProduct(id){

    const p = products.find(p=>p.id===id)
    p.active = !p.active

    renderProducts()

    //TODO: llamar endpoint PATCH /products/{id}/active
}

function openProductModal(){
    editingId = null
    document.getElementById("productModal").classList.remove("hidden")
}

function closeProductModal(){
    document.getElementById("productModal").classList.add("hidden")
}

function editProduct(id){

    const p = products.find(p=>p.id===id)

    editingId = id

    document.getElementById("pName").value = p.name
    document.getElementById("pPrice").value = p.price
    document.getElementById("pStock").value = p.stock

    openProductModal()
}

function saveProduct(){

    const name = document.getElementById("pName").value
    const price = parseFloat(document.getElementById("pPrice").value)
    const stock = parseInt(document.getElementById("pStock").value)

    if(editingId){

        const p = products.find(p=>p.id===editingId)
        p.name = name
        p.price = price
        p.stock = stock

        //TODO: PUT /products/{id}

    }else{

        products.push({
            id: Date.now(),
            name,
            price,
            stock,
            active:true
        })

        //TODO: POST /products
    }

    closeProductModal()
    renderProducts()
}

function switchTab(tab){

    document.querySelectorAll(".tab").forEach(t=>t.classList.remove("active"))
    document.querySelectorAll(".tab-content").forEach(c=>c.classList.remove("active"))

    document.getElementById(tab).classList.add("active")

    event.target.classList.add("active")
}

function addCategory(){
    alert("TODO categorias")
    //TODO: implementar CRUD categorias
}

function addCombo(){
    alert("TODO combos")
    //TODO: implementar CRUD combos
}

// init
renderProducts()