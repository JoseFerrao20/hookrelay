package io.github.joseferrao20.hookrelay.endpoint;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import io.github.joseferrao20.hookrelay.TestAuthConfiguration;
import io.github.joseferrao20.hookrelay.common.NotFoundException;
import io.github.joseferrao20.hookrelay.security.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EndpointController.class)
@Import({SecurityConfig.class, TestAuthConfiguration.class})
class EndpointControllerTest {

  @Autowired MockMvc mvc;
  @MockitoBean EndpointService service;

  @Test
  void createReturns201WithLocation() throws Exception {
    UUID id = UUID.randomUUID();
    Instant now = Instant.parse("2026-11-18T15:00:00Z");
    when(service.create(any()))
        .thenReturn(
            new EndpointCreatedResponse(
                id, "https://e.com/h", null, List.of("a.b"), true, now, now, "whsec_x"));
    mvc.perform(
            post("/api/v1/endpoints")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://e.com/h\",\"eventTypes\":[\"a.b\"]}"))
        .andExpect(status().isCreated())
        .andExpect(header().string("Location", "/api/v1/endpoints/" + id))
        .andExpect(jsonPath("$.secret").value("whsec_x"));
  }

  @Test
  void invalidBodyReturnsProblemDetailsWithFieldErrors() throws Exception {
    mvc.perform(
            post("/api/v1/endpoints")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"ftp://x\",\"eventTypes\":[]}"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.title").value("Bad Request"))
        .andExpect(jsonPath("$.errors[?(@.field=='url')]").exists())
        .andExpect(jsonPath("$.errors[?(@.field=='eventTypes')]").exists());
  }

  @Test
  void badEventTypeAndMalformedJsonReturn400() throws Exception {
    mvc.perform(
            post("/api/v1/endpoints")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"url\":\"https://x.com\",\"eventTypes\":[\"bad type\"]}"))
        .andExpect(status().isBadRequest());
    mvc.perform(post("/api/v1/endpoints").contentType(MediaType.APPLICATION_JSON).content("{"))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Content-Type", "application/problem+json"));
  }

  @Test
  void unknownIdReturns404AndBadUuidReturns400() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.get(id)).thenThrow(new NotFoundException("Endpoint not found"));
    mvc.perform(get("/api/v1/endpoints/" + id))
        .andExpect(status().isNotFound())
        .andExpect(header().string("Content-Type", "application/problem+json"));
    mvc.perform(get("/api/v1/endpoints/not-a-uuid")).andExpect(status().isBadRequest());
  }

  @Test
  void unexpectedErrorReturns500WithoutInternals() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.get(id)).thenThrow(new IllegalStateException("secret internal detail"));
    mvc.perform(get("/api/v1/endpoints/" + id))
        .andExpect(status().isInternalServerError())
        .andExpect(jsonPath("$.detail").value("An unexpected error occurred."));
  }
}
