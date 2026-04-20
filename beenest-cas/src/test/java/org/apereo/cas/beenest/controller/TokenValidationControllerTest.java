package org.apereo.cas.beenest.controller;

import org.apereo.cas.beenest.config.GlobalExceptionHandler;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class TokenValidationControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TicketRegistry ticketRegistry = mock(TicketRegistry.class);
        TicketGrantingTicket tgt = mock(TicketGrantingTicket.class);
        Authentication authentication = mock(Authentication.class);
        Principal principal = mock(Principal.class);

        when(ticketRegistry.getTicket("TGT-1", TicketGrantingTicket.class)).thenReturn(tgt);
        when(tgt.isExpired()).thenReturn(false);
        when(tgt.getAuthentication()).thenReturn(authentication);
        when(authentication.getPrincipal()).thenReturn(principal);
        when(principal.getId()).thenReturn("U1001");
        when(principal.getAttributes()).thenReturn(Map.of(
                "userId", List.of("U1001"),
                "nickname", List.of("测试用户"),
                "roles", List.of("ADMIN", "AUDITOR")
        ));

        TokenValidationController controller = new TokenValidationController(ticketRegistry);
        ReflectionTestUtils.setField(controller, "validationSecret", "secret");
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void rejectsMissingSharedSecret() throws Exception {
        mockMvc.perform(post("/token/validate")
                        .accept(MediaType.APPLICATION_JSON)
                        .param("accessToken", "TGT-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未授权访问"));
    }

    @Test
    void shouldFlattenSingleValueAttributesAndKeepMultiValueAttributes() throws Exception {
        mockMvc.perform(post("/token/validate")
                        .accept(MediaType.APPLICATION_JSON)
                        .param("accessToken", "TGT-1")
                        .header("X-CAS-Token-Secret", "secret"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("U1001"))
                .andExpect(jsonPath("$.data.attributes.userId").value("U1001"))
                .andExpect(jsonPath("$.data.attributes.nickname").value("测试用户"))
                .andExpect(jsonPath("$.data.attributes.roles[0]").value("ADMIN"))
                .andExpect(jsonPath("$.data.attributes.roles[1]").value("AUDITOR"));
    }
}
