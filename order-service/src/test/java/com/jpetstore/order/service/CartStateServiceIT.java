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
package com.jpetstore.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import com.jpetstore.order.client.AccountServiceClient;
import com.jpetstore.order.client.CatalogServiceClient;
import com.jpetstore.order.dto.CartDTO;
import com.jpetstore.order.dto.CartItemDTO;
import com.jpetstore.order.exception.ResourceNotFoundException;
import com.jpetstore.order.repository.CartStateRepository;

/**
 * Integration test for {@link CartStateService} with a real Redis instance
 * via Testcontainers.
 *
 * <p>This test validates the externalized cart state management that replaces
 * the monolith's session-scoped {@code Cart.java}. It verifies all cart
 * operations (add, remove, update, subtotal, merge, clear) against a real
 * Redis data store, ensuring that the Redis hash storage model correctly
 * preserves cart semantics.</p>
 *
 * <h3>Test Infrastructure</h3>
 * <ul>
 *   <li><b>Redis 7</b> — Testcontainers GenericContainer for real cart state persistence</li>
 *   <li><b>PostgreSQL 16</b> — Testcontainers for JPA auto-configuration (required by
 *       Spring Boot context but not used directly in cart tests)</li>
 *   <li><b>{@code @MockBean CatalogServiceClient}</b> — Controls item lookup responses
 *       (replaces monolith's in-process {@code @SpringBean CatalogService})</li>
 *   <li><b>{@code @MockBean AccountServiceClient}</b> — Satisfies application context
 *       dependency (not used by cart operations)</li>
 * </ul>
 *
 * <h3>Key behaviors verified</h3>
 * <ul>
 *   <li>Add item to empty cart → creates new cart in Redis with quantity=1</li>
 *   <li>Add existing item → increments quantity (mirrors {@code Cart.addItem()} line 77)</li>
 *   <li>Remove item from cart → removes entry from Redis hash</li>
 *   <li>Update quantities → supports bulk update with removal at quantity &lt;= 0</li>
 *   <li>Subtotal calculation → BigDecimal precision matching monolith {@code Cart.getSubTotal()}</li>
 *   <li>Cart merge on login → anonymous-to-user merge per AAP §0.7.2</li>
 *   <li>Clear cart → removes entire cart from Redis</li>
 *   <li>Cart expiry/cleanup — verified via clearCart and non-existent cart handling</li>
 * </ul>
 *
 * @see CartStateService
 * @see com.jpetstore.order.entity.CartState
 * @see com.jpetstore.order.repository.CartStateRepository
 */
@Tag("integration")
@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
class CartStateServiceIT {

    // =========================================================================
    // Infrastructure: Testcontainers
    // =========================================================================

    /**
     * PostgreSQL container — required for Spring Boot JPA auto-configuration.
     * Cart data is stored in Redis, but the application context needs a valid
     * DataSource to initialize JPA repositories for Order, LineItem, etc.
     */
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16")
            .withDatabaseName("jpetstore_order_test")
            .withUsername("test")
            .withPassword("test");

    /**
     * Redis 7 container for cart state persistence. Uses GenericContainer
     * since the order-service pom.xml includes testcontainers core (via
     * testcontainers-postgresql transitive dependency) but not the dedicated
     * testcontainers-redis module.
     */
    @Container
    @SuppressWarnings("resource")
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7"))
            .withExposedPorts(6379);

    // =========================================================================
    // Dynamic Properties
    // =========================================================================

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL (for JPA auto-configuration)
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.liquibase.enabled", () -> "false");

