package io.github.joseferrao20.hookrelay.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.joseferrao20.hookrelay.security.SsrfDnsResolver;
import io.github.joseferrao20.hookrelay.security.SsrfGuard;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration
@EnableScheduling
@EnableConfigurationProperties({DispatchProperties.class, RetryProperties.class})
public class DispatchConfig {

  private static final Logger log = LoggerFactory.getLogger(DispatchConfig.class);

  public static final String EXCHANGE = "hookrelay.deliveries";
  public static final String QUEUE = "hookrelay.deliveries.work";
  public static final String ROUTING_KEY = "dispatch";
  public static final String DLX = "hookrelay.deliveries.dlx";
  public static final String DLQ = "hookrelay.deliveries.dlq";
  public static final String DEAD_ROUTING_KEY = "dead";

  /** The E2 work queue, replaced by {@link #QUEUE}; deleted at startup. */
  public static final String LEGACY_QUEUE = "hookrelay.deliveries.dispatch";

  @Bean
  Declarables dispatchTopology() {
    DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
    DirectExchange dlx = new DirectExchange(DLX, true, false);
    Queue work =
        QueueBuilder.durable(QUEUE)
            .withArgument("x-dead-letter-exchange", DLX)
            .withArgument("x-dead-letter-routing-key", DEAD_ROUTING_KEY)
            .build();
    Queue dlq = QueueBuilder.durable(DLQ).build();
    return new Declarables(
        exchange,
        dlx,
        work,
        dlq,
        BindingBuilder.bind(work).to(exchange).with(ROUTING_KEY),
        BindingBuilder.bind(dlq).to(dlx).with(DEAD_ROUTING_KEY));
  }

  /**
   * Removes the E2 queue so its binding stops copying messages into a queue nobody consumes. Runs
   * on startup; deleting is idempotent. In-flight messages lost here are recovered by the poller.
   */
  @Bean
  SmartInitializingSingleton legacyQueueCleanup(AmqpAdmin admin) {
    return () -> {
      try {
        if (admin.deleteQueue(LEGACY_QUEUE)) {
          log.info("Deleted legacy queue {}", LEGACY_QUEUE);
        }
      } catch (RuntimeException e) {
        log.warn("Could not delete legacy queue {}: {}", LEGACY_QUEUE, e.toString());
      }
    };
  }

  @Bean
  MessageConverter messageConverter(ObjectMapper objectMapper) {
    return new Jackson2JsonMessageConverter(objectMapper);
  }

  /**
   * HTTP client for webhooks: Apache HttpClient 5 with a resolver that refuses blocked addresses,
   * so connections only ever use checked addresses. No redirects, no automatic retries.
   */
  @Bean
  RestClient webhookRestClient(DispatchProperties props, SsrfGuard ssrfGuard) {
    var connectionManager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setDnsResolver(new SsrfDnsResolver(ssrfGuard))
            .setDefaultConnectionConfig(
                ConnectionConfig.custom()
                    .setConnectTimeout(Timeout.of(props.connectTimeout()))
                    .setSocketTimeout(Timeout.of(props.requestTimeout()))
                    .build())
            .build();
    CloseableHttpClient httpClient =
        HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(
                RequestConfig.custom()
                    .setResponseTimeout(Timeout.of(props.requestTimeout()))
                    .setRedirectsEnabled(false)
                    .build())
            .disableRedirectHandling()
            .disableAutomaticRetries()
            .disableCookieManagement()
            .build();
    return RestClient.builder()
        .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
        .build();
  }
}
