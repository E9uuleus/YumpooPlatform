package com.yumpoo.platform.operations.domain;

import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;

class AlertEvaluatorTest {
    final Instant start=Instant.parse("2026-09-26T12:00:00Z");
    final AlertEvaluator evaluator=new AlertEvaluator();
    AlertEvaluator.Decision evaluate(int seconds, Double value,String active) {
        return evaluator.evaluate("cpu",1,AlertRuleType.HOST_CPU_HIGH,true,.85,.95,30,value,active,start.plusSeconds(seconds));
    }
    @Test void requiresContinuousSamplesAndUnknownBreaksPendingAndRecovery() {
        assertThat(evaluate(0,.9,null)).isNull();assertThat(evaluate(15,.9,null)).isNull();
        assertThat(evaluate(30,null,null)).isNull();assertThat(evaluate(45,.9,null)).isNull();
        assertThat(evaluate(60,.9,null)).isNull();assertThat(evaluate(75,.9,null).event()).isEqualTo("FIRED");
        assertThat(evaluate(90,.99,"WARNING").event()).isEqualTo("ESCALATED");
        for(int t=105;t<=135;t+=15)assertThat(evaluate(t,.2,"CRITICAL")).isNull();
        assertThat(evaluate(150,null,"CRITICAL")).isNull();
        for(int t=165;t<225;t+=15)assertThat(evaluate(t,.2,"CRITICAL")).isNull();
        assertThat(evaluate(225,.2,"CRITICAL").event()).isEqualTo("RESOLVED");
    }
    @Test void longGapVersionChangeAndRestartDoNotCarryPendingTime() {
        evaluate(0,.9,null);assertThat(evaluate(60,.9,null)).isNull();
        assertThat(evaluator.evaluate("cpu",2,AlertRuleType.HOST_CPU_HIGH,true,.85,.95,30,.9,null,start.plusSeconds(90))).isNull();
        assertThat(new AlertEvaluator().evaluate("cpu",2,AlertRuleType.HOST_CPU_HIGH,true,.85,.95,30,.9,null,start.plusSeconds(120))).isNull();
    }
    @Test void diskHasReversedThresholdsAndDisablingResolvesImmediately() {
        assertThat(AlertRuleType.DISK_LOW.valid(.2,.1,60)).isTrue();
        assertThat(AlertRuleType.DISK_LOW.valid(.1,.2,60)).isFalse();
        assertThat(AlertRuleType.HOST_CPU_HIGH.valid(Double.NaN,.95,30)).isFalse();
        assertThat(AlertRuleType.OUTBOX_DEAD.valid(1.1,10d,0)).isFalse();
        assertThat(AlertRuleType.DISK_LOW.severity(.08,.2,.1)).isEqualTo("CRITICAL");
        assertThat(evaluator.evaluate("disk",1,AlertRuleType.DISK_LOW,false,.2,.1,60,.05,"CRITICAL",start).event()).isEqualTo("RULE_DISABLED");
    }
}

