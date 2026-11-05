package com.x7x24.it;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

@TestConfiguration
public class FakeConfig { @Bean @Primary FakeTools fakeTools() { return new FakeTools(); } }
