package com.carrito.saas.dto;

import java.time.LocalDateTime;

import com.carrito.saas.repository.enums.TableStatus;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TableResponseDTO {
	
	private Long id;

    private Integer tableNumber;

    private String tableName;

    private String qrToken;

    private String qrUrl;

    private TableStatus status;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
    
    private Boolean hasActiveOrder;

    private Long activeOrderId;
}
