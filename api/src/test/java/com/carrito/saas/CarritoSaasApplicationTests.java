package com.carrito.saas;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Whole-application wiring contract.
 *
 * <p>This is the foundation of the test suite, not a unit test. It boots every
 * auto-configuration, every bean across all modules, Spring Security, JPA and
 * the WebSocket broker, and fails if any of them cannot be constructed. It is
 * deliberately the first test: every future feature depends on this class of
 * failure being caught in CI instead of in production.</p>
 *
 * <p>It requires a reachable PostgreSQL matching
 * {@code api/src/main/resources/application.yml} (localhost:5432, database
 * {@code carrito_db}, user {@code admin}). CI provisions exactly that as a
 * service container.</p>
 */
@SpringBootTest
class CarritoSaasApplicationTests {

    @Test
    void contextLoads() {
        // An empty body is intentional. The assertion is that startup completes:
        // if any bean in any of the seven modules is misconfigured, the context
        // fails to refresh and this test fails.
    }
}
