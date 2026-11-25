package io.github.joseferrao20.hookrelay;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.joseferrao20.hookrelay.delivery.DeliveryRepository;
import io.github.joseferrao20.hookrelay.endpoint.EndpointRepository;
import io.github.joseferrao20.hookrelay.event.EventRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@Import({TestcontainersConfiguration.class, TestAuthConfiguration.class})
@SpringBootTest(properties = "hookrelay.dispatch.enabled=false")
@AutoConfigureMockMvc
class ApiIntegrationTest {

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired DeliveryRepository deliveries;
  @Autowired EventRepository events;
  @Autowired EndpointRepository endpoints;

  @BeforeEach
  void clean() {
    deliveries.deleteAll();
    events.deleteAll();
    endpoints.deleteAll();
  }

  private JsonNode body(MvcResult r) throws Exception {
    return json.readTree(r.getResponse().getContentAsString());
  }

  private String createEndpoint(String types, boolean enabled) throws Exception {
    MvcResult r =
        mvc.perform(
                post("/api/v1/endpoints")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"url\":\"https://example.com/hook\",\"eventTypes\":["
                            + types
                            + "],\"enabled\":"
                            + enabled
                            + "}"))
            .andExpect(status().isCreated())
            .andReturn();
    return body(r).get("id").asText();
  }

  private MvcResult postEvent(String key, String type, String payload) throws Exception {
    var req =
        post("/api/v1/events")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"eventType\":\"" + type + "\",\"payload\":" + payload + "}");
    if (key != null) {
      req.header("Idempotency-Key", key);
    }
    return mvc.perform(req).andReturn();
  }

  @Test
  void endpointCrudAndSoftDelete() throws Exception {
    MvcResult created =
        mvc.perform(
                post("/api/v1/endpoints")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"url\":\"https://example.com/h\",\"description\":\"d\","
                            + "\"eventTypes\":[\"b.two\",\"a.one\"]}"))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.secret").value(org.hamcrest.Matchers.startsWith("whsec_")))
            .andExpect(jsonPath("$.enabled").value(true))
            .andExpect(jsonPath("$.eventTypes[0]").value("a.one"))
            .andReturn();
    String id = body(created).get("id").asText();
    assertThat(created.getResponse().getHeader("Location")).isEqualTo("/api/v1/endpoints/" + id);

    mvc.perform(get("/api/v1/endpoints/" + id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.secret").doesNotExist());

    mvc.perform(
            patch("/api/v1/endpoints/" + id)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"enabled\":false,\"eventTypes\":[\"c.three\"]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.enabled").value(false))
        .andExpect(jsonPath("$.eventTypes.length()").value(1))
        .andExpect(jsonPath("$.url").value("https://example.com/h"));

    mvc.perform(get("/api/v1/endpoints"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));

    mvc.perform(delete("/api/v1/endpoints/" + id)).andExpect(status().isNoContent());
    mvc.perform(get("/api/v1/endpoints/" + id))
        .andExpect(status().isNotFound())
        .andExpect(header().string("Content-Type", "application/problem+json"));
    mvc.perform(get("/api/v1/endpoints")).andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(delete("/api/v1/endpoints/" + id)).andExpect(status().isNotFound());
  }

  @Test
  void fanOutCreatesPendingDeliveriesOnlyForMatchingEndpoints() throws Exception {
    String match = createEndpoint("\"order.created\"", true);
    String wildcard = createEndpoint("\"*\"", true);
    createEndpoint("\"order.paid\"", true);
    createEndpoint("\"order.created\"", false);
    String deleted = createEndpoint("\"order.created\"", true);
    mvc.perform(delete("/api/v1/endpoints/" + deleted)).andExpect(status().isNoContent());

    MvcResult r = postEvent(null, "order.created", "{\"orderId\":42}");
    assertThat(r.getResponse().getStatus()).isEqualTo(202);
    JsonNode event = body(r);
    assertThat(r.getResponse().getHeader("Location"))
        .isEqualTo("/api/v1/events/" + event.get("id").asText());
    List<String> targets =
        event.get("deliveries").findValuesAsText("endpointId").stream().sorted().toList();
    assertThat(targets).containsExactlyInAnyOrder(match, wildcard);
    event.get("deliveries").forEach(d -> assertThat(d.get("status").asText()).isEqualTo("PENDING"));

    mvc.perform(get("/api/v1/deliveries?eventId=" + event.get("id").asText()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].attemptCount").value(0))
        .andExpect(jsonPath("$.content[0].nextAttemptAt").isNotEmpty());
    mvc.perform(get("/api/v1/deliveries?endpointId=" + match + "&status=PENDING"))
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(get("/api/v1/deliveries?status=DEAD"))
        .andExpect(jsonPath("$.totalElements").value(0));
    mvc.perform(get("/api/v1/deliveries?status=BOGUS")).andExpect(status().isBadRequest());
    mvc.perform(get("/api/v1/deliveries?eventId=nope")).andExpect(status().isBadRequest());
  }

  @Test
  void idempotentReplayAndConflict() throws Exception {
    createEndpoint("\"*\"", true);
    MvcResult first = postEvent("key-1", "a.b", "{\"x\":1,\"y\":[1,2]}");
    assertThat(first.getResponse().getStatus()).isEqualTo(202);
    MvcResult replay = postEvent("key-1", "a.b", "{\"y\":[1,2],\"x\":1}");
    assertThat(replay.getResponse().getStatus()).isEqualTo(200);
    assertThat(body(replay).get("id")).isEqualTo(body(first).get("id"));
    assertThat(body(replay).get("deliveries")).hasSize(1);
    assertThat(deliveries.count()).isEqualTo(1);

    MvcResult conflictPayload = postEvent("key-1", "a.b", "{\"x\":2}");
    assertThat(conflictPayload.getResponse().getStatus()).isEqualTo(409);
    MvcResult conflictType = postEvent("key-1", "a.c", "{\"x\":1,\"y\":[1,2]}");
    assertThat(conflictType.getResponse().getStatus()).isEqualTo(409);
    assertThat(events.count()).isEqualTo(1);
  }

  @Test
  void concurrentRequestsWithSameKeyCreateOneEvent() throws Exception {
    createEndpoint("\"*\"", true);
    ExecutorService pool = Executors.newFixedThreadPool(6);
    try {
      List<Future<Integer>> futures =
          java.util.stream.IntStream.range(0, 6)
              .mapToObj(
                  i ->
                      pool.submit(
                          (Callable<Integer>)
                              () ->
                                  postEvent("race", "r.e", "{\"n\":1}").getResponse().getStatus()))
              .toList();
      int accepted = 0;
      for (Future<Integer> f : futures) {
        int s = f.get();
        assertThat(s).isIn(200, 202);
        if (s == 202) {
          accepted++;
        }
      }
      assertThat(accepted).isEqualTo(1);
    } finally {
      pool.shutdownNow();
    }
    assertThat(events.count()).isEqualTo(1);
    assertThat(deliveries.count()).isEqualTo(1);
  }

  @Test
  void eventsAreListedAndFilteredAndValidated() throws Exception {
    postEvent(null, "t.one", "{}");
    postEvent(null, "t.two", "{}");
    mvc.perform(get("/api/v1/events"))
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[0].eventType").value("t.two"));
    mvc.perform(get("/api/v1/events?eventType=t.one"))
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(get("/api/v1/events/" + UUID.randomUUID())).andExpect(status().isNotFound());
    assertThat(postEvent(null, "t.one", "[1]").getResponse().getStatus()).isEqualTo(400);
    assertThat(postEvent(null, "*", "{}").getResponse().getStatus()).isEqualTo(400);
    assertThat(postEvent(null, "bad type", "{}").getResponse().getStatus()).isEqualTo(400);
  }

  @Test
  void paginationBoundsAreEnforced() throws Exception {
    for (String q : List.of("size=0", "size=101", "page=-1")) {
      mvc.perform(get("/api/v1/endpoints?" + q)).andExpect(status().isBadRequest());
      mvc.perform(get("/api/v1/events?" + q)).andExpect(status().isBadRequest());
      mvc.perform(get("/api/v1/deliveries?" + q)).andExpect(status().isBadRequest());
    }
    mvc.perform(get("/api/v1/endpoints?size=100&page=3"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.page").value(3))
        .andExpect(jsonPath("$.size").value(100));
  }

  @Test
  void openApiSpecIsServed() throws Exception {
    mvc.perform(get("/v3/api-docs"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.info.title").value("hookrelay API"));
  }
}
