package com.cccece.picharbor

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId

class DiagnosticsPolicyTest {
    @Test fun inclusiveDatesFollowCalendarAcrossDaylightSaving() {
        val day=LocalDate.of(2026,3,8)
        val range=LogRange.dates(day,day,ZoneId.of("America/New_York"))
        assertEquals(23L*60*60*1000,range.end-range.start)
        assertTrue(range.includes(range.start))
        assertFalse(range.includes(range.end))
    }
    @Test fun sensitiveValuesNeverPassTheFieldAllowlist() {
        val source=JSONObject().put("password","secret-sentinel").put("Authorization","Bearer secret-sentinel")
            .put("filename","/private/account/IMG_0001.jpg").put("reason","token=secret-sentinel user@example.com https://example.test/?token=secret-sentinel")
        val clean=DiagnosticFields.clean(source)
        assertFalse(clean.toString().contains("secret-sentinel"))
        assertFalse(clean.toString().contains("user@example.com"))
        assertFalse(clean.toString().contains("https://"))
        assertEquals("IMG_0001.jpg",clean.getString("filename"))
    }
    @Test fun exceptionsKeepTypesAndSymbolsWithoutMessages() {
        val error=IllegalStateException("token=secret-sentinel",IllegalArgumentException("user@example.com"))
        val safe=DiagnosticFields.error(error)
        assertFalse(safe.toString().contains("secret-sentinel"))
        assertFalse(safe.toString().contains("user@example.com"))
        assertEquals(2,safe.getJSONArray("cause_types").length())
    }
}
