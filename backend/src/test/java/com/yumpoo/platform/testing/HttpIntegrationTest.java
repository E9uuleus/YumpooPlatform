package com.yumpoo.platform.testing;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** 随机端口启动 Web 服务器的标准集成测试上下文。 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "yumpoo.outbox.enabled=false",
        "yumpoo.projects.deletion.purge-poll-delay=1d",
        "yumpoo.attachments.maintenance-initial-delay=1d"
})
@Import(PostgreSqlTestContainerConfiguration.class)
public @interface HttpIntegrationTest {
}
