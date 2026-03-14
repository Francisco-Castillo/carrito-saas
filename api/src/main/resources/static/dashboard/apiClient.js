const params = new URLSearchParams(window.location.search)
const restaurantSlug = params.get("restaurant")

if(!restaurantSlug){
    console.error("Restaurant slug missing in URL")
}

const API_BASE = `/api/restaurants/${restaurantSlug}`

async function apiGet(path){

    const res = await fetch(`${API_BASE}${path}`)

    if(!res.ok){
        throw new Error("API error")
    }

    return res.json()
}

export const api = {

    getDashboardToday(){
        return apiGet("/dashboard/today")
    },

    getOrderStatusSummary(){
        return apiGet("/orders/status-summary")
    },

    getTopProducts(){
        return apiGet("/analytics/top-products")
    },

    getSalesByHour(){
        return apiGet("/analytics/sales-by-hour")
    }

}