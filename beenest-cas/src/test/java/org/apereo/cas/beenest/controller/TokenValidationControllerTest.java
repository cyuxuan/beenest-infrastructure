package org.apereo.cas.beenest.controller;

import org.apereo.cas.beenest.config.GlobalExceptionHandler;
import org.apereo.cas.authentication.Authentication;
import org.apereo.cas.authentication.principal.Principal;
import org.apereo.cas.ticket.TicketGrantingTicket;
import org.apereo.cas.ticket.registry.TicketRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

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
        when(principal.getAttributes()).thenReturn(Map.of("userId", java.util.List.of("U1001")));

        TokenValidationController controller = new TokenValidationController(ticketRegistry);
        ReflectionTestUtils.setField(controller, "validationSecret", "secret");
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void rejectsMissingSharedSecret() throws Exception {
        mockMvc.perform(post("/cas/token/validate")
                        .param("accessToken", "TGT-1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.message").value("未授权访问"));
    }
}
