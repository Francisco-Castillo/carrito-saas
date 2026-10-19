package com.carrito.saas.service.strategies.impl;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;

import com.carrito.saas.repository.enums.QrTemplate;
import com.carrito.saas.service.strategies.interfaces.PdfTemplateStrategy;

@Service
public class PdfTemplateFactory {

	private final Map<QrTemplate, PdfTemplateStrategy> strategies;

	public PdfTemplateFactory(List<PdfTemplateStrategy> templates) {

		this.strategies = templates.stream()
				.collect(Collectors.toMap(PdfTemplateStrategy::getTemplate, Function.identity()));
	}

	public PdfTemplateStrategy get(QrTemplate template) {

		PdfTemplateStrategy strategy = strategies.get(template);

		if (strategy == null) {
			throw new IllegalArgumentException("Unsupported template: " + template);
		}

		return strategy;
	}

}
