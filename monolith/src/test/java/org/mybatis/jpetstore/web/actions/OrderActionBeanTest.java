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
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpSession;

import net.sourceforge.stripes.action.ActionBeanContext;
import net.sourceforge.stripes.action.Message;
import net.sourceforge.stripes.action.Resolution;
import net.sourceforge.stripes.config.Configuration;
import net.sourceforge.stripes.controller.ActionResolver;
import net.sourceforge.stripes.controller.StripesFilter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mybatis.jpetstore.domain.Account;
import org.mybatis.jpetstore.domain.Cart;
import org.mybatis.jpetstore.domain.Item;
import org.mybatis.jpetstore.domain.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

/**
 * Tests for the {@link OrderActionBean} — covers both basic property accessors and the
 * REST-client-based handler methods (listOrders, newOrderForm, newOrder, viewOrder) that
 * call the Order Service microservice via {@link RestTemplate} as part of the Strangler
 * Fig migration.
 *
 * <p>The {@code RestClientTests} inner class follows the same pattern used by
 * {@code AccountActionBeanTest}, {@code CatalogActionBeanTest}, and
 * {@code CartActionBeanTest}: Mockito mocks are injected via reflection for RestTemplate,
 * ActionBeanContext, HttpServletRequest, HttpSession, and a static mock for
 * {@link StripesFilter#getConfiguration()} is set up to allow {@code ForwardResolution}
 * construction without a running Stripes container.</p>
 */
class OrderActionBeanTest {

  // =========================================================================
  // Basic property accessor tests (Diffblue Cover baseline)
  // =========================================================================

  // Test written by Diffblue Cover.
  @Test
  void getOrderListOutputNull() {
    final OrderActionBean orderActionBean = new OrderActionBean();
    assertThat(orderActionBean.getOrderList()).isNull();
  }

  // Test written by Diffblue Cover.
  @Test
  void isShippingAddressRequiredOutputFalse() {
    final OrderActionBean orderActionBean = new OrderActionBean();
    assertThat(orderActionBean.isShippingAddressRequired()).isFalse();
  }

  // Test written by Diffblue Cover.
  @Test
  void constructorOutputNotNull() {
    final OrderActionBean actual = new OrderActionBean();
    assertThat(actual).isNotNull().isNotNull();
    assertThat(actual.getContext()).isNull();
  }

  // Test written by Diffblue Cover.
  @Test
  void isConfirmedOutputFalse() {
    final OrderActionBean orderActionBean = new OrderActionBean();
    assertThat(orderActionBean.isConfirmed()).isFalse();
  }

  @Test
  void getOrderOutputNotNull() {
    final OrderActionBean orderActionBean = new OrderActionBean();
    assertThat(orderActionBean.getOrder()).isNotNull();
  }

  @Test
  void getCreditCardTypesReturnsExpectedList() {
    final OrderActionBean orderActionBean = new OrderActionBean();
    assertThat(orderActionBean.getCreditCardTypes())
        .containsExactly("Visa", "MasterCard", "American Express");
  }

  @Test
  void setShippingAddressRequiredSetsValue() {
    final OrderActionBean bean = new OrderActionBean();
    bean.setShippingAddressRequired(true);
    assertThat(bean.isShippingAddressRequired()).isTrue();
  }

  @Test
  void setConfirmedSetsValue() {
    final OrderActionBean bean = new OrderActionBean();
    bean.setConfirmed(true);
    assertThat(bean.isConfirmed()).isTrue();
  }

  @Test
  void clearResetsState() {
    final OrderActionBean bean = new OrderActionBean();
    bean.setShippingAddressRequired(true);
    bean.setConfirmed(true);
    bean.setOrderId(42);
    bean.clear();
    assertThat(bean.isShippingAddressRequired()).isFalse();
    assertThat(bean.isConfirmed()).isFalse();
    assertThat(bean.getOrderList()).isNull();
  }

  // =========================================================================
  // REST-client-based handler method tests
  // =========================================================================

  /**
   * Nested test class for verifying REST-client-based handler methods in the
   * modified OrderActionBean. Tests use Mockito to mock the RestTemplate
   * injected via reflection, simulating calls to Order Service during the
   * Strangler Fig migration.
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

    private OrderActionBean orderActionBean;

    /** Static mock for StripesFilter — required because ForwardResolution(Class)
     *  internally calls StripesFilter.getConfiguration().getActionResolver().getUrlBinding(). */
    private MockedStatic<StripesFilter> mockedStripesFilter;

    private static final String ORDER_SERVICE_URL = "http://order-service:8083/api";
    private static final String CART_SERVICE_URL = "http://order-service:8083/api/cart";

