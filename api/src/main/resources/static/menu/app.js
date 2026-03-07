const params = new URLSearchParams(window.location.search);
const slug = params.get("slug");

let menuData = null;
let cart = [];
let phone = "";

fetch("/api/menu/" + slug)
.then(res => res.json())
.then(data => {

menuData = data;
phone = data.business.whatsappNumber;

document.getElementById("business-name").innerText = data.business.name;

renderMenu();

});

function renderMenu(){

const menu = document.getElementById("menu");

menuData.categories.forEach(cat=>{

const div = document.createElement("div");
div.className="category";

div.innerHTML=`<h2>${cat.name}</h2>`;

menuData.products
.filter(p=>p.categoryId===cat.id)
.forEach(prod=>{

const p = document.createElement("div");
p.className="product";

p.innerHTML=`
<div class="product-info">
<div class="product-name">${prod.name}</div>
<div>${prod.description||""}</div>
<div class="product-price">$${prod.price}</div>
</div>

<button class="add-btn" onclick="addProduct(${prod.id})">
Agregar
</button>
`;

div.appendChild(p);

});

menu.appendChild(div);

});

}

function addProduct(id){

const product = menuData.products.find(p=>p.id===id);

let item = cart.find(i=>i.id===id);

if(item){

item.qty++;

}else{

cart.push({
id:product.id,
name:product.name,
price:product.price,
qty:1
});

}

renderCart();

}

function renderCart(){

const list = document.getElementById("cart-items");
list.innerHTML="";

let total=0;

cart.forEach(item=>{

total+=item.price*item.qty;

const li=document.createElement("li");

li.innerHTML=`
${item.name} x${item.qty}
<span>$${item.price*item.qty}</span>
`;

list.appendChild(li);

});

document.getElementById("total").innerText=total;

}

document.getElementById("send-btn").onclick=function(){

let message="Hola! Quiero pedir:%0A";

cart.forEach(item=>{

message+=`${item.name} x${item.qty}%0A`;

});

message+=`%0ATotal: $${document.getElementById("total").innerText}`;

const url=`https://wa.me/${phone}?text=${message}`;

window.open(url);

};