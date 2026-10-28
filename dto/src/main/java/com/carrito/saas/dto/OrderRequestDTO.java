package com.carrito.saas.dto;

import java.util.List;

import com.carrito.saas.repository.enums.OrderType;
import com.carrito.saas.repository.enums.PaymentMethod;

import lombok.Data;

@Data
public class OrderRequestDTO {

    private String customerName;

    private String customerPhone;
    
    private String customerAddress;
    
    private OrderType orderType;
    
    private PaymentMethod paymentMethod;

    /**
     * Mesa del pedido (opcional). Solo aplica a SALON: el servicio la resuelve
     * y valida contra el negocio; RETIRO/DELIVERY la ignoran.
     */
    private Long tableId;

    private String notes;

    private List<OrderItemDTO> items;

}