    @BeforeEach
    void setUp() throws Exception {
      // Mock StripesFilter.getConfiguration() so that ForwardResolution(Class) can
      // resolve the URL binding for AccountActionBean without a running Stripes container.
      Configuration mockConfig = mock(Configuration.class);
      ActionResolver mockActionResolver = mock(ActionResolver.class);
      lenient().when(mockConfig.getActionResolver()).thenReturn(mockActionResolver);
      lenient().when(mockActionResolver.getUrlBinding(any())).thenReturn("/actions/Account.action");
      mockedStripesFilter = mockStatic(StripesFilter.class);
      mockedStripesFilter.when(StripesFilter::getConfiguration).thenReturn(mockConfig);

      orderActionBean = new OrderActionBean();
      // Inject mocked RestTemplate via reflection into private restTemplate field
      Field restTemplateField = OrderActionBean.class.getDeclaredField("restTemplate");
      restTemplateField.setAccessible(true);
      restTemplateField.set(orderActionBean, restTemplate);

      // Set up context mock chain for tests that need it.
      lenient().when(mockContext.getMessages()).thenReturn(new ArrayList<Message>());
      lenient().when(mockContext.getRequest()).thenReturn(mockRequest);
      lenient().when(mockRequest.getSession()).thenReturn(mockSession);
      lenient().when(mockSession.getId()).thenReturn("test-session-id");
      orderActionBean.setContext(mockContext);
    }

    @AfterEach
    void tearDown() {
      if (mockedStripesFilter != null) {
        mockedStripesFilter.close();
      }
    }

    // --- Helper to set up an authenticated session ---

    private AccountActionBean createAuthenticatedAccountBean(String username) {
      AccountActionBean accountBean = new AccountActionBean();
      Account account = accountBean.getAccount();
      account.setUsername(username);
      // Simulate authenticated state by setting the internal flag via reflection
      try {
        Field authenticatedField = AccountActionBean.class.getDeclaredField("authenticated");
        authenticatedField.setAccessible(true);
        authenticatedField.set(accountBean, true);
      } catch (Exception e) {
        throw new RuntimeException("Failed to set authenticated flag", e);
      }
      return accountBean;
    }

    // ================================================================
    // listOrders() tests
    // ================================================================

    @Test
    void listOrders_WithAuthenticatedUser_ShouldReturnOrderList() {
      AccountActionBean accountBean = createAuthenticatedAccountBean("j2ee");
      when(mockSession.getAttribute("/actions/Account.action")).thenReturn(accountBean);

      Order[] orders = new Order[] { new Order(), new Order() };
      when(restTemplate.getForObject(
          eq(ORDER_SERVICE_URL + "/orders?username={username}"), eq(Order[].class), eq("j2ee")))
          .thenReturn(orders);

      Resolution resolution = orderActionBean.listOrders();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("ListOrders.jsp");
      assertThat(orderActionBean.getOrderList()).hasSize(2);
    }

    @Test
    void listOrders_WithNullResponse_ShouldReturnEmptyList() {
      AccountActionBean accountBean = createAuthenticatedAccountBean("j2ee");
      when(mockSession.getAttribute("/actions/Account.action")).thenReturn(accountBean);

      when(restTemplate.getForObject(anyString(), eq(Order[].class), anyString()))
          .thenReturn(null);

      Resolution resolution = orderActionBean.listOrders();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("ListOrders.jsp");
      assertThat(orderActionBean.getOrderList()).isEmpty();
    }

    @Test
    void listOrders_WhenNotAuthenticated_ShouldRedirectToSignon() {
      when(mockSession.getAttribute("/actions/Account.action")).thenReturn(null);
      when(mockSession.getAttribute("accountBean")).thenReturn(null);

      Resolution resolution = orderActionBean.listOrders();

      assertThat(resolution).isNotNull();
      verifyNoInteractions(restTemplate);
    }

    @Test
    void listOrders_WhenOrderServiceFails_ShouldReturnError() {
      AccountActionBean accountBean = createAuthenticatedAccountBean("j2ee");
      when(mockSession.getAttribute("/actions/Account.action")).thenReturn(accountBean);

      when(restTemplate.getForObject(anyString(), eq(Order[].class), anyString()))
          .thenThrow(new RestClientException("503 Service Unavailable"));

      Resolution resolution = orderActionBean.listOrders();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("Error.jsp");
    }

    @Test
    void listOrders_WithAccountBeanAttribute_ShouldUseAlternateKey() {
      // Test the "accountBean" session attribute fallback path
      when(mockSession.getAttribute("/actions/Account.action")).thenReturn(null);
      AccountActionBean accountBean = createAuthenticatedAccountBean("testuser");
      when(mockSession.getAttribute("accountBean")).thenReturn(accountBean);

      Order[] orders = new Order[] { new Order() };
      when(restTemplate.getForObject(anyString(), eq(Order[].class), eq("testuser")))
          .thenReturn(orders);

      Resolution resolution = orderActionBean.listOrders();

      assertThat(resolution).isNotNull();
      assertThat(orderActionBean.getOrderList()).hasSize(1);
    }

