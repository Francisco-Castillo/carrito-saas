package com.carrito.saas.dto;

import java.util.List;

import lombok.Builder;
import lombok.Getter;

@Builder
@Getter
public class PageResponse<T> {
	
	private List<T> content;

    private int page;
    private int size;

    private long totalElements;
    private int totalPages;

    private boolean last;

}
