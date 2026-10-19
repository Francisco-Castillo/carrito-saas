package com.carrito.saas.dto;

import java.util.List;

import lombok.Data;

/**
 * One weekday of opening hours in the public menu and owner profile payloads.
 * The response always carries all seven days (1..7, Monday→Sunday), each with
 * its shifts list — a day with an empty {@code shifts} list is a closed day,
 * so no client has to infer "missing day means closed". On the request side a
 * day omitted from the payload means the same as an empty list (closed), and
 * the whole list is replaced on PUT.
 *
 * <p>Naming a day and omitting its {@code shifts} is an error (400), not a
 * closed day: a client sending the pre-shifts shape would otherwise clear the
 * owner's hours silently. Say {@code "shifts": []} to close a day.</p>
 */
@Data
public class BusinessHoursDTO {

    /** ISO day: 1 = Monday … 7 = Sunday. */
    private Integer dayOfWeek;

    /**
     * Ordered shifts of the day (sorted by {@code opensAt}); empty when closed.
     * Required on a day entry that is present in a request (see class javadoc).
     */
    private List<BusinessShiftDTO> shifts;

}