    // ================================================================
    // newOrderForm() tests
    // ================================================================

    @Test
    void newOrderForm_WhenNotAuthenticated_ShouldRedirectToSignon() {
      when(mockSession.getAttribute("/actions/Account.action")).thenReturn(null);

      Resolution resolution = orderActionBean.newOrderForm();

      assertThat(resolution).isNotNull();
    }

    @Test
    void newOrderForm_WithExternalizedCart_ShouldInitOrder() {
      AccountActionBean accountBean = createAuthenticatedAccountBean("j2ee");
      accountBean.getAccount().setFirstName("John");
      when(mockSession.getAttribute("/actions/Account.action")).thenReturn(accountBean);

      // Return valid cart JSON from externalized cart state
      String cartJson = "{\"items\":[{\"itemId\":\"EST-1\",\"quantity\":2,\"unitPrice\":\"16.50\",\"inStock\":true}]}";
      when(restTemplate.getForObject(
          eq(CART_SERVICE_URL + "/test-session-id"), eq(String.class)))
          .thenReturn(cartJson);

      Resolution resolution = orderActionBean.newOrderForm();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("NewOrderForm.jsp");
      assertThat(orderActionBean.getOrder()).isNotNull();
    }

    @Test
    void newOrderForm_WithEmptyExternalCart_FallsBackToSessionCart() {
      AccountActionBean accountBean = createAuthenticatedAccountBean("j2ee");
      when(mockSession.getAttribute("/actions/Account.action")).thenReturn(accountBean);

      // Externalized cart returns empty/null
      when(restTemplate.getForObject(anyString(), eq(String.class)))
          .thenReturn(null);

      // Fall back to session cart bean
      CartActionBean cartBean = new CartActionBean();
      Cart cart = cartBean.getCart();
      Item item = new Item();
      item.setItemId("EST-1");
      item.setListPrice(new java.math.BigDecimal("16.50"));
      cart.addItem(item, true);
      when(mockSession.getAttribute("/actions/Cart.action")).thenReturn(cartBean);

      Resolution resolution = orderActionBean.newOrderForm();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("NewOrderForm.jsp");
    }

    @Test
    void newOrderForm_WithNoCart_ShouldReturnError() {
      AccountActionBean accountBean = createAuthenticatedAccountBean("j2ee");
      when(mockSession.getAttribute("/actions/Account.action")).thenReturn(accountBean);

      // Both cart sources fail
      when(restTemplate.getForObject(anyString(), eq(String.class)))
          .thenThrow(new RestClientException("Cart service unavailable"));
      when(mockSession.getAttribute("/actions/Cart.action")).thenReturn(null);

      Resolution resolution = orderActionBean.newOrderForm();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("Error.jsp");
    }

    @Test
    void newOrderForm_WithUnauthenticatedAccountBean_ShouldRedirectToSignon() {
      // AccountActionBean exists but is not authenticated
      AccountActionBean accountBean = new AccountActionBean();
      when(mockSession.getAttribute("/actions/Account.action")).thenReturn(accountBean);

      Resolution resolution = orderActionBean.newOrderForm();

      assertThat(resolution).isNotNull();
    }

    // ================================================================
    // newOrder() tests — state machine: shipping → confirm → submit
    // ================================================================

    @Test
    void newOrder_WhenShippingRequired_ShouldForwardToShippingForm() {
      orderActionBean.setShippingAddressRequired(true);

      Resolution resolution = orderActionBean.newOrder();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("ShippingForm.jsp");
      assertThat(orderActionBean.isShippingAddressRequired()).isFalse();
    }

    @Test
    void newOrder_WhenNotConfirmed_ShouldForwardToConfirmOrder() {
      orderActionBean.setShippingAddressRequired(false);
      orderActionBean.setConfirmed(false);

      Resolution resolution = orderActionBean.newOrder();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("ConfirmOrder.jsp");
    }

    @Test
    void newOrder_WhenConfirmed_ShouldSubmitOrderAndClearCart() {
      orderActionBean.setShippingAddressRequired(false);
      orderActionBean.setConfirmed(true);

      Order submittedOrder = new Order();
      submittedOrder.setOrderId(1001);
      when(restTemplate.postForEntity(
          eq(ORDER_SERVICE_URL + "/orders"), any(Order.class), eq(Order.class)))
          .thenReturn(new ResponseEntity<>(submittedOrder, HttpStatus.OK));

      // Mock cart clear operations
      doNothing().when(restTemplate).delete(anyString());
      CartActionBean cartBean = mock(CartActionBean.class);
      when(mockSession.getAttribute("/actions/Cart.action")).thenReturn(cartBean);

      Resolution resolution = orderActionBean.newOrder();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("ViewOrder.jsp");
      assertThat(orderActionBean.getOrder().getOrderId()).isEqualTo(1001);
      verify(restTemplate).delete(contains("cart/test-session-id"));
      verify(cartBean).clear();
    }

