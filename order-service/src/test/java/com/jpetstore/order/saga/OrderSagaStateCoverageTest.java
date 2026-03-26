/*
 *    Copyright 2010-2026 the original author or authors.
 *
 *    Licensed under the Apache License, Version 2.0 (the "License");
 *    you may not use this file except in compliance with the License.
 *    You may obtain a copy of the License at
 *
 *       https://www.apache.org/licenses/LICENSE-2.0
 *
 *    Unless required by applicable law or agreed to in writing, software
 *    distributed under the License is distributed on an "AS IS" BASIS,
 *    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *    See the License for the specific language governing permissions and
 *    limitations under the License.
 */
package com.jpetstore.order.saga;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link OrderSagaState} — the persisted state entity for the
 * orchestration-based Saga pattern that coordinates the distributed order
 * transaction across Order and Catalog services.
 *
 * <p>Covers constructors, getters/setters, lifecycle callbacks, status constants,
 * and toString.</p>
 */
class OrderSagaStateCoverageTest {

    @Nested
    @DisplayName("Construction and Defaults")
    class ConstructionTests {

        @Test
        @DisplayName("Default constructor creates state with null fields")
        void shouldCreateDefaultState() {
            OrderSagaState state = new OrderSagaState();
            assertThat(state.getSagaId()).isNull();
            assertThat(state.getOrderId()).isZero();
            assertThat(state.getCurrentStep()).isNull();
            assertThat(state.getStatus()).isNull();
            assertThat(state.getCreatedAt()).isNull();
            assertThat(state.getUpdatedAt()).isNull();
        }

        @Test
        @DisplayName("Parameterized constructor sets orderId, step, and status")
        void shouldCreateWithOrderIdStepAndStatus() {
            OrderSagaState state = new OrderSagaState(1001, OrderSagaStep.CREATE_ORDER,
                    OrderSagaState.STATUS_PENDING);
            assertThat(state.getOrderId()).isEqualTo(1001);
            assertThat(state.getCurrentStep()).isEqualTo(OrderSagaStep.CREATE_ORDER);
            assertThat(state.getStatus()).isEqualTo(OrderSagaState.STATUS_PENDING);
        }
    }

    @Nested
    @DisplayName("Getters and Setters")
    class GetterSetterTests {

        @Test
        @DisplayName("Set and get sagaId")
        void shouldSetAndGetSagaId() {
            OrderSagaState state = new OrderSagaState();
            state.setSagaId("saga-abc-123");
            assertThat(state.getSagaId()).isEqualTo("saga-abc-123");
        }

        @Test
        @DisplayName("Set and get orderId")
        void shouldSetAndGetOrderId() {
            OrderSagaState state = new OrderSagaState();
            state.setOrderId(2002);
            assertThat(state.getOrderId()).isEqualTo(2002);
        }

        @Test
        @DisplayName("Set and get currentStep")
        void shouldSetAndGetCurrentStep() {
            OrderSagaState state = new OrderSagaState();
            state.setCurrentStep(OrderSagaStep.RESERVE_INVENTORY);
            assertThat(state.getCurrentStep()).isEqualTo(OrderSagaStep.RESERVE_INVENTORY);
        }

        @Test
        @DisplayName("Set and get status")
        void shouldSetAndGetStatus() {
            OrderSagaState state = new OrderSagaState();
            state.setStatus(OrderSagaState.STATUS_COMPLETED);
            assertThat(state.getStatus()).isEqualTo(OrderSagaState.STATUS_COMPLETED);
        }

        @Test
        @DisplayName("Set and get createdAt")
        void shouldSetAndGetCreatedAt() {
            OrderSagaState state = new OrderSagaState();
            LocalDateTime now = LocalDateTime.now();
            state.setCreatedAt(now);
            assertThat(state.getCreatedAt()).isEqualTo(now);
        }

        @Test
        @DisplayName("Set and get updatedAt")
        void shouldSetAndGetUpdatedAt() {
            OrderSagaState state = new OrderSagaState();
            LocalDateTime now = LocalDateTime.now();
            state.setUpdatedAt(now);
            assertThat(state.getUpdatedAt()).isEqualTo(now);
        }
    }

