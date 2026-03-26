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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.jpetstore.order.client.CatalogServiceClient;
import com.jpetstore.order.dto.CartDTO;
import com.jpetstore.order.dto.CartItemDTO;
import com.jpetstore.order.entity.CartState;
import com.jpetstore.order.repository.CartStateRepository;

/**
 * Unit tests for {@link CartStateService} — the externalized Redis-backed cart
 * state management service that replaces the monolith's session-scoped
 * {@code Cart.java} (126 lines) and {@code CartActionBean.java}.
 *
 * <p>These are pure Mockito unit tests with no Spring context loading.
 * Dependencies ({@link CartStateRepository}, {@link CatalogServiceClient}) are
 * mocked, and behavior is verified against the monolith's Cart.java behavioral
 * contracts.</p>
 *
 * <h3>Critical behavioral contracts tested:</h3>
 * <ul>
 *   <li>{@code containsItemId} — maps to monolith {@code Cart.containsItemId()} line 55-57</li>
 *   <li>{@code addItem} — maps to monolith {@code Cart.addItem()} lines 67-78 +
 *       {@code CartActionBean.addItemToCart()} lines 68-87</li>
 *   <li>{@code removeItemById} — maps to monolith {@code Cart.removeItemById()} lines 88-96</li>
 *   <li>{@code incrementQuantityByItemId} — maps to monolith
 *       {@code Cart.incrementQuantityByItemId()} lines 104-107</li>
 *   <li>{@code updateQuantity} — maps to monolith {@code Cart.setQuantityByItemId()} +
 *       {@code CartActionBean.updateCartQuantities()} removal on qty &lt; 1 (lines 126-128)</li>
 *   <li>{@code getSubTotal} — CRITICAL: exact BigDecimal replication of monolith
 *       {@code Cart.getSubTotal()} lines 119-123:
 *       {@code unitPrice.multiply(new BigDecimal(quantity)).reduce(BigDecimal.ZERO, BigDecimal::add)}</li>
 *   <li>{@code getCart} — combines Cart.getCartItemList(), getNumberOfItems(), getSubTotal()</li>
 *   <li>{@code clearCart} — maps to monolith {@code CartActionBean.clear()}</li>
 * </ul>
 *
 * @see CartStateService
 * @see CartState
 * @see CartState.CartItemData
 */
@ExtendWith(MockitoExtension.class)
class CartStateServiceTest {

    @Mock
    private CartStateRepository cartStateRepository;

    @Mock
    private CatalogServiceClient catalogServiceClient;

    @InjectMocks
    private CartStateService cartStateService;

    // -----------------------------------------------------------------------
    // containsItemId Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code containsItemId} returns {@code true} when the
     * specified item exists in the cart.
     *
     * <p>Replicates monolith {@code Cart.containsItemId()} (line 55-57):
     * {@code itemMap.containsKey(itemId)}.</p>
     */
    @Test
    void shouldReturnTrueWhenItemExistsInCart() {
        // given
        CartState cart = createCartState("cart-1",
                createCartItemData("EST-1", 1, true, "16.50", "Angelfish"));
        when(cartStateRepository.findById("cart-1")).thenReturn(Optional.of(cart));

        // when
        boolean result = cartStateService.containsItemId("cart-1", "EST-1");

        // then
        assertThat(result).isTrue();
    }

    /**
     * Verifies that {@code containsItemId} returns {@code false} when the
     * specified item is not in the cart (but the cart exists).
     */
    @Test
    void shouldReturnFalseWhenItemNotInCart() {
        // given
        CartState cart = createCartState("cart-1",
                createCartItemData("EST-1", 1, true, "16.50", "Angelfish"));
        when(cartStateRepository.findById("cart-1")).thenReturn(Optional.of(cart));

        // when
        boolean result = cartStateService.containsItemId("cart-1", "EST-99");

        // then
        assertThat(result).isFalse();
    }

