package io.github.joseferrao20.hookrelay.event;

import io.github.joseferrao20.hookrelay.common.ConflictException;
import io.github.joseferrao20.hookrelay.common.NotFoundException;
import io.github.joseferrao20.hookrelay.common.PageResponse;
import io.github.joseferrao20.hookrelay.common.Pagination;
import io.github.joseferrao20.hookrelay.delivery.Delivery;
import io.github.joseferrao20.hookrelay.delivery.DeliveryRepository;
import io.github.joseferrao20.hookrelay.endpoint.Endpoint;
import io.github.joseferrao20.hookrelay.endpoint.EndpointRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class EventService {

  private final EventRepository events;
  private final EndpointRepository endpoints;
  private final DeliveryRepository deliveries;
  private final Clock clock;
  private final TransactionTemplate tx;

  public EventService(
      EventRepository events,
      EndpointRepository endpoints,
      DeliveryRepository deliveries,
      Clock clock,
      PlatformTransactionManager transactionManager) {
    this.events = events;
    this.endpoints = endpoints;
    this.deliveries = deliveries;
    this.clock = clock;
    this.tx = new TransactionTemplate(transactionManager);
  }

  /**
   * Stores the event and fans it out into PENDING deliveries in one transaction. This method is
   * deliberately not {@code @Transactional}: when two requests race on the same idempotency key the
   * unique constraint aborts the loser's transaction, and the loser must re-read the winner's row
   * in a fresh transaction.
   */
  public EventCreationResult create(String idempotencyKey, CreateEventRequest request) {
    if (idempotencyKey != null) {
      EventCreationResult replay = tx.execute(s -> replay(idempotencyKey, request));
      if (replay != null) {
        return replay;
      }
    }
    try {
      return tx.execute(s -> insertAndFanOut(idempotencyKey, request));
    } catch (DataIntegrityViolationException e) {
      if (idempotencyKey == null) {
        throw e;
      }
      EventCreationResult replay = tx.execute(s -> replay(idempotencyKey, request));
      if (replay == null) {
        throw e;
      }
      return replay;
    }
  }

  @Transactional(readOnly = true)
  public PageResponse<EventSummaryResponse> list(String eventType, int page, int size) {
    Pageable pageable = Pagination.newestFirst(page, size);
    Page<Event> result =
        eventType == null ? events.findAll(pageable) : events.findByEventType(eventType, pageable);
    return PageResponse.of(result, EventSummaryResponse::from);
  }

  @Transactional(readOnly = true)
  public EventResponse get(UUID id) {
    Event event =
        events.findById(id).orElseThrow(() -> new NotFoundException("Event " + id + " not found"));
    return toResponse(event, deliveries.findByEventIdOrderByCreatedAtAscIdAsc(id));
  }

  private EventCreationResult replay(String key, CreateEventRequest request) {
    return events
        .findByIdempotencyKey(key)
        .map(
            existing -> {
              if (!existing.getEventType().equals(request.eventType())
                  || !existing.getPayload().equals(request.payload())) {
                throw new ConflictException(
                    "Idempotency-Key was already used with a different event type or payload.");
              }
              return new EventCreationResult(
                  toResponse(
                      existing, deliveries.findByEventIdOrderByCreatedAtAscIdAsc(existing.getId())),
                  false);
            })
        .orElse(null);
  }

  private EventCreationResult insertAndFanOut(String key, CreateEventRequest request) {
    Instant now = clock.instant();
    Event event = events.saveAndFlush(new Event(request.eventType(), request.payload(), key, now));
    List<Delivery> created =
        endpoints.findSubscribers(request.eventType()).stream()
            .map(Endpoint::getId)
            .map(endpointId -> new Delivery(event.getId(), endpointId, now))
            .toList();
    return new EventCreationResult(toResponse(event, deliveries.saveAll(created)), true);
  }

  private static EventResponse toResponse(Event event, List<Delivery> deliveries) {
    return new EventResponse(
        event.getId(),
        event.getEventType(),
        event.getPayload(),
        event.getIdempotencyKey(),
        event.getCreatedAt(),
        deliveries.stream()
            .map(d -> new EventDeliveryResponse(d.getId(), d.getEndpointId(), d.getStatus()))
            .toList());
  }
}
