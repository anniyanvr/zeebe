/*
 * Copyright Camunda Services GmbH and/or licensed to Camunda Services GmbH under
 * one or more contributor license agreements. See the NOTICE file distributed
 * with this work for additional information regarding copyright ownership.
 * Licensed under the Zeebe Community License 1.1. You may not use this file
 * except in compliance with the Zeebe Community License 1.1.
 */
package io.zeebe.engine.state.appliers;

import io.zeebe.engine.state.TypedEventApplier;
import io.zeebe.engine.state.mutable.MutableProcessInstanceSubscriptionState;
import io.zeebe.protocol.impl.record.value.message.ProcessInstanceSubscriptionRecord;
import io.zeebe.protocol.record.intent.ProcessInstanceSubscriptionIntent;

public final class ProcessInstanceSubscriptionCreatedApplier
    implements TypedEventApplier<
        ProcessInstanceSubscriptionIntent, ProcessInstanceSubscriptionRecord> {

  private final MutableProcessInstanceSubscriptionState subscriptionState;

  public ProcessInstanceSubscriptionCreatedApplier(
      final MutableProcessInstanceSubscriptionState subscriptionState) {
    this.subscriptionState = subscriptionState;
  }

  @Override
  public void applyState(final long key, final ProcessInstanceSubscriptionRecord value) {

    // TODO (saig0): reuse the subscription record in the state (#6533)
    final var subscription =
        subscriptionState.getSubscription(
            value.getElementInstanceKey(), value.getMessageNameBuffer());

    subscriptionState.updateToOpenedState(subscription, subscription.getSubscriptionPartitionId());
  }
}
