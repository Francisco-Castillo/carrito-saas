package com.carrito.saas.dto;

import java.util.List;

import com.carrito.saas.dto.DateExceptionDTO;

import lombok.Data;

/**
 * Owner-facing storefront profile ({@code GET}/{@code PUT
 * /api/business/profile}). The fields mean the same as on
 * {@link BusinessDTO}. The PUT is a partial update: a field absent (null)
 * keeps the stored value, and present fields are validated and replaced as
 * before. For the structured lists an empty list is the explicit "none":
 * {@code hours: []} closes every day and {@code dateExceptions: []} deletes
 * every special date; {@code hours}/{@code dateExceptions} absent leave the
 * stored shifts/dates intact. {@code name} is the one exception on the right:
 * blank is rejected with 400 (a business needs a name), absent keeps the
 * stored name.
 */
@Data
public class BusinessProfileDTO {

    private String name;

    /** Teléfono del local (WhatsApp): el menú público lo sirve como
     *  {@code whatsappNumber}. Mismas reglas que en el alta de plataforma:
     *  dígitos, sin 0 ni 15 (espeja el check businesses_phone_shape).
     *  null = no tocar (actualización parcial); vacío limpia; inválido es 400. */
    private String phone;

    private String logoUrl;

    private String bannerUrl;

    private String address;

    private String instagramUrl;

    /** Perfil de TikTok del local. Mismo contrato que {@code instagramUrl}:
     *  null = no tocar (actualización parcial); vacío limpia; inválida es 400. */
    private String tiktokUrl;

    /** Color de marca del local como hex {@code #RRGGBB}. null = no tocar
     *  (actualización parcial); vacío limpia; inválido es 400. */
    private String primaryColor;

    /** Tipografía de la carta: slug de la lista blanca de fuentes curadas.
     *  null = no tocar (actualización parcial); vacío limpia (la carta vuelve
     *  a Inter); un slug que no está en la lista es 400. */
    private String menuFont;

    private List<BusinessHoursDTO> hours;

    /** Fechas especiales, ordenadas por fecha; vacío cuando no hay ninguna. */
    private List<DateExceptionDTO> dateExceptions;

}
