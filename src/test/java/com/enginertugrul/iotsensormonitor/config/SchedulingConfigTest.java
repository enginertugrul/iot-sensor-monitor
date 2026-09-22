package com.enginertugrul.iotsensormonitor.config;

import com.enginertugrul.iotsensormonitor.scheduler.SensorReadingStreamScheduler;
import com.enginertugrul.iotsensormonitor.service.reading.stream.SensorReadingStreamService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class SchedulingConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(SchedulingConfig.class)
            .withBean(SensorReadingStreamService.class,() -> mock(SensorReadingStreamService.class));

    @Test
    void enablesSchedulingWhenPropertyIsMissing() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context).hasSingleBean(SensorReadingStreamScheduler.class);
        });
    }

    @Test
    void enablesSchedulingWhenPropertyIsTrue() {
        contextRunner.withPropertyValues("app.scheduling.enabled=true").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(context).hasSingleBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context).hasSingleBean(SensorReadingStreamScheduler.class);
        });
    }

    @Test
    void disablesSchedulingWhenPropertyIsFalse() {
        contextRunner.withPropertyValues("app.scheduling.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(SchedulingConfig.class);
            assertThat(context).doesNotHaveBean(ScheduledAnnotationBeanPostProcessor.class);
            assertThat(context).doesNotHaveBean(SensorReadingStreamScheduler.class);
        });
    }
}