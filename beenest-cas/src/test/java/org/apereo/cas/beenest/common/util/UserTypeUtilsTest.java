package org.apereo.cas.beenest.common.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class UserTypeUtilsTest {

    @Test
    void normalizesUnknownUserTypeToCustomer() {
        assertEquals("CUSTOMER", UserTypeUtils.normalize("hacker"));
    }

    @Test
    void keepsAllowedUserType() {
        assertEquals("PILOT", UserTypeUtils.normalize("pilot"));
    }
}
