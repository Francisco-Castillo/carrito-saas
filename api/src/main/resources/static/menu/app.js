const API_BASE = "http://localhost:8080/api"
const RESTAURANT_ID = 1

let productsCache = []

loadMenu()
renderCart()



async function loadMenu(){

const res = await fetch(
`${API_BASE}/restaurants/${RESTAURANT_ID}/products`
)

const products = await res.json()

productsCache = products

const menu = document.getElementById("menu")

menu.innerHTML=""

const categories = groupByCategory(products)

Object.keys(categories).forEach(cat=>{

menu.innerHTML += `
<div class="category">

<div class="category-header"
onclick="toggleCategory('${cat}')">
${cat}
</div>

<div class="products" id="cat-${cat}">
${renderProducts(categories[cat])}
</div>

</div>
`

})

}



function renderProducts(products){

let html=""

products.forEach(p=>{

html += `

<div class="product">

<img src="${p.imageUrl || 'https://images.unsplash.com/photo-1568901346375-23c9450c58cd'}">

<div class="product-info">

<div class="product-name">${p.name}</div>

<div class="product-desc">${p.description || ""}</div>

<div class="product-price">$${p.price}</div>

</div>

<div class="quantity">

<button class="qty-minus"
onclick="changeQty(${p.id}, -1)">
-
</button>

<span id="qty-${p.id}">
${getQty(p.id)}
</span>

<button class="qty-plus"
onclick="changeQty(${p.id}, 1)">
+
</button>

</div>

</div>

`

})

return html

}



function toggleCategory(cat){

const el = document.getElementById("cat-"+cat)

if(el.style.display==="block"){

el.style.display="none"

}else{

el.style.display="block"

}

}



function groupByCategory(products){

const map={}

products.forEach(p=>{

const cat=p.category||"Otros"

if(!map[cat]){

map[cat]=[]

}

map[cat].push(p)

})

return map

}



function getCart(){

const cart=localStorage.getItem("cart")

if(!cart){

return {restaurantId:RESTAURANT_ID,items:[]}

}

return JSON.parse(cart)

}



function saveCart(cart){

localStorage.setItem("cart",JSON.stringify(cart))

}



function getQty(productId){

const cart=getCart()

const item=cart.items.find(i=>i.productId===productId)

return item?item.quantity:0

}



function changeQty(productId,delta){

const cart=getCart()

let item=cart.items.find(i=>i.productId===productId)

const product = productsCache.find(p=>p.id===productId)

if(delta>0){

if(!item){

cart.items.push({
productId:product.id,
name:product.name,
price:product.price,
quantity:1
})

}else{

item.quantity++

}

}else{

if(!item) return

if(item.quantity===0) return

item.quantity--

if(item.quantity===0){

cart.items=cart.items.filter(i=>i.productId!==productId)

}

}

saveCart(cart)

updateQuantities()

renderCart()

}



function updateQuantities(){

const cart=getCart()

document.querySelectorAll("[id^='qty-']").forEach(el=>{

const id=parseInt(el.id.replace("qty-",""))

const item=cart.items.find(i=>i.productId===id)

el.innerText=item?item.quantity:0

})

}



function renderCart(){

const cart=getCart()

const list=document.getElementById("cart")

const totalDiv=document.getElementById("total")

list.innerHTML=""

let total=0

cart.items.forEach(i=>{

const subtotal=i.price*i.quantity

total+=subtotal

list.innerHTML+=`

<li>
${i.quantity}x ${i.name}
</li>

`

})

totalDiv.innerHTML="Total: $"+total

}



function sendWhatsapp(){

const cart=getCart()

if(cart.items.length===0){

alert("Carrito vacío")

return

}

let msg="Hola! Quiero pedir:\n\n"

cart.items.forEach(i=>{

msg+=`${i.quantity}x ${i.name}\n`

})

msg+="\nGracias!"

const phone="549385XXXXXXX"

const url=`https://wa.me/${phone}?text=${encodeURIComponent(msg)}`

window.open(url)

}