    /**
     * Verifies that {@code containsItemId} returns {@code false} when the
     * cart itself does not exist in Redis.
     */
    @Test
    void shouldReturnFalseWhenCartNotFound() {
        // given
        when(cartStateRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // when
        boolean result = cartStateService.containsItemId("nonexistent", "EST-1");

        // then
        assertThat(result).isFalse();
    }

    // -----------------------------------------------------------------------
    // addItem Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that adding a NEW item to the cart calls
     * {@link CatalogServiceClient#getItem(String)} to fetch item details,
     * creates a new {@link CartState.CartItemData} with quantity=1, and
     * persists the updated cart.
     *
     * <p>Replicates monolith {@code Cart.addItem()} new-item path (lines 69-77):
     * create CartItem, set quantity=0, increment → qty=1.</p>
     */
    @Test
    void shouldAddNewItemToCart() {
        // given — empty cart exists
        CartState cart = createCartState("cart-1");
        when(cartStateRepository.findById("cart-1")).thenReturn(Optional.of(cart));
        when(catalogServiceClient.getItem("EST-1")).thenReturn(Optional.of(
                Map.of("itemId", "EST-1",
                        "listPrice", 16.50,
                        "productName", "Angelfish",
                        "quantity", 10000)));

        // when
        CartDTO result = cartStateService.addItem("cart-1", "EST-1");

        // then — CatalogService called for new item details
        verify(catalogServiceClient).getItem("EST-1");

        // then — item persisted with correct data
        verify(cartStateRepository).save(argThat(state ->
                state.getItems().containsKey("EST-1")
                        && state.getItems().get("EST-1").getQuantity() == 1
                        && state.getItems().get("EST-1").isInStock()
                        && "Angelfish".equals(state.getItems().get("EST-1").getProductName())
                        && state.getItems().get("EST-1").getUnitPrice()
                                .compareTo(new BigDecimal("16.50")) == 0));

        // then — returned DTO reflects the added item
        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(1);
        assertThat(result.getNumberOfItems()).isEqualTo(1);
    }

    /**
     * Verifies that adding an item that ALREADY exists in the cart increments
     * its quantity by 1 WITHOUT calling the Catalog Service.
     *
     * <p>Replicates monolith {@code Cart.addItem()} existing-item path
     * (lines 68-69): {@code cartItem = itemMap.get(item.getItemId());
     * if (cartItem != null) ... cartItem.incrementQuantity();}.</p>
     */
    @Test
    void shouldIncrementQuantityWhenItemAlreadyExists() {
        // given — cart with existing item at qty=2
        CartState cart = createCartState("cart-1",
                createCartItemData("EST-1", 2, true, "16.50", "Angelfish"));
        when(cartStateRepository.findById("cart-1")).thenReturn(Optional.of(cart));

        // when
        CartDTO result = cartStateService.addItem("cart-1", "EST-1");

        // then — CatalogService NOT called for existing item
        verify(catalogServiceClient, never()).getItem(anyString());

        // then — quantity incremented from 2 to 3
        verify(cartStateRepository).save(argThat(state ->
                state.getItems().get("EST-1").getQuantity() == 3));
    }

    /**
     * Verifies that adding an item when no cart exists creates a new cart
     * with the specified ID and adds the item.
     */
    @Test
    void shouldCreateNewCartWhenCartNotFound() {
        // given — no cart exists for this ID
        when(cartStateRepository.findById("new-cart")).thenReturn(Optional.empty());
        when(catalogServiceClient.getItem("EST-1")).thenReturn(Optional.of(
                Map.of("itemId", "EST-1",
                        "listPrice", 16.50,
                        "productName", "Angelfish",
                        "quantity", 10000)));

        // when
        CartDTO result = cartStateService.addItem("new-cart", "EST-1");

        // then — new cart created with correct ID and item added
        verify(cartStateRepository).save(argThat(state ->
                "new-cart".equals(state.getId())
                        && state.getItems().containsKey("EST-1")
                        && state.getItems().get("EST-1").getQuantity() == 1));

        assertThat(result).isNotNull();
        assertThat(result.getItems()).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // removeItemById Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code removeItemById} removes the specified item from
     * the cart while preserving other items.
     *
     * <p>Replicates monolith {@code Cart.removeItemById()} (lines 88-96):
     * removes from both {@code itemMap} and {@code itemList}.</p>
     */
    @Test
    void shouldRemoveItemFromCart() {
        // given — cart with 2 items
        CartState cart = createCartState("cart-1",
                createCartItemData("EST-1", 1, true, "16.50", "Angelfish"),
                createCartItemData("EST-2", 2, true, "18.50", "Tiger Shark"));
        when(cartStateRepository.findById("cart-1")).thenReturn(Optional.of(cart));

        // when
        CartDTO result = cartStateService.removeItemById("cart-1", "EST-1");

        // then — EST-1 removed, EST-2 preserved
        verify(cartStateRepository).save(argThat(state ->
                !state.getItems().containsKey("EST-1")
                        && state.getItems().containsKey("EST-2")));

        assertThat(result.getItems()).hasSize(1);
    }

    // -----------------------------------------------------------------------
    // incrementQuantityByItemId Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code incrementQuantityByItemId} increments the quantity
     * of an existing item by 1.
     *
     * <p>Replicates monolith {@code Cart.incrementQuantityByItemId()} (lines 104-107):
     * {@code cartItem = itemMap.get(itemId); cartItem.incrementQuantity();}.</p>
     */
    @Test
    void shouldIncrementQuantityOfExistingItem() {
        // given — cart with item at qty=5
        CartState cart = createCartState("cart-1",
                createCartItemData("EST-1", 5, true, "16.50", "Angelfish"));
        when(cartStateRepository.findById("cart-1")).thenReturn(Optional.of(cart));

        // when
        cartStateService.incrementQuantityByItemId("cart-1", "EST-1");

        // then — quantity incremented from 5 to 6
        verify(cartStateRepository).save(argThat(state ->
                state.getItems().get("EST-1").getQuantity() == 6));
    }

    /**
     * Verifies that incrementing a non-existent item throws an exception.
     *
     * <p>The monolith's {@code Cart.incrementQuantityByItemId()} would throw
     * a {@code NullPointerException} if the item is not found (line 106:
     * {@code cartItem.incrementQuantity()} on null). The microservice handles
     * this with an explicit RuntimeException.</p>
     */
    @Test
    void shouldHandleIncrementForNonExistentItem() {
        // given — cart with EST-1 but NOT EST-99
        CartState cart = createCartState("cart-1",
                createCartItemData("EST-1", 1, true, "16.50", "Angelfish"));
        when(cartStateRepository.findById("cart-1")).thenReturn(Optional.of(cart));

        // when / then — should throw for non-existent item
        assertThatThrownBy(() -> cartStateService.incrementQuantityByItemId("cart-1", "EST-99"))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("EST-99");
    }

    // -----------------------------------------------------------------------
    // updateQuantity Tests (CRITICAL — includes qty<1 removal)
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code updateQuantity} sets the item quantity to the
     * specified positive value.
     *
     * <p>Replicates monolith {@code Cart.setQuantityByItemId()} (lines 109-112).</p>
     */
    @Test
    void shouldUpdateQuantityOfItem() {
        // given
        CartState cart = createCartState("cart-1",
                createCartItemData("EST-1", 2, true, "16.50", "Angelfish"));
        when(cartStateRepository.findById("cart-1")).thenReturn(Optional.of(cart));

        // when
        CartDTO result = cartStateService.updateQuantity("cart-1", "EST-1", 5);

        // then — quantity updated to 5
        verify(cartStateRepository).save(argThat(state ->
                state.getItems().get("EST-1").getQuantity() == 5));
    }

    /**
     * CRITICAL — Verifies that updating quantity to 0 removes the item from
     * the cart, replicating monolith {@code CartActionBean.updateCartQuantities()}
     * lines 126-128: {@code if (quantity < 1) { cartItems.remove(); }}.
     */
    @Test
    void shouldRemoveItemWhenQuantityLessThanOne() {
        // given
        CartState cart = createCartState("cart-1",
                createCartItemData("EST-1", 3, true, "16.50", "Angelfish"));
        when(cartStateRepository.findById("cart-1")).thenReturn(Optional.of(cart));

        // when — update to quantity 0
        CartDTO result = cartStateService.updateQuantity("cart-1", "EST-1", 0);

        // then — item removed from cart
        verify(cartStateRepository).save(argThat(state ->
                !state.getItems().containsKey("EST-1")));
    }

    /**
     * Verifies that updating quantity to a negative value also removes the
     * item, consistent with the monolith's {@code quantity < 1} removal check.
     */
    @Test
    void shouldRemoveItemWhenQuantityIsNegative() {
        // given
        CartState cart = createCartState("cart-1",
                createCartItemData("EST-1", 3, true, "16.50", "Angelfish"));
        when(cartStateRepository.findById("cart-1")).thenReturn(Optional.of(cart));

        // when — update to negative quantity
        CartDTO result = cartStateService.updateQuantity("cart-1", "EST-1", -1);

        // then — item removed from cart
        verify(cartStateRepository).save(argThat(state ->
                !state.getItems().containsKey("EST-1")));
    }

    // -----------------------------------------------------------------------
    // getSubTotal Tests (CRITICAL — BigDecimal precision)
    // -----------------------------------------------------------------------

    /**
     * CRITICAL — Verifies the subtotal calculation with multiple items matches
     * the monolith's {@code Cart.getSubTotal()} exactly (lines 119-123):
     * {@code items.stream().map(item -> unitPrice.multiply(new BigDecimal(quantity)))
     *   .reduce(BigDecimal.ZERO, BigDecimal::add)}.
     *
     * <p>Expected: EST-1 (16.50 × 2 = 33.00) + EST-14 (58.50 × 1 = 58.50)
     * = 91.50.</p>
     *
     * <p>Uses {@code compareTo()} NOT {@code equals()} for BigDecimal comparison
     * because different scales represent equal values (e.g., 91.5 == 91.50).</p>
     */
    @Test
    void shouldCalculateSubTotalWithMultipleItems() {
        // given — two items with known prices and quantities
        CartState cart = createCartState("cart-1",
                createCartItemData("EST-1", 2, true, "16.50", "Angelfish"),
                createCartItemData("EST-14", 1, true, "58.50", "Koi"));
        when(cartStateRepository.findById("cart-1")).thenReturn(Optional.of(cart));

        // when
        BigDecimal result = cartStateService.getSubTotal("cart-1");

        // then — 16.50 * 2 + 58.50 * 1 = 33.00 + 58.50 = 91.50
        assertThat(result.compareTo(new BigDecimal("91.50"))).isEqualTo(0);
    }

    /**
     * Verifies that an empty cart returns a subtotal of zero, matching
     * the monolith's behavior when the cart item list is empty.
     */
    @Test
    void shouldReturnZeroSubTotalForEmptyCart() {
        // given — cart exists but has no items
        CartState cart = createCartState("cart-1");
        when(cartStateRepository.findById("cart-1")).thenReturn(Optional.of(cart));

        // when
        BigDecimal result = cartStateService.getSubTotal("cart-1");

        // then
        assertThat(result.compareTo(BigDecimal.ZERO)).isEqualTo(0);
    }

    /**
     * Verifies subtotal computation with a single item: 16.50 × 3 = 49.50.
     */
    @Test
    void shouldCalculateSubTotalWithSingleItem() {
        // given
        CartState cart = createCartState("cart-1",
                createCartItemData("EST-1", 3, true, "16.50", "Angelfish"));
        when(cartStateRepository.findById("cart-1")).thenReturn(Optional.of(cart));

        // when
        BigDecimal result = cartStateService.getSubTotal("cart-1");

        // then — 16.50 * 3 = 49.50
        assertThat(result.compareTo(new BigDecimal("49.50"))).isEqualTo(0);
    }

    /**
     * Verifies that {@code getSubTotal} returns {@code BigDecimal.ZERO} when
     * the cart does not exist in Redis, matching the service's graceful
     * degradation: {@code .orElse(BigDecimal.ZERO)}.
     */
    @Test
    void shouldReturnZeroSubTotalWhenCartNotFound() {
        // given — no cart exists
        when(cartStateRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // when
        BigDecimal result = cartStateService.getSubTotal("nonexistent");

        // then
        assertThat(result.compareTo(BigDecimal.ZERO)).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // getCart Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code getCart} returns a correctly populated {@link CartDTO}
     * when the cart exists, including item details, subtotal, and item count.
     */
    @Test
    void shouldReturnCartDTOWhenCartExists() {
        // given — cart with 2 items
        CartState cart = createCartState("cart-1",
                createCartItemData("EST-1", 2, true, "16.50", "Angelfish"),
                createCartItemData("EST-14", 1, true, "58.50", "Koi"));
        when(cartStateRepository.findById("cart-1")).thenReturn(Optional.of(cart));

        // when
        CartDTO result = cartStateService.getCart("cart-1");

        // then — verify DTO structure
        assertThat(result.getId()).isEqualTo("cart-1");
        assertThat(result.getNumberOfItems()).isEqualTo(2);
        assertThat(result.getItems()).hasSize(2);

        // then — verify subtotal: 16.50 * 2 + 58.50 * 1 = 91.50
        assertThat(result.getSubTotal().compareTo(new BigDecimal("91.50"))).isEqualTo(0);

        // then — verify items are correctly mapped (order not guaranteed from HashMap)
        assertThat(result.getItems())
                .extracting(CartItemDTO::getItemId)
                .containsExactlyInAnyOrder("EST-1", "EST-14");

        // then — verify individual item details
        CartItemDTO est1 = result.getItems().stream()
                .filter(item -> "EST-1".equals(item.getItemId()))
                .findFirst()
                .orElseThrow();
        assertThat(est1.getQuantity()).isEqualTo(2);
        assertThat(est1.isInStock()).isTrue();
        assertThat(est1.getUnitPrice().compareTo(new BigDecimal("16.50"))).isEqualTo(0);
        assertThat(est1.getTotal().compareTo(new BigDecimal("33.00"))).isEqualTo(0);
    }

    /**
     * Verifies that {@code getCart} returns an empty {@link CartDTO} when the
     * cart does not exist in Redis — never returns null.
     */
    @Test
    void shouldReturnEmptyCartDTOWhenCartNotFound() {
        // given — no cart exists
        when(cartStateRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // when
        CartDTO result = cartStateService.getCart("nonexistent");

        // then — empty cart, not null
        assertThat(result).isNotNull();
        assertThat(result.getItems()).isEmpty();
        assertThat(result.getSubTotal().compareTo(BigDecimal.ZERO)).isEqualTo(0);
        assertThat(result.getNumberOfItems()).isEqualTo(0);
    }

    // -----------------------------------------------------------------------
    // clearCart Tests
    // -----------------------------------------------------------------------

    /**
     * Verifies that {@code clearCart} delegates to
     * {@link CartStateRepository#deleteById(Object)} to remove the cart
     * from Redis.
     *
     * <p>Replicates monolith {@code CartActionBean.clear()} (lines 145-148):
     * {@code cart = new Cart(); workingItemId = null;}.</p>
     */
    @Test
    void shouldClearCartSuccessfully() {
        // when
        cartStateService.clearCart("cart-1");

        // then — deleteById called with correct cart ID
        verify(cartStateRepository).deleteById("cart-1");
    }

    // -----------------------------------------------------------------------
    // Helper Methods
    // -----------------------------------------------------------------------

    /**
     * Creates a {@link CartState} entity with the specified cart ID and items.
     *
     * @param cartId the cart identifier
     * @param items  zero or more cart item data entries
     * @return a fully populated CartState for test stubbing
     */
    private CartState createCartState(String cartId, CartState.CartItemData... items) {
        CartState state = new CartState();
        state.setId(cartId);
        Map<String, CartState.CartItemData> itemMap = new HashMap<>();
        for (CartState.CartItemData item : items) {
            itemMap.put(item.getItemId(), item);
        }
        state.setItems(itemMap);
        state.setLastUpdated(Instant.now());
        return state;
    }

    /**
     * Creates a {@link CartState.CartItemData} instance with all fields populated.
     *
     * @param itemId  the item identifier (e.g., "EST-1")
     * @param qty     the quantity in the cart
     * @param inStock whether the item is in stock
     * @param price   the unit price as a string for BigDecimal precision
     * @param name    the product name for display
     * @return a fully populated CartItemData for test data construction
     */
    private CartState.CartItemData createCartItemData(String itemId, int qty,
                                                       boolean inStock, String price,
                                                       String name) {
        CartState.CartItemData data = new CartState.CartItemData();
        data.setItemId(itemId);
        data.setQuantity(qty);
        data.setInStock(inStock);
        data.setUnitPrice(new BigDecimal(price));
        data.setProductName(name);
        return data;
    }
}
