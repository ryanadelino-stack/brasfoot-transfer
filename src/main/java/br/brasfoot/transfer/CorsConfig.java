package br.brasfoot.transfer;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Configuração de CORS via variável de ambiente ALLOWED_ORIGINS.
 *
 * Em desenvolvimento (local): deixe em branco ou coloque "*"
 * Em produção (Render):       coloque a URL do seu app no Lovable
 *                             ex: https://meu-app.lovable.app
 *
 * Múltiplas origens separadas por vírgula:
 *   ALLOWED_ORIGINS=https://meu-app.lovable.app,https://outro-dominio.com
 */
@Configuration
public class CorsConfig {

  @Value("${ALLOWED_ORIGINS:*}")
  private String allowedOrigins;

  @Bean
  public WebMvcConfigurer corsConfigurer() {
    return new WebMvcConfigurer() {
      @Override
      public void addCorsMappings(CorsRegistry registry) {
        String[] origins = allowedOrigins.equals("*")
            ? new String[]{"*"}
            : allowedOrigins.split(",");

        registry.addMapping("/api/**")
            .allowedOrigins(origins)
            .allowedMethods("GET", "POST", "OPTIONS")
            .allowedHeaders("*")
            .maxAge(3600);
      }
    };
  }
}
