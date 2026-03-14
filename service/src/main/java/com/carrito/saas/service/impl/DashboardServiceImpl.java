package com.carrito.saas.service.impl;

import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;

import com.carrito.saas.dto.reportes.DashboardTodayDTO;
import com.carrito.saas.dto.reportes.OrderStatusSummaryDTO;
import com.carrito.saas.repository.entity.Business;
import com.carrito.saas.repository.jpa.BusinessRepository;
import com.carrito.saas.repository.jpa.OrderRepository;
import com.carrito.saas.service.interfaces.IDashboardService;


@Service
public class DashboardServiceImpl implements IDashboardService {

	private final BusinessRepository businessRepository;
	private final OrderRepository orderRepository;

	public DashboardServiceImpl(BusinessRepository businessRepository, OrderRepository orderRepository) {
		super();
		this.businessRepository = businessRepository;
		this.orderRepository = orderRepository;
	}

	@Override
	public DashboardTodayDTO getTodayStats(String slug) {

		Business restaurant = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new RuntimeException("Restaurant not found"));

	    LocalDate today = LocalDate.now();

	    long orders = orderRepository
	            .countOrdersToday(restaurant.getId(), today);

	    double revenue = orderRepository
	            .sumRevenueToday(restaurant.getId(), today);

	    double avgTicket = orders == 0 ? 0 : revenue / orders;

	    long avgPrepTime = orderRepository
	            .avgPrepTimeToday(restaurant.getId(), today);

	    DashboardTodayDTO dto = new DashboardTodayDTO();

	    dto.setOrders(orders);
	    dto.setRevenue(revenue);
	    dto.setAvgTicket(avgTicket);
	    dto.setAvgPrepTime(avgPrepTime);

	    return dto;
	}
	@Override
	public OrderStatusSummaryDTO getOrderStatusSummary(String slug){

		Business restaurant = businessRepository.findBySlug(slug)
				.orElseThrow(() -> new RuntimeException("Restaurant not found"));

	    List<Object[]> rows =
	            orderRepository.countOrdersByStatus(restaurant.getId());

	    OrderStatusSummaryDTO dto = new OrderStatusSummaryDTO();

	    for(Object[] row : rows){

	        String status = String.valueOf(row[0]);
	        
	        Long count = ((Number) row[1]).longValue();

	        switch(status){

	            case "NEW":
	                dto.setNEW(count);
	                break;

	            case "PREPARING":
	                dto.setPREPARING(count);
	                break;

	            case "READY":
	                dto.setREADY(count);
	                break;

	        }
	    }

	    return dto;
	}

}
