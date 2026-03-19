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

    private String notes;

    private List<OrderItemDTO> items;

}
