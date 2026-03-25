/*
 *    Copyright 2010-2022 the original author or authors.
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
package org.mybatis.jpetstore.web.actions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import net.sourceforge.stripes.action.ActionBeanContext;
import net.sourceforge.stripes.action.Message;
import net.sourceforge.stripes.action.Resolution;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mybatis.jpetstore.domain.Cart;
import org.mybatis.jpetstore.domain.Item;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

class CartActionBeanTest {

    private CartActionBean cartActionBean;
    private ActionBeanContext mockContext;

    @BeforeEach
    void setUp() {
        cartActionBean = new CartActionBean();
        cartActionBean.setCart(new Cart());

        // Mock ActionBeanContext to avoid NPE in setMessage().
        // Lenient stubbing prevents UnnecessaryStubbingException when the nested
        // RestClientTests class's MockitoExtension checks stubs created by this
        // enclosing setUp — the outer mockContext is not used by nested tests.
        mockContext = mock(ActionBeanContext.class);
        lenient().when(mockContext.getMessages()).thenReturn(new ArrayList<Message>());
        cartActionBean.setContext(mockContext);
    }

    @Test
    void constructorOutputNotNull() {
        final CartActionBean actual = new CartActionBean();

        assertThat(actual).isNotNull();
        assertThat(actual.getCart()).isNotNull();
        assertThat(actual.getContext()).isNull();
    }

    @Test
    void getCartOutputNotNull() {
        final CartActionBean bean = new CartActionBean();

        assertThat(bean.getCart()).isNotNull();
    }

    @Test
    void addItemToCart_WithNullWorkingItemId_ShouldReturnError() {
        cartActionBean.setWorkingItemId(null);

        Resolution resolution = cartActionBean.addItemToCart();

        assertThat(resolution).isNotNull();
        assertThat(resolution.toString()).contains("Error.jsp");
    }

    @Test
    void addItemToCart_WithEmptyWorkingItemId_ShouldReturnError() {
        cartActionBean.setWorkingItemId("");

        Resolution resolution = cartActionBean.addItemToCart();

        assertThat(resolution).isNotNull();
        assertThat(resolution.toString()).contains("Error.jsp");
    }

    @Test
    void addItemToCart_WithBlankWorkingItemId_ShouldReturnError() {
        cartActionBean.setWorkingItemId("   ");

        Resolution resolution = cartActionBean.addItemToCart();

        assertThat(resolution).isNotNull();
        assertThat(resolution.toString()).contains("Error.jsp");
    }

    @Test
    void removeItemFromCart_WithNullWorkingItemId_ShouldReturnError() {
        cartActionBean.setWorkingItemId(null);

        Resolution resolution = cartActionBean.removeItemFromCart();

        assertThat(resolution).isNotNull();
        assertThat(resolution.toString()).contains("Error.jsp");
    }

    @Test
    void removeItemFromCart_WithEmptyWorkingItemId_ShouldReturnError() {
        cartActionBean.setWorkingItemId("");

        Resolution resolution = cartActionBean.removeItemFromCart();

        assertThat(resolution).isNotNull();
        assertThat(resolution.toString()).contains("Error.jsp");
    }

    @Test
    void removeItemFromCart_WithBlankWorkingItemId_ShouldReturnError() {
        cartActionBean.setWorkingItemId("   ");

        Resolution resolution = cartActionBean.removeItemFromCart();

        assertThat(resolution).isNotNull();
        assertThat(resolution.toString()).contains("Error.jsp");
    }

    @Test
    void removeItemFromCart_WithNonExistentItem_ShouldReturnError() {
        cartActionBean.setWorkingItemId("NON_EXISTENT_ITEM");

        Resolution resolution = cartActionBean.removeItemFromCart();

        assertThat(resolution).isNotNull();
        assertThat(resolution.toString()).contains("Error.jsp");
    }

    @Test
    void clearShouldResetCartAndWorkingItemId() {
        cartActionBean.setWorkingItemId("EST-1");

        cartActionBean.clear();

        assertThat(cartActionBean.getCart()).isNotNull();
        assertThat(cartActionBean.getCart().getNumberOfItems()).isZero();
    }

    /**
     * Nested test class for REST-client integration tests.
     *
     * <p>These tests verify the CartActionBean's interactions with the Catalog Service
     * REST API for item lookups and inventory checks, and the Order Service REST API
     * for externalized cart state synchronization during the Strangler Fig transition.</p>
     *
     * <p>The RestTemplate is injected via reflection into the CartActionBean's private
     * {@code restTemplate} field, bypassing the lazy initialization in
     * {@code getRestTemplate()}.</p>
     */
    @Nested
    @ExtendWith(MockitoExtension.class)
    class RestClientTests {

        @Mock
        private RestTemplate restTemplate;

        @Mock
        private ActionBeanContext mockContext;

        @Mock
        private HttpServletRequest mockRequest;

        @Mock
        private HttpSession mockSession;

        private CartActionBean cartActionBean;

        private static final String CATALOG_SERVICE_URL = "http://catalog-service:8082/api";
        private static final String CART_SERVICE_URL = "http://order-service:8083/api/cart";

        @BeforeEach
        void setUp() throws Exception {
            cartActionBean = new CartActionBean();
            cartActionBean.setCart(new Cart());

            // Inject mocked RestTemplate via reflection into the private transient field
            Field restTemplateField = CartActionBean.class.getDeclaredField("restTemplate");
            restTemplateField.setAccessible(true);
            restTemplateField.set(cartActionBean, restTemplate);

            // Set up context mock chain — lenient because not every test exercises
            // every stub (e.g., setMessage path vs. syncCartToExternalStore path)
            lenient().when(mockContext.getMessages()).thenReturn(new ArrayList<Message>());
            lenient().when(mockContext.getRequest()).thenReturn(mockRequest);
            lenient().when(mockRequest.getSession()).thenReturn(mockSession);
            lenient().when(mockSession.getId()).thenReturn("test-session-id");
            cartActionBean.setContext(mockContext);
        }

        @Test
        void addItemToCart_WithValidNewItem_ShouldCallCatalogServiceAndAddToCart() {
            cartActionBean.setWorkingItemId("EST-1");

            // Mock inventory check — Catalog Service returns quantity > 0
            when(restTemplate.getForObject(
                eq(CATALOG_SERVICE_URL + "/items/EST-1/inventory"), eq(Integer.class)))
                .thenReturn(10);

            // Mock item details retrieval from Catalog Service
            Item item = new Item();
            item.setItemId("EST-1");
            when(restTemplate.getForObject(
                eq(CATALOG_SERVICE_URL + "/items/EST-1"), eq(Item.class)))
                .thenReturn(item);

            Resolution resolution = cartActionBean.addItemToCart();

            assertThat(resolution).isNotNull();
            assertThat(resolution.toString()).contains("Cart.jsp");
            assertThat(cartActionBean.getCart().containsItemId("EST-1")).isTrue();
            verify(restTemplate).getForObject(eq(CATALOG_SERVICE_URL + "/items/EST-1/inventory"), eq(Integer.class));
            verify(restTemplate).getForObject(eq(CATALOG_SERVICE_URL + "/items/EST-1"), eq(Item.class));
        }

        @Test
        void addItemToCart_WithExistingItem_ShouldIncrementQuantity() {
            // Pre-populate cart with an item
            Item existingItem = new Item();
            existingItem.setItemId("EST-1");
            cartActionBean.getCart().addItem(existingItem, true);

            cartActionBean.setWorkingItemId("EST-1");

            Resolution resolution = cartActionBean.addItemToCart();

            assertThat(resolution).isNotNull();
            assertThat(resolution.toString()).contains("Cart.jsp");
            // Quantity should be 2 now (was 1, incremented by addItemToCart)
            // No Catalog Service REST calls needed — just increment local cart
        }

        @Test
        void addItemToCart_WhenCatalogServiceUnavailable_ShouldReturnError() {
            cartActionBean.setWorkingItemId("EST-1");

            when(restTemplate.getForObject(anyString(), any(Class.class)))
                .thenThrow(new RestClientException("Service unavailable"));

            Resolution resolution = cartActionBean.addItemToCart();

            assertThat(resolution).isNotNull();
            assertThat(resolution.toString()).contains("Error.jsp");
        }

        @Test
        void addItemToCart_WithItemOutOfStock_ShouldStillAddToCart() {
            cartActionBean.setWorkingItemId("EST-2");

            // Inventory returns 0 — out of stock
            when(restTemplate.getForObject(
                eq(CATALOG_SERVICE_URL + "/items/EST-2/inventory"), eq(Integer.class)))
                .thenReturn(0);

            Item item = new Item();
            item.setItemId("EST-2");
            when(restTemplate.getForObject(
                eq(CATALOG_SERVICE_URL + "/items/EST-2"), eq(Item.class)))
                .thenReturn(item);

            Resolution resolution = cartActionBean.addItemToCart();

            assertThat(resolution).isNotNull();
            assertThat(resolution.toString()).contains("Cart.jsp");
            assertThat(cartActionBean.getCart().containsItemId("EST-2")).isTrue();
        }

        @Test
        void addItemToCart_WithNullInventoryResponse_ShouldTreatAsOutOfStock() {
            cartActionBean.setWorkingItemId("EST-3");

            // Inventory returns null — treated as out of stock (isInStock = false)
            when(restTemplate.getForObject(
                eq(CATALOG_SERVICE_URL + "/items/EST-3/inventory"), eq(Integer.class)))
                .thenReturn(null);

            Item item = new Item();
            item.setItemId("EST-3");
            when(restTemplate.getForObject(
                eq(CATALOG_SERVICE_URL + "/items/EST-3"), eq(Item.class)))
                .thenReturn(item);

            Resolution resolution = cartActionBean.addItemToCart();

            assertThat(resolution).isNotNull();
            assertThat(resolution.toString()).contains("Cart.jsp");
        }

        @Test
        void addItemToCart_ShouldSyncCartToExternalStore() {
            cartActionBean.setWorkingItemId("EST-1");

            when(restTemplate.getForObject(
                eq(CATALOG_SERVICE_URL + "/items/EST-1/inventory"), eq(Integer.class)))
                .thenReturn(5);

            Item item = new Item();
            item.setItemId("EST-1");
            when(restTemplate.getForObject(
                eq(CATALOG_SERVICE_URL + "/items/EST-1"), eq(Item.class)))
                .thenReturn(item);

            cartActionBean.addItemToCart();

            // Verify externalized cart sync was called via PUT to Order Service
            // After fix for finding #9, sync now sends Map<String, Integer> (itemId→qty)
            // instead of the raw Cart object, matching CartController's expected input format
            verify(restTemplate).put(eq(CART_SERVICE_URL + "/test-session-id"), any(Map.class));
        }

        @Test
        void removeItemFromCart_ShouldSyncCartToExternalStore() {
            // Pre-populate cart
            Item existingItem = new Item();
            existingItem.setItemId("EST-1");
            cartActionBean.getCart().addItem(existingItem, true);

            cartActionBean.setWorkingItemId("EST-1");

            cartActionBean.removeItemFromCart();

            // Verify externalized cart sync was called via PUT to Order Service
            // After fix for finding #9, sync now sends Map<String, Integer> (itemId→qty)
            verify(restTemplate).put(eq(CART_SERVICE_URL + "/test-session-id"), any(Map.class));
        }

        @Test
        void syncCartToExternalStore_WhenFails_ShouldNotPropagateError() {
            cartActionBean.setWorkingItemId("EST-1");

            when(restTemplate.getForObject(
                eq(CATALOG_SERVICE_URL + "/items/EST-1/inventory"), eq(Integer.class)))
                .thenReturn(5);

            Item item = new Item();
            item.setItemId("EST-1");
            when(restTemplate.getForObject(
                eq(CATALOG_SERVICE_URL + "/items/EST-1"), eq(Item.class)))
                .thenReturn(item);

            // Cart sync fails — should NOT propagate error (fire-and-forget)
            // After fix for finding #9, sync sends Map<String, Integer> instead of Cart
            doThrow(new RestClientException("Redis unavailable"))
                .when(restTemplate).put(anyString(), any(Map.class));

            Resolution resolution = cartActionBean.addItemToCart();

            // Should still return Cart.jsp, not Error.jsp — sync failure is non-fatal
            assertThat(resolution).isNotNull();
            assertThat(resolution.toString()).contains("Cart.jsp");
        }
    }
}
