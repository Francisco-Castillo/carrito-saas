package com.carrito.saas.service.impl;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import javax.imageio.ImageIO;

import org.springframework.stereotype.Service;

import com.carrito.saas.service.interfaces.IQrCodeService;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

@Service
public class ZxingQrCodeServiceImpl implements IQrCodeService{
	
    //private static final int QR_SIZE = 400;

	@Override
	public byte[] generateQr(String content, Integer size) {
		try {

			int qrSize =
		            size != null
		            ? size
		            : 300;

			
            Map<EncodeHintType, Object> hints =
                    new HashMap<>();

            hints.put(
                    EncodeHintType.CHARACTER_SET,
                    StandardCharsets.UTF_8.name());

            hints.put(
                    EncodeHintType.MARGIN,
                    1);

            BitMatrix matrix =
                    new QRCodeWriter().encode(
                            content,
                            BarcodeFormat.QR_CODE,
                            qrSize,
                            qrSize,
                            hints);

            BufferedImage image =
                    MatrixToImageWriter
                            .toBufferedImage(matrix);

            ByteArrayOutputStream output =
                    new ByteArrayOutputStream();

            ImageIO.write(
                    image,
                    "PNG",
                    output);

            return output.toByteArray();

        } catch (Exception ex) {
/*
            throw new QrGenerationException(
                    "Error generating QR",
                    ex);*/
        }
		return null;
	}

}
