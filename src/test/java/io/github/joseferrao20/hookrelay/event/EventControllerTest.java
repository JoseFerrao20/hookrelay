package io.github.joseferrao20.hookrelay.event;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.joseferrao20.hookrelay.common.ConflictException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(EventController.class)
class EventControllerTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @MockitoBean EventService service;

  private EventResponse sample(UUID id) throws Exception {
    return new EventResponse(
        id,
        "a.b",
        json.readTree("{\"x\":1}"),
        "k",
        Instant.parse("2026-11-18T15:00:00Z"),
        List.of());
  }

  private static final String BODY = "{\"eventType\":\"a.b\",\"payload\":{\"x\":1}}";

  @Test
  void newEventReturns202WithLocation() throws Exception {
    UUID id = UUID.randomUUID();
    when(service.create(eq("k"), any())).thenReturn(new EventCreationResult(sample(id), true));
    mvc.perform(
            post("/api/v1/events")
                .header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Location", "/api/v1/events/" + id))
        .andExpect(jsonPath("$.idempotencyKey").value("k"));
  }

  @Test
  void replayReturns200WithoutLocation() throws Exception {
    when(service.create(eq("k"), any()))
        .thenReturn(new EventCreationResult(sample(UUID.randomUUID()), false));
    mvc.perform(
            post("/api/v1/events")
                .header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Location"));
  }

  @Test
  void conflictReturns409ProblemDetails() throws Exception {
    when(service.create(eq("k"), any())).thenThrow(new ConflictException("different content"));
    mvc.perform(
            post("/api/v1/events")
                .header("Idempotency-Key", "k")
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isConflict())
        .andExpect(header().string("Content-Type", "application/problem+json"))
        .andExpect(jsonPath("$.status").value(409));
  }

  @Test
  void validationFailuresReturn400() throws Exception {
    mvc.perform(
            post("/api/v1/events")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"eventType\":\"a.b\",\"payload\":[1]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.errors[0].field").value("payload"))
        .andExpect(jsonPath("$.errors[0].message").value("must be a JSON object"));
    mvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/v1/events")
                .header("Idempotency-Key", "x".repeat(256))
                .contentType(MediaType.APPLICATION_JSON)
                .content(BODY))
        .andExpect(status().isBadRequest());
  }
}
