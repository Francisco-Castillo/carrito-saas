package com.carrito.saas.service.impl;

import java.math.BigDecimal;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.carrito.saas.dto.ComboCreateDTO;
import com.carrito.saas.dto.ComboDTO;
import com.carrito.saas.dto.ComboProductDTO;
import com.carrito.saas.repository.entity.Combo;
import com.carrito.saas.repository.entity.ComboProduct;
import com.carrito.saas.repository.jpa.ComboProductRepository;
import com.carrito.saas.repository.jpa.ComboRepository;
import com.carrito.saas.repository.jpa.ProductRepository;
import com.carrito.saas.service.interfaces.IComboService;
import com.carrito.saas.service.mapper.interfaces.IComboMapper;

@Service
public class ComboServiceImpl implements IComboService {

	private final ComboRepository comboRepository;
	private final ProductRepository productRepository;
	private final ComboProductRepository comboProductRepository;
	private final IComboMapper iComboMapper;

	public ComboServiceImpl(ComboRepository comboRepository, ProductRepository productRepository,
			ComboProductRepository comboProductRepository, IComboMapper iComboMapper) {
		this.comboRepository = comboRepository;
		this.productRepository = productRepository;
		this.comboProductRepository = comboProductRepository;
		this.iComboMapper = iComboMapper;
	}

	@Transactional
	@Override
	public ComboDTO crearCombo(ComboCreateDTO combo) {
		Combo nuevoCombo = new Combo();
		nuevoCombo.setName(combo.getName());
		nuevoCombo.setPrice(combo.getPrice());
		nuevoCombo.setActive(true);

		Combo comboSaved = comboRepository.save(nuevoCombo);

		List<ComboProductDTO> comboProductsDTO = combo.getProducts();

		List<ComboProduct> productos = comboProductsDTO.stream().map(dto -> {
			ComboProduct cp = new ComboProduct();
			cp.setCombo(comboSaved);
			cp.setProduct(productRepository.findById(dto.getProductId()).orElseThrow());
			cp.setQuantity(BigDecimal.valueOf(dto.getQuantity()));
			return comboProductRepository.save(cp);
		}).toList();

		comboSaved.setItems(productos);

		return iComboMapper.toDTO(comboSaved);
	}

}