        // Redis (for cart state)
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));

        // Service URLs (not used by cart, but required for AppConfig beans)
        registry.add("services.catalog-service.url", () -> "http://localhost:19998");
        registry.add("services.account-service.url", () -> "http://localhost:19999");
    }

    // =========================================================================
    // Mocked Beans
    // =========================================================================

    /**
     * Mocked CatalogServiceClient — controls what item data is returned when
     * CartStateService.addItem() fetches item details from the Catalog Service.
     * This lets us test cart behavior without a real Catalog Service.
     */
    @MockBean
    private CatalogServiceClient catalogServiceClient;

    /**
     * Mocked AccountServiceClient — not used by CartStateService but required
     * by the Spring application context (AppConfig creates the bean).
     */
    @MockBean
    private AccountServiceClient accountServiceClient;

    // =========================================================================
    // Autowired Components Under Test
    // =========================================================================

    @Autowired
    private CartStateService cartStateService;

    @Autowired
    private CartStateRepository cartStateRepository;

    // =========================================================================
    // Test Constants
    // =========================================================================

    private static final String CART_ID = "test-session-001";
    private static final String USER_CART_ID = "j2ee";
    private static final String ANON_CART_ID = "anon-session-abc";
    private static final String ITEM_EST1 = "EST-1";
    private static final String ITEM_EST2 = "EST-2";
    private static final String ITEM_EST3 = "EST-3";

    // =========================================================================
    // Test Setup
    // =========================================================================

    @BeforeEach
    void setUp() {
        // Clean all cart data from Redis before each test
        cartStateRepository.deleteAll();

        // Stub CatalogServiceClient.getItem() for common test items
        stubCatalogItem(ITEM_EST1, new BigDecimal("16.50"), "Angelfish", true);
        stubCatalogItem(ITEM_EST2, new BigDecimal("18.50"), "Tiger Shark", true);
        stubCatalogItem(ITEM_EST3, new BigDecimal("12.00"), "Koi", false);
    }

    /**
     * Configures the mocked CatalogServiceClient to return item data matching
     * the monolith's Catalog Service response format.
     */
    private void stubCatalogItem(String itemId, BigDecimal listPrice,
                                 String productName, boolean inStock) {
        Map<String, Object> itemData = new HashMap<>();
        itemData.put("itemId", itemId);
        itemData.put("listPrice", listPrice);
        itemData.put("quantity", inStock ? 10 : 0);

        // Product name may be nested in a "product" map (matches CatalogService response)
        Map<String, Object> product = new HashMap<>();
        product.put("name", productName);
        itemData.put("product", product);

        when(catalogServiceClient.getItem(eq(itemId))).thenReturn(Optional.of(itemData));
    }

    // =========================================================================
    // Add Item Tests
    // =========================================================================

    @Nested
    @DisplayName("Add Item Operations")
    class AddItemTests {

        /**
         * Verifies that adding an item to an empty cart creates the cart in Redis
         * with quantity=1 — matching monolith Cart.addItem() behavior where
         * quantity is set to 0 then incremented to 1.
         */
        @Test
        @DisplayName("should create new cart and add item with quantity 1")
        void shouldCreateNewCartAndAddItem() {
            CartDTO result = cartStateService.addItem(CART_ID, ITEM_EST1);

            assertThat(result).isNotNull();
            assertThat(result.getItems()).hasSize(1);

            CartItemDTO item = result.getItems().get(0);
            assertThat(item.getItemId()).isEqualTo(ITEM_EST1);
            assertThat(item.getQuantity()).isEqualTo(1);
            assertThat(item.getUnitPrice()).isEqualByComparingTo(new BigDecimal("16.50"));
        }

        /**
         * Verifies that adding an item that already exists in the cart increments
         * the quantity by 1 — matching monolith Cart.addItem() line 77:
         * {@code cartItem.incrementQuantity()}.
         */
        @Test
        @DisplayName("should increment quantity when adding existing item")
        void shouldIncrementQuantityForExistingItem() {
            // Add item first time → quantity=1
            cartStateService.addItem(CART_ID, ITEM_EST1);
            // Add same item again → quantity=2
            CartDTO result = cartStateService.addItem(CART_ID, ITEM_EST1);

            assertThat(result.getItems()).hasSize(1);
            assertThat(result.getItems().get(0).getQuantity()).isEqualTo(2);
        }

        /**
         * Verifies that adding a non-existent catalog item throws
         * ResourceNotFoundException — equivalent to the monolith throwing when
         * CatalogService.getItem() returns null.
         */
        @Test
        @DisplayName("should throw ResourceNotFoundException for non-existent item")
        void shouldThrowWhenItemNotInCatalog() {
            when(catalogServiceClient.getItem(eq("NONEXISTENT")))
                    .thenReturn(Optional.empty());

            assertThatThrownBy(() -> cartStateService.addItem(CART_ID, "NONEXISTENT"))
                    .isInstanceOf(ResourceNotFoundException.class)
                    .hasMessageContaining("not found");
        }

        /**
         * Verifies that multiple different items can coexist in the same cart.
         */
        @Test
        @DisplayName("should handle multiple different items in same cart")
        void shouldHandleMultipleItems() {
            cartStateService.addItem(CART_ID, ITEM_EST1);
            CartDTO result = cartStateService.addItem(CART_ID, ITEM_EST2);

            assertThat(result.getItems()).hasSize(2);
        }
    }

    // =========================================================================
    // Remove Item Tests
    // =========================================================================

    @Nested
    @DisplayName("Remove Item Operations")
    class RemoveItemTests {

        /**
         * Verifies that removing an item from the cart deletes it from the
         * Redis hash — matching monolith Cart.removeItemById().
         */
        @Test
        @DisplayName("should remove item from cart")
        void shouldRemoveItem() {
            cartStateService.addItem(CART_ID, ITEM_EST1);
            cartStateService.addItem(CART_ID, ITEM_EST2);

            CartDTO result = cartStateService.removeItemById(CART_ID, ITEM_EST1);

            assertThat(result.getItems()).hasSize(1);
            assertThat(result.getItems().get(0).getItemId()).isEqualTo(ITEM_EST2);
        }

        /**
         * Verifies that removing an item from a non-existent cart throws
         * ResourceNotFoundException.
         */
        @Test
        @DisplayName("should throw when removing from non-existent cart")
        void shouldThrowWhenCartNotFound() {
            assertThatThrownBy(() ->
                    cartStateService.removeItemById("nonexistent-cart", ITEM_EST1))
                    .isInstanceOf(ResourceNotFoundException.class);
        }

        /**
         * Verifies that removing a non-existent item from an existing cart
         * throws ResourceNotFoundException.
         */
        @Test
        @DisplayName("should throw when removing non-existent item")
        void shouldThrowWhenItemNotInCart() {
            cartStateService.addItem(CART_ID, ITEM_EST1);

            assertThatThrownBy(() ->
                    cartStateService.removeItemById(CART_ID, "NONEXISTENT"))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    // =========================================================================
    // Update Quantity Tests
    // =========================================================================

    @Nested
    @DisplayName("Update Quantity Operations")
    class UpdateQuantityTests {

        /**
         * Verifies that updating an item's quantity persists to Redis correctly.
         */
        @Test
        @DisplayName("should update item quantity")
        void shouldUpdateQuantity() {
            cartStateService.addItem(CART_ID, ITEM_EST1);

            CartDTO result = cartStateService.updateQuantity(CART_ID, ITEM_EST1, 5);

            assertThat(result.getItems()).hasSize(1);
            assertThat(result.getItems().get(0).getQuantity()).isEqualTo(5);
        }

        /**
         * Verifies that setting quantity to 0 or less removes the item from
         * the cart — matching monolith behavior where quantity <= 0 means removal.
         */
        @Test
        @DisplayName("should remove item when quantity set to zero")
        void shouldRemoveItemWhenQuantityZero() {
            cartStateService.addItem(CART_ID, ITEM_EST1);

            CartDTO result = cartStateService.updateQuantity(CART_ID, ITEM_EST1, 0);

            assertThat(result.getItems()).isEmpty();
        }

        /**
         * Verifies bulk quantity update across multiple items in a single operation —
         * matching monolith CartActionBean.updateCartQuantities().
         */
        @Test
        @DisplayName("should support bulk quantity update")
        void shouldSupportBulkUpdate() {
            cartStateService.addItem(CART_ID, ITEM_EST1);
            cartStateService.addItem(CART_ID, ITEM_EST2);

            Map<String, Integer> quantities = new HashMap<>();
            quantities.put(ITEM_EST1, 3);
            quantities.put(ITEM_EST2, 7);

            CartDTO result = cartStateService.updateQuantities(CART_ID, quantities);

            assertThat(result.getItems()).hasSize(2);

            // Verify quantities are updated correctly
            Map<String, Integer> resultQtys = new HashMap<>();
            for (CartItemDTO item : result.getItems()) {
                resultQtys.put(item.getItemId(), item.getQuantity());
            }
            assertThat(resultQtys).containsEntry(ITEM_EST1, 3);
            assertThat(resultQtys).containsEntry(ITEM_EST2, 7);
        }

        /**
         * Verifies that bulk update with quantity 0 removes items from the cart.
         */
        @Test
        @DisplayName("should remove items with zero quantity in bulk update")
        void shouldRemoveItemsWithZeroQuantityInBulkUpdate() {
            cartStateService.addItem(CART_ID, ITEM_EST1);
            cartStateService.addItem(CART_ID, ITEM_EST2);

            Map<String, Integer> quantities = new HashMap<>();
            quantities.put(ITEM_EST1, 0);
            quantities.put(ITEM_EST2, 5);

            CartDTO result = cartStateService.updateQuantities(CART_ID, quantities);

            assertThat(result.getItems()).hasSize(1);
            assertThat(result.getItems().get(0).getItemId()).isEqualTo(ITEM_EST2);
        }
    }

    // =========================================================================
    // Subtotal Calculation Tests
    // =========================================================================

    @Nested
    @DisplayName("Subtotal Calculation — BigDecimal Precision")
    class SubTotalTests {

        /**
         * Verifies that getSubTotal produces the correct BigDecimal result
         * matching monolith Cart.getSubTotal() arithmetic:
         * {@code unitPrice * quantity} for each item, summed via BigDecimal::add.
         */
        @Test
        @DisplayName("should calculate correct subtotal with BigDecimal precision")
        void shouldCalculateSubtotalCorrectly() {
            // EST-1: listPrice=16.50, quantity after 2 adds = 2 → 16.50 * 2 = 33.00
            cartStateService.addItem(CART_ID, ITEM_EST1);
            cartStateService.addItem(CART_ID, ITEM_EST1);

            // EST-2: listPrice=18.50, quantity = 1 → 18.50 * 1 = 18.50
            cartStateService.addItem(CART_ID, ITEM_EST2);

            BigDecimal subtotal = cartStateService.getSubTotal(CART_ID);

            // Expected: 33.00 + 18.50 = 51.50
            assertThat(subtotal).isEqualByComparingTo(new BigDecimal("51.50"));
        }

        /**
         * Verifies that subtotal of empty cart returns BigDecimal.ZERO.
         */
        @Test
        @DisplayName("should return ZERO subtotal for empty cart")
        void shouldReturnZeroForEmptyCart() {
            BigDecimal subtotal = cartStateService.getSubTotal("nonexistent-cart");

            assertThat(subtotal).isEqualByComparingTo(BigDecimal.ZERO);
        }

        /**
         * Verifies subtotal with higher quantity (tests multiplication precision).
         */
        @Test
        @DisplayName("should handle larger quantities in subtotal calculation")
        void shouldHandleLargerQuantities() {
            cartStateService.addItem(CART_ID, ITEM_EST1);
            cartStateService.updateQuantity(CART_ID, ITEM_EST1, 10);

            BigDecimal subtotal = cartStateService.getSubTotal(CART_ID);

            // 16.50 * 10 = 165.00
            assertThat(subtotal).isEqualByComparingTo(new BigDecimal("165.00"));
        }
    }

    // =========================================================================
    // Cart Merge on Login Tests
    // =========================================================================

    @Nested
    @DisplayName("Cart Merge on Login — AAP §0.7.2")
    class CartMergeTests {

        /**
         * Verifies that merging an anonymous cart into a user's cart sums
         * quantities for items present in both carts and copies unique items
         * from the anonymous cart.
         */
        @Test
        @DisplayName("should merge anonymous cart into user cart with quantity summation")
        void shouldMergeCartsWithQuantitySummation() {
            // Anonymous cart: EST-1 qty=2, EST-3 qty=1
            cartStateService.addItem(ANON_CART_ID, ITEM_EST1);
            cartStateService.addItem(ANON_CART_ID, ITEM_EST1);
            cartStateService.addItem(ANON_CART_ID, ITEM_EST3);

            // User cart: EST-1 qty=3, EST-2 qty=1
            cartStateService.addItem(USER_CART_ID, ITEM_EST1);
            cartStateService.addItem(USER_CART_ID, ITEM_EST1);
            cartStateService.addItem(USER_CART_ID, ITEM_EST1);
            cartStateService.addItem(USER_CART_ID, ITEM_EST2);

            // Merge: anonymous → user
            CartDTO result = cartStateService.mergeCart(ANON_CART_ID, USER_CART_ID);

            // Verify: 3 distinct items
            assertThat(result.getItems()).hasSize(3);

            // Build result map for easy assertion
            Map<String, Integer> resultQtys = new HashMap<>();
            for (CartItemDTO item : result.getItems()) {
                resultQtys.put(item.getItemId(), item.getQuantity());
            }

            // EST-1: user(3) + anon(2) = 5
            assertThat(resultQtys).containsEntry(ITEM_EST1, 5);
            // EST-2: user only = 1
            assertThat(resultQtys).containsEntry(ITEM_EST2, 1);
            // EST-3: anon only = 1
            assertThat(resultQtys).containsEntry(ITEM_EST3, 1);

            // Verify: Anonymous cart was deleted after merge
            assertThat(cartStateRepository.findById(ANON_CART_ID)).isEmpty();
        }

        /**
         * Verifies that merging when no anonymous cart exists returns the user's
         * cart unchanged.
         */
        @Test
        @DisplayName("should return user cart unchanged when no anonymous cart exists")
        void shouldReturnUserCartWhenNoAnonymousCart() {
            cartStateService.addItem(USER_CART_ID, ITEM_EST1);

            CartDTO result = cartStateService.mergeCart("nonexistent-anon-id", USER_CART_ID);

            assertThat(result.getItems()).hasSize(1);
            assertThat(result.getItems().get(0).getItemId()).isEqualTo(ITEM_EST1);
        }

        /**
         * Verifies that merging an anonymous cart into a new (non-existent) user
         * cart creates the user cart with the anonymous items.
         */
        @Test
        @DisplayName("should create user cart from anonymous cart when user has no cart")
        void shouldCreateUserCartFromAnonymous() {
            cartStateService.addItem(ANON_CART_ID, ITEM_EST1);
            cartStateService.addItem(ANON_CART_ID, ITEM_EST2);

            CartDTO result = cartStateService.mergeCart(ANON_CART_ID, USER_CART_ID);

            assertThat(result.getItems()).hasSize(2);
            assertThat(cartStateRepository.findById(ANON_CART_ID)).isEmpty();
        }
    }

    // =========================================================================
    // Clear Cart and Cleanup Tests
    // =========================================================================

    @Nested
    @DisplayName("Clear Cart and Cleanup")
    class ClearCartTests {

        /**
         * Verifies that clearCart removes the entire cart from Redis —
         * matching monolith CartActionBean.clear() behavior.
         */
        @Test
        @DisplayName("should remove cart entirely from Redis on clear")
        void shouldClearCart() {
            cartStateService.addItem(CART_ID, ITEM_EST1);
            cartStateService.addItem(CART_ID, ITEM_EST2);

            // Verify cart exists
            assertThat(cartStateRepository.findById(CART_ID)).isPresent();

            cartStateService.clearCart(CART_ID);

            // Verify cart is gone from Redis
            assertThat(cartStateRepository.findById(CART_ID)).isEmpty();
        }

        /**
         * Verifies that getCart for a non-existent cart ID returns an empty
         * cart (not null) — graceful degradation for expired sessions.
         */
        @Test
        @DisplayName("should return empty cart DTO for non-existent cart")
        void shouldReturnEmptyCartForNonExistentId() {
            CartDTO result = cartStateService.getCart("nonexistent-session");

            assertThat(result).isNotNull();
            assertThat(result.getItems()).isEmpty();
            assertThat(result.getSubTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        }

        /**
         * Verifies that clearing a non-existent cart does not throw
         * (idempotent operation).
         */
        @Test
        @DisplayName("should not throw when clearing non-existent cart")
        void shouldNotThrowOnClearNonExistentCart() {
            // Should execute without exception
            cartStateService.clearCart("nonexistent-cart-id");
        }
    }

    // =========================================================================
    // Get Cart / containsItemId Tests
    // =========================================================================

    @Nested
    @DisplayName("Cart Retrieval and Item Check")
    class CartRetrievalTests {

        /**
         * Verifies that getCart returns a complete CartDTO with all fields:
         * items, numberOfItems, subTotal.
         */
        @Test
        @DisplayName("should return complete cart DTO with all items")
        void shouldReturnCompleteCartDTO() {
            cartStateService.addItem(CART_ID, ITEM_EST1);
            cartStateService.addItem(CART_ID, ITEM_EST2);

            CartDTO result = cartStateService.getCart(CART_ID);

            assertThat(result.getItems()).hasSize(2);
            assertThat(result.getNumberOfItems()).isEqualTo(2);
            // Subtotal: 16.50 + 18.50 = 35.00
            assertThat(result.getSubTotal()).isEqualByComparingTo(new BigDecimal("35.00"));
        }

        /**
         * Verifies containsItemId returns true for items in the cart and
         * false for items not in the cart.
         */
        @Test
        @DisplayName("should correctly report item containment")
        void shouldCheckContainsItemId() {
            cartStateService.addItem(CART_ID, ITEM_EST1);

            assertThat(cartStateService.containsItemId(CART_ID, ITEM_EST1)).isTrue();
            assertThat(cartStateService.containsItemId(CART_ID, ITEM_EST2)).isFalse();
        }

        /**
         * Verifies containsItemId returns false for a non-existent cart.
         */
        @Test
        @DisplayName("should return false for non-existent cart")
        void shouldReturnFalseForNonExistentCart() {
            assertThat(cartStateService.containsItemId("nonexistent", ITEM_EST1)).isFalse();
        }
    }
}
