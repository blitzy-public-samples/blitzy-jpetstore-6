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
package com.jpetstore.order.repository;

import com.jpetstore.order.entity.LineItem;
import com.jpetstore.order.entity.Order;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.autoconfigure.orm.jpa.TestEntityManager;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code @DataJpaTest} integration tests for {@link LineItemRepository}.
 *
 * <p>Validates that the {@link LineItem} JPA entity is correctly mapped to the
 * {@code lineitem} PostgreSQL table with snake_case column names, that the
 * composite primary key ({@code order_id}, {@code line_num}) works correctly
 * via {@code @IdClass(LineItem.LineItemId.class)}, and that all Spring Data JPA
 * repository query methods produce results identical to the monolith's
 * {@code LineItemMapper} MyBatis mapper.</p>
 *
 * <p>This test class replaces the monolith's {@code LineItemMapperTest} which tested:
 * <ul>
 *   <li>{@code insertLineItem()} — INSERT into 5 columns, verified via JdbcTemplate
 *       with UPPERCASE HSQLDB column names (ORDERID, LINENUM, ITEMID, QUANTITY, UNITPRICE)</li>
 *   <li>{@code getLineItemsByOrderId()} — SELECT by orderId, verified all 5 fields
 *       with exact BigDecimal comparison using {@code new BigDecimal("100.00")}</li>
 * </ul>
 *
 * <p><strong>Key architectural changes verified by these tests:</strong></p>
 * <ul>
 *   <li><strong>Snake_case column mapping</strong> — HSQLDB UPPERCASE columns
 *       (ORDERID, LINENUM, ITEMID) are now PostgreSQL snake_case (order_id, line_num,
 *       item_id) via {@code @Column(name = "...")} annotations</li>
 *   <li><strong>Composite PK via @IdClass</strong> — replaces MyBatis XML resultMap
 *       with JPA {@code @IdClass(LineItem.LineItemId.class)}</li>
 *   <li><strong>Cross-service FK removed</strong> — {@code lineitem.item_id} is a plain
 *       String with NO foreign key to Catalog Service's {@code item} table (AAP Section 0.8.1)</li>
 *   <li><strong>Intra-service FK preserved</strong> — {@code lineitem.order_id} maintains
 *       FK to {@code orders.order_id} within the Order Service</li>
 *   <li><strong>BigDecimal precision</strong> — {@code unit_price} uses
 *       {@code decimal(10,2)} matching the monolith's UNITPRICE column</li>
 * </ul>
 *
 * <p><strong>Test infrastructure:</strong></p>
 * <ul>
 *   <li>{@code @DataJpaTest} auto-configures Spring Data JPA with an embedded HSQLDB
 *       test datasource, replacing the production PostgreSQL datasource</li>
 *   <li>Liquibase is disabled; Hibernate {@code create-drop} generates the schema from
 *       JPA entity annotations</li>
 *   <li>Each test method runs within a {@code @Transactional} boundary that is rolled
 *       back after completion, ensuring test isolation</li>
 *   <li>A parent {@link Order} entity is created before each LineItem test to satisfy
 *       the intra-service FK constraint ({@code lineitem.order_id → orders.order_id})</li>
 * </ul>
 *
 * @see LineItemRepository
 * @see LineItem
 * @see Order
 */
@DataJpaTest
@TestPropertySource(properties = {
    "spring.liquibase.enabled=false",
    "spring.jpa.hibernate.ddl-auto=create-drop",
    "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.HSQLDialect"
})
class LineItemRepositoryTest {

    @Autowired
    private LineItemRepository lineItemRepository;

    @Autowired
    private TestEntityManager entityManager;

    // -----------------------------------------------------------------------
    // Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Creates and persists a minimal {@link Order} entity with all required
     * NOT NULL fields populated, satisfying the intra-service FK constraint
     * ({@code lineitem.order_id → orders.order_id}). The Order is flushed
     * immediately so that the auto-generated {@code orderId} from the
     * PostgreSQL sequence ({@code order_id_seq}) is available for use in
     * LineItem construction.
     *
     * <p>All setters listed in the internal_imports schema are called to
     * ensure comprehensive coverage of the Order entity's required fields.</p>
     *
     * @return the persisted Order entity with its auto-generated orderId
     */
    private Order createParentOrder() {
        Order order = new Order();
        order.setUsername("j2ee");
        order.setOrderDate(LocalDateTime.now());
        order.setShipAddress1("123 Main St");
        order.setShipAddress2("Apt 4B");
        order.setShipCity("Springfield");
        order.setShipState("IL");
        order.setShipZip("62701");
        order.setShipCountry("USA");
        order.setBillAddress1("456 Oak Ave");
        order.setBillAddress2("Suite 100");
        order.setBillCity("Springfield");
        order.setBillState("IL");
        order.setBillZip("62701");
        order.setBillCountry("USA");
        order.setCourier("UPS");
        order.setTotalPrice(new BigDecimal("100.00"));
        order.setBillToFirstName("John");
        order.setBillToLastName("Doe");
        order.setShipToFirstName("Jane");
        order.setShipToLastName("Doe");
        order.setCreditCard("4111111111111111");
        order.setExpiryDate("12/2026");
        order.setCardType("Visa");
        order.setLocale("en");
        order.setStatus("CONFIRMED");
        return entityManager.persistAndFlush(order);
    }

