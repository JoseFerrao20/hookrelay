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

  /**
   * Runs one poll cycle; returns the number of deliveries published.
   *
   * <p>The lease transaction is finished before anything is published: a worker can receive a
   * message before the poller's transaction would end, miss the bumped version and drop the
   * message. If publishing fails, the leases are released right away (best effort); otherwise they
   * expire and the rows are picked up again.
   */
  public int poll() {
    Instant now = clock.instant();
    List<Object[]> leased =
        tx.execute(status -> deliveries.leaseDue(now, now.plus(props.lease()), props.batchSize()));
    if (leased == null || leased.isEmpty()) {
      return 0;
    }
    try {
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
    } catch (RuntimeException e) {
      releaseLeases(leased);
      throw e;
    }
    log.debug("Published {} due deliveries", leased.size());
    return leased.size();
  }

  private void releaseLeases(List<Object[]> leased) {
    try {
      tx.executeWithoutResult(
          status -> {
            for (Object[] row : leased) {
              deliveries.releaseLease((UUID) row[0], ((Number) row[1]).longValue());
            }
          });
    } catch (RuntimeException e) {
      log.warn(
          "Could not release leases after a failed publish; they will expire: {}", e.toString());
    }
  }
}
