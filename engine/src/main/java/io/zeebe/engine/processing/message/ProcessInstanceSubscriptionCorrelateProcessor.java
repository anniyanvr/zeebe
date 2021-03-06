/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.processing.message;

import static io.zeebe.util.buffer.BufferUtil.bufferAsString;

import io.zeebe.engine.processing.common.EventHandle;
import io.zeebe.engine.processing.deployment.model.element.ExecutableFlowElement;
import io.zeebe.engine.processing.message.command.SubscriptionCommandSender;
import io.zeebe.engine.processing.streamprocessor.TypedRecord;
import io.zeebe.engine.processing.streamprocessor.TypedRecordProcessor;
import io.zeebe.engine.processing.streamprocessor.sideeffect.SideEffectProducer;
import io.zeebe.engine.processing.streamprocessor.writers.StateWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedRejectionWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedResponseWriter;
import io.zeebe.engine.processing.streamprocessor.writers.TypedStreamWriter;
import io.zeebe.engine.processing.streamprocessor.writers.Writers;
import io.zeebe.engine.state.immutable.ElementInstanceState;
import io.zeebe.engine.state.immutable.ProcessInstanceSubscriptionState;
import io.zeebe.engine.state.immutable.ProcessState;
import io.zeebe.engine.state.message.ProcessInstanceSubscription;
import io.zeebe.engine.state.mutable.MutableZeebeState;
import io.zeebe.protocol.impl.record.value.message.ProcessInstanceSubscriptionRecord;
import io.zeebe.protocol.impl.record.value.processinstance.ProcessInstanceRecord;
import io.zeebe.protocol.record.RejectionType;
import io.zeebe.protocol.record.intent.ProcessInstanceSubscriptionIntent;
import java.util.function.Consumer;
import org.agrona.DirectBuffer;

public final class ProcessInstanceSubscriptionCorrelateProcessor
    implements TypedRecordProcessor<ProcessInstanceSubscriptionRecord> {

  private static final String NO_EVENT_OCCURRED_MESSAGE =
      "Expected to correlate a process instance subscription with element key '%d' and message name '%s', "
          + "but the subscription is not active anymore";
  private static final String NO_SUBSCRIPTION_FOUND_MESSAGE =
      "Expected to correlate process instance subscription with element key '%d' and message name '%s', "
          + "but no such subscription was found";
  private static final String ALREADY_CLOSING_MESSAGE =
      "Expected to correlate process instance subscription with element key '%d' and message name '%s', "
          + "but it is already closing";

  private final ProcessInstanceSubscriptionState subscriptionState;
  private final SubscriptionCommandSender subscriptionCommandSender;
  private final ProcessState processState;
  private final ElementInstanceState elementInstanceState;
  private final StateWriter stateWriter;
  private final TypedRejectionWriter rejectionWriter;
  private final EventHandle eventHandle;

  public ProcessInstanceSubscriptionCorrelateProcessor(
      final ProcessInstanceSubscriptionState subscriptionState,
      final SubscriptionCommandSender subscriptionCommandSender,
      final MutableZeebeState zeebeState,
      final Writers writers) {
    this.subscriptionState = subscriptionState;
    this.subscriptionCommandSender = subscriptionCommandSender;
    processState = zeebeState.getProcessState();
    elementInstanceState = zeebeState.getElementInstanceState();
    stateWriter = writers.state();
    rejectionWriter = writers.rejection();

    eventHandle =
        new EventHandle(zeebeState.getKeyGenerator(), zeebeState.getEventScopeInstanceState());
  }

  @Override
  public void processRecord(
      final TypedRecord<ProcessInstanceSubscriptionRecord> command,
      final TypedResponseWriter responseWriter,
      final TypedStreamWriter streamWriter,
      final Consumer<SideEffectProducer> sideEffect) {

    final var subscriptionRecord = command.getValue();
    final var elementInstanceKey = subscriptionRecord.getElementInstanceKey();

    final ProcessInstanceSubscription subscription =
        subscriptionState.getSubscription(
            elementInstanceKey, subscriptionRecord.getMessageNameBuffer());

    if (subscription == null) {
      rejectCommand(command, RejectionType.NOT_FOUND, NO_SUBSCRIPTION_FOUND_MESSAGE);

    } else if (subscription.isClosing()) {
      rejectCommand(command, RejectionType.INVALID_STATE, ALREADY_CLOSING_MESSAGE);

    } else {
      final var elementInstance = elementInstanceState.getInstance(elementInstanceKey);
      final var canTriggerElement = eventHandle.canTriggerElement(elementInstance);

      if (!canTriggerElement) {
        rejectCommand(command, RejectionType.INVALID_STATE, NO_EVENT_OCCURRED_MESSAGE);

      } else {
        // TODO (saig0): reuse the subscription record in the state (#6533)
        subscriptionRecord
            .setElementId(subscription.getTargetElementId())
            .setInterrupting(subscription.shouldCloseOnCorrelate());

        stateWriter.appendFollowUpEvent(
            command.getKey(), ProcessInstanceSubscriptionIntent.CORRELATED, subscriptionRecord);

        final var catchEvent =
            getCatchEvent(elementInstance.getValue(), subscriptionRecord.getElementIdBuffer());
        eventHandle.activateElement(
            stateWriter, catchEvent, elementInstanceKey, elementInstance.getValue());

        sendAcknowledgeCommand(subscriptionRecord);
      }
    }
  }

  private ExecutableFlowElement getCatchEvent(
      final ProcessInstanceRecord elementRecord, final DirectBuffer elementId) {
    return processState.getFlowElement(
        elementRecord.getProcessDefinitionKey(), elementId, ExecutableFlowElement.class);
  }

  private void rejectCommand(
      final TypedRecord<ProcessInstanceSubscriptionRecord> command,
      final RejectionType rejectionType,
      final String reasonTemplate) {

    final var subscription = command.getValue();
    final var reason =
        String.format(
            reasonTemplate,
            subscription.getElementInstanceKey(),
            bufferAsString(subscription.getMessageNameBuffer()));

    rejectionWriter.appendRejection(command, rejectionType, reason);

    sendRejectionCommand(subscription);
  }

  private void sendAcknowledgeCommand(final ProcessInstanceSubscriptionRecord subscription) {
    subscriptionCommandSender.correlateMessageSubscription(
        subscription.getSubscriptionPartitionId(),
        subscription.getProcessInstanceKey(),
        subscription.getElementInstanceKey(),
        subscription.getBpmnProcessIdBuffer(),
        subscription.getMessageNameBuffer());
  }

  private void sendRejectionCommand(final ProcessInstanceSubscriptionRecord subscription) {
    subscriptionCommandSender.rejectCorrelateMessageSubscription(
        subscription.getProcessInstanceKey(),
        subscription.getBpmnProcessIdBuffer(),
        subscription.getMessageKey(),
        subscription.getMessageNameBuffer(),
        subscription.getCorrelationKeyBuffer());
  }
}
