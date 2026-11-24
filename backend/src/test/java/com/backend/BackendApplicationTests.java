package com.backend;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = "app.analysis.scheduler.enabled=false")
class BackendApplicationTests {

    @Test
    void contextLoads() {
    }

}
