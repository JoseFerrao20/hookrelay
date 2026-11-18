package io.github.joseferrao20.hookrelay.delivery;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.joseferrao20.hookrelay.common.NotFoundException;
import io.github.joseferrao20.hookrelay.common.PageResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(DeliveryController.class)
class DeliveryControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean DeliveryService service;

  @Test
  void listPassesFiltersAndDefaults() throws Exception {
    UUID eventId = UUID.randomUUID();
    when(service.list(eq(eventId), any(), eq(DeliveryStatus.PENDING), eq(0), eq(20)))
        .thenReturn(new PageResponse<>(List.of(), 0, 20, 0, 0));
    mvc.perform(get("/api/v1/deliveries?eventId=" + eventId + "&status=PENDING"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.size").value(20));
  }

  @Test
  void invalidParamsReturn400() throws Exception {
    mvc.perform(get("/api/v1/deliveries?status=NOPE")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/deliveries?size=101")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/deliveries?page=-1")).andExpect(status().isBadRequest());
  }

  @Test
  void unknownDeliveryReturns404() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.get(id)).thenThrow(new NotFoundException("Delivery not found"));
    mvc.perform(get("/api/v1/deliveries/" + id)).andExpect(status().isNotFound());
  }
}
