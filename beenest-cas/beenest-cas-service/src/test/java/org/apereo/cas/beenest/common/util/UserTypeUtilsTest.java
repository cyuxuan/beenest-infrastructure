package org.apereo.cas.beenest.common.util;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UserTypeUtilsTest {

    @Test
    void normalizeSelfRegistrationAllowsCustomerAndPilotOnly() {
        assertThat(UserTypeUtils.normalizeSelfRegistration("CUSTOMER")).isEqualTo("CUSTOMER");
        assertThat(UserTypeUtils.normalizeSelfRegistration("pilot")).isEqualTo("PILOT");
        assertThat(UserTypeUtils.normalizeSelfRegistration("ADMIN")).isEqualTo("CUSTOMER");
        assertThat(UserTypeUtils.normalizeSelfRegistration(null)).isEqualTo("CUSTOMER");
    }
}
