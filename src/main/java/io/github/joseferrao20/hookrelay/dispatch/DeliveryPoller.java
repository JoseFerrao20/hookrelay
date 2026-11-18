package io.github.joseferrao20.hookrelay.dispatch;

import io.github.joseferrao20.hookrelay.delivery.DeliveryRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/** Leases due deliveries in Postgres and publishes them to RabbitMQ with publisher confirms. */
@Component
@ConditionalOnProperty(
    name = "hookrelay.dispatch.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class DeliveryPoller {

  private static final Logger log = LoggerFactory.getLogger(DeliveryPoller.class);
  private static final long CONFIRM_TIMEOUT_MS = 10_000;

  private final DeliveryRepository deliveries;
  private final RabbitTemplate rabbit;
  private final DispatchProperties props;
  private final Clock clock;
  private final TransactionTemplate tx;

  public DeliveryPoller(
      DeliveryRepository deliveries,
      RabbitTemplate rabbit,
      DispatchProperties props,
      Clock clock,
      TransactionTemplate tx) {
    this.deliveries = deliveries;
    this.rabbit = rabbit;
    this.props = props;
    this.clock = clock;
    this.tx = tx;
  }

  @Scheduled(fixedDelayString = "${hookrelay.dispatch.poll-interval:1s}")
  public void scheduledPoll() {
    try {
      poll();
    } catch (RuntimeException e) {
      log.warn("Delivery poll failed, will retry on the next tick: {}", e.toString());
    }
  }

  /** Runs one poll cycle; returns the number of deliveries published. */
  public int poll() {
    Integer published =
        tx.execute(
            status -> {
              Instant now = clock.instant();
              List<Object[]> leased =
                  deliveries.leaseDue(now, now.plus(props.lease()), props.batchSize());
              if (leased.isEmpty()) {
                return 0;
              }
              rabbit.invoke(
                  ops -> {
                    for (Object[] row : leased) {
                      ops.convertAndSend(
                          DispatchConfig.EXCHANGE,
                          DispatchConfig.ROUTING_KEY,
                          new DeliveryMessage((UUID) row[0], ((Number) row[1]).longValue()));
                    }
                    ops.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
                    return null;
                  });
              return leased.size();
            });
    int count = published == null ? 0 : published;
    if (count > 0) {
      log.debug("Published {} due deliveries", count);
    }
    return count;
  }
}
