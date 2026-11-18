package io.github.joseferrao20.hookrelay.event;

import io.github.joseferrao20.hookrelay.common.EventTypes;
import io.github.joseferrao20.hookrelay.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/events")
public class EventController {

  private final EventService service;

  public EventController(EventService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<EventResponse> create(
      @RequestHeader(name = "Idempotency-Key", required = false) @Size(min = 1, max = 255)
          String idempotencyKey,
      @Valid @RequestBody CreateEventRequest request) {
    EventCreationResult result = service.create(idempotencyKey, request);
    if (!result.created()) {
      return ResponseEntity.ok(result.event());
    }
    return ResponseEntity.status(HttpStatus.ACCEPTED)
        .location(URI.create("/api/v1/events/" + result.event().id()))
        .body(result.event());
  }

  @GetMapping
  public PageResponse<EventSummaryResponse> list(
      @RequestParam(required = false) @Pattern(regexp = EventTypes.PATTERN) String eventType,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.list(eventType, page, size);
  }

  @GetMapping("/{id}")
  public EventResponse get(@PathVariable UUID id) {
    return service.get(id);
  }
}