    /**
     * Creates a test {@link LineItem} entity with standard test values matching
     * the monolith's {@code LineItemMapperTest} data patterns.
     *
     * <p>Default values: {@code itemId = "EST-1"}, {@code quantity = 4},
     * {@code unitPrice = new BigDecimal("100.00")} — matching the monolith's
     * test data on LineItemMapperTest lines 47-52.</p>
     *
     * @param orderId the parent order's ID (from {@link #createParentOrder()})
     * @param lineNum the line number within the order (part of composite PK)
     * @return a new, unpersisted LineItem entity ready for repository operations
     */
    private LineItem createTestLineItem(int orderId, int lineNum) {
        LineItem lineItem = new LineItem();
        lineItem.setOrderId(orderId);
        lineItem.setLineNum(lineNum);
        lineItem.setItemId("EST-1");
        lineItem.setQuantity(4);
        lineItem.setUnitPrice(new BigDecimal("100.00"));
        return lineItem;
    }

    // -----------------------------------------------------------------------
    // Test 1: findByOrderId() — Replaces monolith getLineItemsByOrderId()
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link LineItemRepository#findByOrderId(int)} returns all
     * line items associated with a given order, with correct field values for
     * all 5 persisted columns plus the computed {@code total} field.
     *
     * <p>This test mirrors the monolith's {@code LineItemMapperTest.getLineItemsByOrderId()}
     * (lines 66-88) but validates 2 line items with different field values, snake_case
     * column mapping, and the transient {@code total} computation.</p>
     */
    @Test
    void findByOrderId() {
        // Arrange: Create parent order (required by intra-service FK)
        Order order = createParentOrder();
        int orderId = order.getOrderId();

        // Create 2 line items with different values
        LineItem lineItem1 = createTestLineItem(orderId, 1);

        LineItem lineItem2 = new LineItem();
        lineItem2.setOrderId(orderId);
        lineItem2.setLineNum(2);
        lineItem2.setItemId("EST-2");
        lineItem2.setQuantity(2);
        lineItem2.setUnitPrice(new BigDecimal("50.00"));

        lineItemRepository.saveAll(List.of(lineItem1, lineItem2));
        entityManager.flush();
        entityManager.clear();

        // Act
        List<LineItem> lineItems = lineItemRepository.findByOrderId(orderId);

        // Assert: collection size via explicit List.size() and AssertJ
        assertThat(lineItems).hasSize(2);
        assertThat(lineItems.size()).isEqualTo(2);

        // Verify first line item (lineNum == 1) — all 5 fields + computed total
        LineItem first = lineItems.stream()
            .filter(li -> li.getLineNum() == 1)
            .findFirst()
            .orElseThrow();
        assertThat(first.getOrderId()).isEqualTo(orderId);
        assertThat(first.getLineNum()).isEqualTo(1);
        assertThat(first.getItemId()).isEqualTo("EST-1");
        assertThat(first.getQuantity()).isEqualTo(4);
        assertThat(first.getUnitPrice()).isEqualTo(new BigDecimal("100.00"));
        // Verify transient total computation: 100.00 * 4 = 400.00
        assertThat(first.getTotal()).isEqualByComparingTo(new BigDecimal("400.00"));

        // Verify second line item (lineNum == 2) — all 5 fields + computed total
        LineItem second = lineItems.stream()
            .filter(li -> li.getLineNum() == 2)
            .findFirst()
            .orElseThrow();
        assertThat(second.getOrderId()).isEqualTo(orderId);
        assertThat(second.getLineNum()).isEqualTo(2);
        assertThat(second.getItemId()).isEqualTo("EST-2");
        assertThat(second.getQuantity()).isEqualTo(2);
        assertThat(second.getUnitPrice()).isEqualTo(new BigDecimal("50.00"));
        // Verify transient total computation: 50.00 * 2 = 100.00
        assertThat(second.getTotal()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    // -----------------------------------------------------------------------
    // Test 2: save_singleLineItem() — Replaces monolith insertLineItem()
    // -----------------------------------------------------------------------

    /**
     * Verifies that a single {@link LineItem} can be saved via
     * {@link LineItemRepository#save(LineItem)} and retrieved by its composite
     * primary key via {@link LineItemRepository#findById(LineItem.LineItemId)}.
     *
     * <p>Mirrors the monolith's {@code LineItemMapperTest.insertLineItem()} (lines
     * 44-63). Uses {@code BigDecimal.valueOf(100)} for creation (matching monolith
     * line 52) but asserts against {@code new BigDecimal("100.00")} for exact
     * comparison (matching monolith line 62).</p>
     */
    @Test
    void save_singleLineItem() {
        // Arrange: Create parent order
        Order order = createParentOrder();
        int orderId = order.getOrderId();

        LineItem lineItem = new LineItem();
        lineItem.setOrderId(orderId);
        lineItem.setLineNum(1);
        lineItem.setItemId("EST-1");
        lineItem.setQuantity(4);
        lineItem.setUnitPrice(BigDecimal.valueOf(100));

        // Act: Save via repository
        lineItemRepository.save(lineItem);
        entityManager.flush();
        entityManager.clear();

        // Assert: Retrieve by composite PK
        Optional<LineItem> result = lineItemRepository.findById(
            new LineItem.LineItemId(orderId, 1));
        assertThat(result).isPresent();
        assertThat(result.isPresent()).isTrue();

        LineItem found = result.get();
        assertThat(found.getOrderId()).isEqualTo(orderId);
        assertThat(found.getLineNum()).isEqualTo(1);
        assertThat(found.getItemId()).isEqualTo("EST-1");
        assertThat(found.getQuantity()).isEqualTo(4);
        // Exact BigDecimal comparison: monolith asserts new BigDecimal("100.00")
        assertThat(found.getUnitPrice()).isEqualTo(new BigDecimal("100.00"));
    }

    // -----------------------------------------------------------------------
    // Test 3: saveAll_batchInsert() — Batch Insert Verification
    // -----------------------------------------------------------------------

    /**
     * Verifies that multiple {@link LineItem} entities can be saved in batch via
     * {@link LineItemRepository#saveAll(Iterable)} and that all are retrievable
     * via {@link LineItemRepository#findByOrderId(int)}.
     *
     * <p>This replaces the monolith's loop in {@code OrderService.insertOrder()}
     * that calls {@code insertLineItem()} N times with Spring Data JPA's batch
     * insert capability. The test verifies 3 line items with distinct
     * {@code lineNum} and {@code itemId} values.</p>
     */
    @Test
    void saveAll_batchInsert() {
        // Arrange: Create parent order
        Order order = createParentOrder();
        int orderId = order.getOrderId();

        LineItem item1 = createTestLineItem(orderId, 1);

        LineItem item2 = new LineItem();
        item2.setOrderId(orderId);
        item2.setLineNum(2);
        item2.setItemId("EST-2");
        item2.setQuantity(2);
        item2.setUnitPrice(new BigDecimal("50.00"));

        LineItem item3 = new LineItem();
        item3.setOrderId(orderId);
        item3.setLineNum(3);
        item3.setItemId("EST-3");
        item3.setQuantity(1);
        item3.setUnitPrice(new BigDecimal("75.00"));

        // Act: Batch save via saveAll()
        lineItemRepository.saveAll(List.of(item1, item2, item3));
        entityManager.flush();
        entityManager.clear();

        // Assert: All 3 items found by orderId
        List<LineItem> lineItems = lineItemRepository.findByOrderId(orderId);
        assertThat(lineItems).hasSize(3);

        // Verify each item via List.get() for explicit List access
        // Sort by lineNum for deterministic assertion order
        lineItems.sort((a, b) -> Integer.compare(a.getLineNum(), b.getLineNum()));

        assertThat(lineItems.get(0).getLineNum()).isEqualTo(1);
        assertThat(lineItems.get(0).getItemId()).isEqualTo("EST-1");

        assertThat(lineItems.get(1).getLineNum()).isEqualTo(2);
        assertThat(lineItems.get(1).getItemId()).isEqualTo("EST-2");

        assertThat(lineItems.get(2).getLineNum()).isEqualTo(3);
        assertThat(lineItems.get(2).getItemId()).isEqualTo("EST-3");
    }

    // -----------------------------------------------------------------------
    // Test 4: compositePkVerification() — Composite PK @IdClass Test
    // -----------------------------------------------------------------------

    /**
     * Verifies that the composite primary key ({@code order_id}, {@code line_num})
     * implemented via {@code @IdClass(LineItem.LineItemId.class)} allows distinct
     * line items within the same order to be individually addressed.
     *
     * <p>Two line items with the same {@code orderId} but different {@code lineNum}
     * values are persisted and then retrieved individually by their composite keys.
     * This validates the JPA {@code @IdClass} mapping that replaces the MyBatis
     * XML {@code <resultMap>} composite key definition.</p>
     */
    @Test
    void compositePkVerification() {
        // Arrange: Create parent order
        Order order = createParentOrder();
        int orderId = order.getOrderId();

        // Create 2 line items with same orderId but different lineNum
        LineItem lineItem1 = createTestLineItem(orderId, 1);

        LineItem lineItem2 = new LineItem();
        lineItem2.setOrderId(orderId);
        lineItem2.setLineNum(2);
        lineItem2.setItemId("EST-2");
        lineItem2.setQuantity(3);
        lineItem2.setUnitPrice(new BigDecimal("75.00"));

        lineItemRepository.save(lineItem1);
        lineItemRepository.save(lineItem2);
        entityManager.flush();
        entityManager.clear();

        // Act: Retrieve each by composite key
        Optional<LineItem> result1 = lineItemRepository.findById(
            new LineItem.LineItemId(orderId, 1));
        Optional<LineItem> result2 = lineItemRepository.findById(
            new LineItem.LineItemId(orderId, 2));

        // Assert: Both present with correct distinct values
        assertThat(result1).isPresent();
        assertThat(result2).isPresent();

        assertThat(result1.get().getLineNum()).isEqualTo(1);
        assertThat(result1.get().getItemId()).isEqualTo("EST-1");
        assertThat(result1.get().getQuantity()).isEqualTo(4);

        assertThat(result2.get().getLineNum()).isEqualTo(2);
        assertThat(result2.get().getItemId()).isEqualTo("EST-2");
        assertThat(result2.get().getQuantity()).isEqualTo(3);

        // Verify they are truly different entities
        assertThat(result1.get().getItemId()).isNotEqualTo(result2.get().getItemId());
    }

    // -----------------------------------------------------------------------
    // Test 5: snakeCaseColumnMappingValidation() — Column Name Verification
    // -----------------------------------------------------------------------

    /**
     * Verifies that all {@code @Column(name = "...")} annotations on
     * {@link LineItem} correctly produce snake_case column names in the database.
     *
     * <p>Uses a native SQL query via {@link TestEntityManager#getEntityManager()}
     * to directly access the database using snake_case column names, bypassing
     * JPA/Hibernate mapping. This confirms the column-name transformation from
     * HSQLDB UPPERCASE (ORDERID, LINENUM, ITEMID, QUANTITY, UNITPRICE) to
     * PostgreSQL snake_case (order_id, line_num, item_id, quantity, unit_price).</p>
     */
    @Test
    @SuppressWarnings("unchecked")
    void snakeCaseColumnMappingValidation() {
        // Arrange: Create parent order and a line item
        Order order = createParentOrder();
        int orderId = order.getOrderId();

        LineItem lineItem = createTestLineItem(orderId, 1);
        lineItemRepository.save(lineItem);
        entityManager.flush();
        entityManager.clear();

        // Act: Use native SQL with snake_case column names to verify mapping
        List<Object[]> results = entityManager.getEntityManager()
            .createNativeQuery(
                "SELECT order_id, line_num, item_id, quantity, unit_price "
                + "FROM lineitem "
                + "WHERE order_id = ?1 AND line_num = ?2")
            .setParameter(1, orderId)
            .setParameter(2, 1)
            .getResultList();

        // Assert: Native query with snake_case columns returned valid data
        assertThat(results).hasSize(1);
        Object[] row = results.get(0);

        // Verify all 5 snake_case columns return correct values
        assertThat(((Number) row[0]).intValue()).isEqualTo(orderId);    // order_id
        assertThat(((Number) row[1]).intValue()).isEqualTo(1);          // line_num
        assertThat(row[2]).isEqualTo("EST-1");                          // item_id
        assertThat(((Number) row[3]).intValue()).isEqualTo(4);          // quantity
        assertThat(new BigDecimal(row[4].toString()))
            .isEqualByComparingTo(new BigDecimal("100.00"));            // unit_price
    }

    // -----------------------------------------------------------------------
    // Test 6: unitPriceBigDecimalPrecision() — BigDecimal Precision Verification
    // -----------------------------------------------------------------------

    /**
     * Verifies that the {@code unit_price} column preserves {@code decimal(10,2)}
     * precision through JPA persistence and retrieval.
     *
     * <p>Tests two values: {@code "100.00"} (matching the monolith's
     * {@code LineItemMapperTest} assertion on line 62) and {@code "99.99"} to
     * verify two-decimal-place precision is maintained. Uses
     * {@code new BigDecimal("100.00")} string constructor for exact comparison,
     * consistent with the monolith's assertion pattern.</p>
     */
    @Test
    void unitPriceBigDecimalPrecision() {
        // Arrange: Create parent order
        Order order = createParentOrder();
        int orderId = order.getOrderId();

        // Test with "100.00" — matching monolith assertion
        LineItem lineItem1 = createTestLineItem(orderId, 1);
        lineItemRepository.save(lineItem1);

        // Test with "99.99" — verifying 2-decimal precision
        LineItem lineItem2 = new LineItem();
        lineItem2.setOrderId(orderId);
        lineItem2.setLineNum(2);
        lineItem2.setItemId("EST-2");
        lineItem2.setQuantity(1);
        lineItem2.setUnitPrice(new BigDecimal("99.99"));
        lineItemRepository.save(lineItem2);

        entityManager.flush();
        entityManager.clear();

        // Act: Retrieve both by composite PK
        Optional<LineItem> result1 = lineItemRepository.findById(
            new LineItem.LineItemId(orderId, 1));
        Optional<LineItem> result2 = lineItemRepository.findById(
            new LineItem.LineItemId(orderId, 2));

        // Assert: Exact BigDecimal comparison for decimal(10,2)
        assertThat(result1).isPresent();
        assertThat(result1.get().getUnitPrice()).isEqualTo(new BigDecimal("100.00"));

        assertThat(result2).isPresent();
        assertThat(result2.get().getUnitPrice()).isEqualTo(new BigDecimal("99.99"));
    }

    // -----------------------------------------------------------------------
    // Test 7: crossServiceFkRemoved_itemIdIsPlainString() — FK Removal
    // -----------------------------------------------------------------------

    /**
     * Verifies that the cross-service foreign key from {@code lineitem.item_id}
     * to the Catalog Service's {@code item.itemid} table has been removed as a
     * database constraint.
     *
     * <p>Per AAP Section 0.8.1: "Cross-service foreign keys (e.g.,
     * lineitem.itemid → item.itemid) must be removed as database constraints
     * and enforced at the application layer." This test creates a LineItem with
     * an {@code itemId} that does NOT exist in any database, verifying that the
     * save succeeds without a FK constraint violation.</p>
     */
    @Test
    void crossServiceFkRemoved_itemIdIsPlainString() {
        // Arrange: Create parent order
        Order order = createParentOrder();
        int orderId = order.getOrderId();

        // Use a nonexistent item ID (max 10 chars per @Column length = 10)
        // that does NOT exist in any database — no FK constraint should prevent this
        LineItem lineItem = new LineItem();
        lineItem.setOrderId(orderId);
        lineItem.setLineNum(1);
        lineItem.setItemId("ZZZ-99999");
        lineItem.setQuantity(1);
        lineItem.setUnitPrice(new BigDecimal("50.00"));

        // Act: Save should succeed — no FK constraint to Catalog's item table
        lineItemRepository.save(lineItem);
        entityManager.flush();
        entityManager.clear();

        // Assert: Retrieve and verify the nonexistent itemId was stored successfully
        Optional<LineItem> result = lineItemRepository.findById(
            new LineItem.LineItemId(orderId, 1));
        assertThat(result).isPresent();
        assertThat(result.get().getItemId()).isEqualTo("ZZZ-99999");
    }

    // -----------------------------------------------------------------------
    // Test 8: findByOrderId_noLineItems_returnsEmptyList()
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@link LineItemRepository#findByOrderId(int)} returns an
     * empty list when an order exists but has no associated line items.
     *
     * <p>This tests the boundary condition where an Order exists (FK is valid)
     * but no LineItem records reference it. The result must be an empty
     * {@code List}, not {@code null}.</p>
     */
    @Test
    void findByOrderId_noLineItems_returnsEmptyList() {
        // Arrange: Create parent order with NO line items
        Order order = createParentOrder();
        int orderId = order.getOrderId();

        // Act: Query for line items on an order with none
        List<LineItem> lineItems = lineItemRepository.findByOrderId(orderId);

        // Assert: Empty list, not null
        assertThat(lineItems).isEmpty();
        assertThat(lineItems).isNotNull();
    }
}
