package io.github.joseferrao20.hookrelay.delivery;

import io.github.joseferrao20.hookrelay.common.PageResponse;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/deliveries")
public class DeliveryController {

  private final DeliveryService service;

  public DeliveryController(DeliveryService service) {
    this.service = service;
  }

  @GetMapping
  public PageResponse<DeliveryResponse> list(
      @RequestParam(required = false) UUID eventId,
      @RequestParam(required = false) UUID endpointId,
      @RequestParam(required = false) DeliveryStatus status,
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.list(eventId, endpointId, status, page, size);
  }

  @GetMapping("/{id}")
  public DeliveryResponse get(@PathVariable UUID id) {
    return service.get(id);
  }
}
