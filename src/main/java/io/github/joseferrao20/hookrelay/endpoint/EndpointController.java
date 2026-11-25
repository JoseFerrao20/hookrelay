package io.github.joseferrao20.hookrelay.endpoint;

import io.github.joseferrao20.hookrelay.common.PageResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/endpoints")
public class EndpointController {

  private final EndpointService service;

  public EndpointController(EndpointService service) {
    this.service = service;
  }

  @PostMapping
  public ResponseEntity<EndpointCreatedResponse> create(
      @Valid @RequestBody CreateEndpointRequest request) {
    EndpointCreatedResponse created = service.create(request);
    return ResponseEntity.created(URI.create("/api/v1/endpoints/" + created.id())).body(created);
  }

  @GetMapping
  public PageResponse<EndpointResponse> list(
      @RequestParam(defaultValue = "0") @Min(0) int page,
      @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {
    return service.list(page, size);
  }

  @GetMapping("/{id}")
  public EndpointResponse get(@PathVariable UUID id) {
    return service.get(id);
  }

  @PatchMapping("/{id}")
  public EndpointResponse update(
      @PathVariable UUID id, @Valid @RequestBody UpdateEndpointRequest request) {
    return service.update(id, request);
  }

  @PostMapping("/{id}/secret/rotate")
  public RotateSecretResponse rotateSecret(
      @PathVariable UUID id, @Valid @RequestBody(required = false) RotateSecretRequest request) {
    int grace =
        request == null
            ? RotateSecretRequest.DEFAULT_GRACE_SECONDS
            : request.graceSecondsOrDefault();
    return service.rotateSecret(id, grace);
  }

  @DeleteMapping("/{id}")
  public ResponseEntity<Void> delete(@PathVariable UUID id) {
    service.delete(id);
    return ResponseEntity.noContent().build();
  }
}
