/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.kafkaclients.v0_11;

import static io.opentelemetry.javaagent.bootstrap.Java8BytecodeBridge.currentContext;
import static io.opentelemetry.javaagent.instrumentation.kafkaclients.v0_11.KafkaSingletons.consumerReceiveInstrumenter;
import static io.opentelemetry.javaagent.instrumentation.kafkaclients.v0_11.KafkaSingletons.enhanceConfig;
import static net.bytebuddy.matcher.ElementMatchers.isConstructor;
import static net.bytebuddy.matcher.ElementMatchers.isPublic;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

import io.opentelemetry.context.Context;
import io.opentelemetry.instrumentation.api.internal.InstrumenterUtil;
import io.opentelemetry.instrumentation.api.util.VirtualField;
import io.opentelemetry.instrumentation.kafka.internal.ConsumerAndRecord;
import io.opentelemetry.instrumentation.kafka.internal.Timer;
import io.opentelemetry.javaagent.bootstrap.kafka.KafkaClientsConsumerProcessTracing;
import io.opentelemetry.javaagent.extension.instrumentation.TypeInstrumentation;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import java.time.Duration;
import java.util.Map;
import java.util.Properties;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.matcher.ElementMatcher;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;

public class KafkaConsumerInstrumentation implements TypeInstrumentation {

  @Override
  public ElementMatcher<TypeDescription> typeMatcher() {
    return named("org.apache.kafka.clients.consumer.KafkaConsumer");
  }

  @Override
  public void transform(TypeTransformer transformer) {
    transformer.applyAdviceToMethod(
        isConstructor().and(takesArgument(0, Map.class)),
        this.getClass().getName() + "$ConstructorMapAdvice");
    transformer.applyAdviceToMethod(
        isConstructor().and(takesArgument(0, Properties.class)),
        this.getClass().getName() + "$ConstructorPropertiesAdvice");
    transformer.applyAdviceToMethod(
        named("poll")
            .and(isPublic())
            .and(takesArguments(1))
            .and(takesArgument(0, long.class).or(takesArgument(0, Duration.class)))
            .and(returns(named("org.apache.kafka.clients.consumer.ConsumerRecords"))),
        this.getClass().getName() + "$PollAdvice");
  }

  @SuppressWarnings("unused")
  public static class ConstructorMapAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) Map<String, Object> config) {
      enhanceConfig(config);
    }
  }

  @SuppressWarnings("unused")
  public static class ConstructorPropertiesAdvice {

    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static void onEnter(@Advice.Argument(0) Properties config) {
      enhanceConfig(config);
    }
  }

  @SuppressWarnings("unused")
  public static class PollAdvice {
    @Advice.OnMethodEnter(suppress = Throwable.class)
    public static Timer onEnter() {
      return Timer.start();
    }

    @Advice.OnMethodExit(suppress = Throwable.class, onThrowable = Throwable.class)
    public static void onExit(
        @Advice.Enter Timer timer,
        @Advice.This Consumer<?, ?> consumer,
        @Advice.Return ConsumerRecords<?, ?> records,
        @Advice.Thrown Throwable error) {

      // don't create spans when no records were received
      if (records == null || records.isEmpty()) {
        return;
      }

      // we're attaching the consumer to the records to be able to retrieve things like consumer
      // group or clientId later
      VirtualField<ConsumerRecords<?, ?>, Consumer<?, ?>> consumerRecordsConsumer =
          VirtualField.find(ConsumerRecords.class, Consumer.class);
      consumerRecordsConsumer.set(records, consumer);

      Context parentContext = currentContext();
      ConsumerAndRecord<ConsumerRecords<?, ?>> request =
          ConsumerAndRecord.create(consumer, records);

      if (consumerReceiveInstrumenter().shouldStart(parentContext, request)) {
        // disable process tracing and store the receive span for each individual record too
        boolean previousValue = KafkaClientsConsumerProcessTracing.setEnabled(false);
        try {
          Context context =
              InstrumenterUtil.startAndEnd(
                  consumerReceiveInstrumenter(),
                  parentContext,
                  request,
                  null,
                  error,
                  timer.startTime(),
                  timer.now());

          // we're storing the context of the receive span so that process spans can use it as
          // parent context even though the span has ended
          // this is the suggested behavior according to the spec batch receive scenario:
          // https://github.com/open-telemetry/opentelemetry-specification/blob/main/specification/trace/semantic_conventions/messaging.md#batch-receiving
          VirtualField<ConsumerRecords<?, ?>, Context> consumerRecordsContext =
              VirtualField.find(ConsumerRecords.class, Context.class);
          consumerRecordsContext.set(records, context);

          VirtualField<ConsumerRecord<?, ?>, Context> consumerRecordContext =
              VirtualField.find(ConsumerRecord.class, Context.class);
          for (ConsumerRecord<?, ?> record : records) {
            consumerRecordContext.set(record, context);
          }
        } finally {
          KafkaClientsConsumerProcessTracing.setEnabled(previousValue);
        }
      }
    }
  }
}