    @Test
    void newOrder_WhenSubmissionFails_ShouldReturnError() {
      orderActionBean.setShippingAddressRequired(false);
      orderActionBean.setConfirmed(true);

      when(restTemplate.postForEntity(anyString(), any(Order.class), eq(Order.class)))
          .thenThrow(new RestClientException("Order service unavailable"));

      Resolution resolution = orderActionBean.newOrder();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("Error.jsp");
    }

    @Test
    void newOrder_WhenCartClearFails_ShouldStillShowOrder() {
      orderActionBean.setShippingAddressRequired(false);
      orderActionBean.setConfirmed(true);

      Order submittedOrder = new Order();
      submittedOrder.setOrderId(1002);
      when(restTemplate.postForEntity(anyString(), any(Order.class), eq(Order.class)))
          .thenReturn(new ResponseEntity<>(submittedOrder, HttpStatus.OK));

      // Cart clear via REST fails — should be swallowed
      doThrow(new RestClientException("Redis unavailable")).when(restTemplate).delete(anyString());

      Resolution resolution = orderActionBean.newOrder();

      // Should still show the order despite cart clear failure
      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("ViewOrder.jsp");
    }

    @Test
    void newOrder_WhenOrderIsNull_ShouldReturnError() {
      orderActionBean.setShippingAddressRequired(false);
      orderActionBean.setConfirmed(true);
      orderActionBean.setOrder(null);

      Resolution resolution = orderActionBean.newOrder();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("Error.jsp");
      verifyNoInteractions(restTemplate);
    }

    // ================================================================
    // viewOrder() tests
    // ================================================================

    @Test
    void viewOrder_WithAuthenticatedUser_ShouldReturnOrder() {
      AccountActionBean accountBean = createAuthenticatedAccountBean("j2ee");
      when(mockSession.getAttribute("/actions/Account.action")).thenReturn(accountBean);

      Order fetchedOrder = new Order();
      fetchedOrder.setOrderId(100);
      fetchedOrder.setUsername("j2ee");
      orderActionBean.setOrderId(100);

      when(restTemplate.getForObject(eq(ORDER_SERVICE_URL + "/orders/100"), eq(Order.class)))
          .thenReturn(fetchedOrder);

      Resolution resolution = orderActionBean.viewOrder();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("ViewOrder.jsp");
      assertThat(orderActionBean.getOrder().getOrderId()).isEqualTo(100);
    }

    @Test
    void viewOrder_WhenNotAuthenticated_ShouldRedirectToSignon() {
      when(mockSession.getAttribute("/actions/Account.action")).thenReturn(null);
      when(mockSession.getAttribute("accountBean")).thenReturn(null);

      Resolution resolution = orderActionBean.viewOrder();

      assertThat(resolution).isNotNull();
      verifyNoInteractions(restTemplate);
    }

    @Test
    void viewOrder_WhenOrderServiceFails_ShouldReturnError() {
      AccountActionBean accountBean = createAuthenticatedAccountBean("j2ee");
      when(mockSession.getAttribute("/actions/Account.action")).thenReturn(accountBean);
      orderActionBean.setOrderId(100);

      when(restTemplate.getForObject(anyString(), eq(Order.class)))
          .thenThrow(new RestClientException("Order service down"));

      Resolution resolution = orderActionBean.viewOrder();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("Error.jsp");
    }

    @Test
    void viewOrder_WhenOrderIsNull_ShouldReturnError() {
      AccountActionBean accountBean = createAuthenticatedAccountBean("j2ee");
      when(mockSession.getAttribute("/actions/Account.action")).thenReturn(accountBean);
      orderActionBean.setOrderId(999);

      when(restTemplate.getForObject(anyString(), eq(Order.class)))
          .thenReturn(null);

      Resolution resolution = orderActionBean.viewOrder();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("Error.jsp");
    }

    @Test
    void viewOrder_WhenOrderBelongsToDifferentUser_ShouldReturnError() {
      AccountActionBean accountBean = createAuthenticatedAccountBean("j2ee");
      when(mockSession.getAttribute("/actions/Account.action")).thenReturn(accountBean);
      orderActionBean.setOrderId(200);

      Order otherUsersOrder = new Order();
      otherUsersOrder.setOrderId(200);
      otherUsersOrder.setUsername("otherUser");
      when(restTemplate.getForObject(anyString(), eq(Order.class)))
          .thenReturn(otherUsersOrder);

      Resolution resolution = orderActionBean.viewOrder();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("Error.jsp");
      // Order should be cleared to prevent data leakage
      assertThat(orderActionBean.getOrder()).isNull();
    }
  }
}
