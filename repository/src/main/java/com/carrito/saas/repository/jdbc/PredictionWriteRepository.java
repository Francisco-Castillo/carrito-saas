package com.carrito.saas.repository.jdbc;

public interface PredictionWriteRepository {
	
	void replaceDailyMetric(Long businessId, int orders, int peakHour, Long topProductId);

    void replaceHourly(Long businessId);

    void replaceProducts(Long businessId);

}
