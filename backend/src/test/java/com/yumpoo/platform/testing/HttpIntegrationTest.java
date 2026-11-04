package com.yumpoo.platform.testing;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 随机端口启动 Web 服务器的标准集成测试上下文。
 * 各上下文之间的测试数据没有隔离（多个测试类共用固定公司），不得借助本注解合并原本独立的上下文。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = "yumpoo.outbox.enabled=false")
@Import(PostgreSqlTestContainerConfiguration.class)
public @interface HttpIntegrationTest {
}
