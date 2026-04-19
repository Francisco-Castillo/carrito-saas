package com.carrito.saas.service.usecase;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.repository.jdbc.ProductTrendRepository;

import lombok.extern.slf4j.Slf4j;

@Service
@Transactional
@Slf4j
public class ProductTrendUseCaseImpl implements IProductTrendUseCase {

	private final ProductTrendRepository productTrendRepository;

	public ProductTrendUseCaseImpl(ProductTrendRepository productTrendRepository) {
		this.productTrendRepository = productTrendRepository;
	}

	@Override
	public void execute() {
		productTrendRepository.update();
	}

}
