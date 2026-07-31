package com.carrito.saas.service.interfaces;

public interface IQrCodeService {
	
	byte[] generateQr(String content, Integer size);

}
