package com.segurplus.ai;

import java.util.concurrent.Executors;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.support.TaskExecutorAdapter;
import org.springframework.web.servlet.config.annotation.AsyncSupportConfigurer;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * El chat responde por SSE con {@code StreamingResponseBody}: el cuerpo se escribe en hilos virtuales (la generación es E/S
 * bloqueante) y el tope de la petición asíncrona queda algo por encima del tope de generación de 120 s (ADR 0003).
 */
@Configuration
class AiWebConfig implements WebMvcConfigurer {

    @Override
    public void configureAsyncSupport(AsyncSupportConfigurer configurer) {
        configurer.setTaskExecutor(new TaskExecutorAdapter(Executors.newVirtualThreadPerTaskExecutor()));
        configurer.setDefaultTimeout(150_000);
    }
}
