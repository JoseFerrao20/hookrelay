package io.github.joseferrao20.hookrelay.dispatch;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.http.HttpClient;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.web.client.RestClient;

@Configuration
@EnableScheduling
@EnableConfigurationProperties(DispatchProperties.class)
public class DispatchConfig {

  public static final String EXCHANGE = "hookrelay.deliveries";
  public static final String QUEUE = "hookrelay.deliveries.dispatch";
  public static final String ROUTING_KEY = "dispatch";

  @Bean
  Declarables dispatchTopology() {
    DirectExchange exchange = new DirectExchange(EXCHANGE, true, false);
    Queue queue = QueueBuilder.durable(QUEUE).build();
    Binding binding = BindingBuilder.bind(queue).to(exchange).with(ROUTING_KEY);
    return new Declarables(exchange, queue, binding);
  }

  @Bean
  MessageConverter messageConverter(ObjectMapper objectMapper) {
    return new Jackson2JsonMessageConverter(objectMapper);
  }

  @Bean
  RestClient webhookRestClient(DispatchProperties props) {
    HttpClient httpClient =
        HttpClient.newBuilder()
            .connectTimeout(props.connectTimeout())
            .followRedirects(HttpClient.Redirect.NEVER)
            .version(HttpClient.Version.HTTP_1_1)
            .build();
    JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
    factory.setReadTimeout(props.requestTimeout());
    return RestClient.builder().requestFactory(factory).build();
  }
}