    @Nested
    @DisplayName("Status Constants")
    class StatusConstantTests {

        @Test
        @DisplayName("STATUS_PENDING constant value is correct")
        void shouldHavePendingConstant() {
            assertThat(OrderSagaState.STATUS_PENDING).isEqualTo("PENDING");
        }

        @Test
        @DisplayName("STATUS_INVENTORY_RESERVED constant value is correct")
        void shouldHaveInventoryReservedConstant() {
            assertThat(OrderSagaState.STATUS_INVENTORY_RESERVED).isEqualTo("INVENTORY_RESERVED");
        }

        @Test
        @DisplayName("STATUS_COMPLETED constant value is correct")
        void shouldHaveCompletedConstant() {
            assertThat(OrderSagaState.STATUS_COMPLETED).isEqualTo("COMPLETED");
        }

        @Test
        @DisplayName("STATUS_COMPENSATING constant value is correct")
        void shouldHaveCompensatingConstant() {
            assertThat(OrderSagaState.STATUS_COMPENSATING).isEqualTo("COMPENSATING");
        }

        @Test
        @DisplayName("STATUS_FAILED constant value is correct")
        void shouldHaveFailedConstant() {
            assertThat(OrderSagaState.STATUS_FAILED).isEqualTo("FAILED");
        }
    }

    @Nested
    @DisplayName("Lifecycle Callbacks")
    class LifecycleTests {

        @Test
        @DisplayName("onCreate sets createdAt and updatedAt before persist")
        void shouldSetTimestampsOnCreate() {
            OrderSagaState state = new OrderSagaState();
            assertThat(state.getCreatedAt()).isNull();
            assertThat(state.getUpdatedAt()).isNull();

            state.onCreate();

            assertThat(state.getCreatedAt()).isNotNull();
            assertThat(state.getUpdatedAt()).isNotNull();
            assertThat(state.getCreatedAt()).isEqualTo(state.getUpdatedAt());
        }

        @Test
        @DisplayName("onUpdate refreshes updatedAt before update")
        void shouldRefreshUpdatedAtOnUpdate() {
            OrderSagaState state = new OrderSagaState();
            state.onCreate();
            LocalDateTime originalUpdated = state.getUpdatedAt();

            // Ensure time advances slightly
            state.onUpdate();

            assertThat(state.getUpdatedAt()).isNotNull();
            // updatedAt should be at or after the original
            assertThat(state.getUpdatedAt()).isAfterOrEqualTo(originalUpdated);
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        @DisplayName("toString includes sagaId and orderId and status")
        void shouldProduceReadableToString() {
            OrderSagaState state = new OrderSagaState(1001, OrderSagaStep.CREATE_ORDER,
                    OrderSagaState.STATUS_PENDING);
            state.setSagaId("saga-xyz");

            String result = state.toString();
            assertThat(result).contains("saga-xyz");
            assertThat(result).contains("1001");
            assertThat(result).contains("PENDING");
        }
    }

    @Nested
    @DisplayName("OrderSagaStep Enum Coverage")
    class SagaStepTests {

        @Test
        @DisplayName("All enum values exist")
        void shouldHaveAllEnumValues() {
            OrderSagaStep[] values = OrderSagaStep.values();
            assertThat(values).hasSizeGreaterThanOrEqualTo(3);
            assertThat(values).contains(
                    OrderSagaStep.CREATE_ORDER,
                    OrderSagaStep.RESERVE_INVENTORY,
                    OrderSagaStep.CONFIRM_ORDER);
        }

        @Test
        @DisplayName("valueOf returns correct enum")
        void shouldResolveByName() {
            assertThat(OrderSagaStep.valueOf("CREATE_ORDER"))
                    .isEqualTo(OrderSagaStep.CREATE_ORDER);
            assertThat(OrderSagaStep.valueOf("RESERVE_INVENTORY"))
                    .isEqualTo(OrderSagaStep.RESERVE_INVENTORY);
            assertThat(OrderSagaStep.valueOf("CONFIRM_ORDER"))
                    .isEqualTo(OrderSagaStep.CONFIRM_ORDER);
        }
    }
}
