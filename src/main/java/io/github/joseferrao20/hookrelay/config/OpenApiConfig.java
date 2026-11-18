package io.github.joseferrao20.hookrelay.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

  @Bean
  public OpenAPI hookrelayOpenApi() {
    return new OpenAPI().info(new Info().title("hookrelay API").version("v1"));
  }
}
