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

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import javax.servlet.http.HttpSession;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import net.sourceforge.stripes.action.ForwardResolution;
import net.sourceforge.stripes.action.Resolution;
import net.sourceforge.stripes.action.SessionScope;

import org.mybatis.jpetstore.domain.Account;
import org.mybatis.jpetstore.domain.Cart;
import org.mybatis.jpetstore.domain.Item;
import org.mybatis.jpetstore.domain.Order;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * The Class OrderActionBean.
 *
 * <p>Session-scoped checkout and order-history controller. Coordinates multi-step order
 * creation (new order form &rarr; shipping &rarr; confirmation &rarr; submission), order
 * listing, and order viewing. Calls Order Service REST API via {@link RestTemplate},
 * replacing the previous {@code @SpringBean} injection of {@code OrderService} as part
 * of the monolith-to-microservices Strangler Fig migration.</p>
 *
 * <p>During the transition period, authentication state is read from the session-scoped
 * {@link AccountActionBean} (which itself now calls Account Service REST API internally).
 * Cart state is first attempted from the externalized cart store via Order Service REST API,
 * with a fallback to the session-scoped {@link CartActionBean} if the REST call fails.</p>
 *
 * @author Eduardo Macarron
 */
@SessionScope
public class OrderActionBean extends AbstractActionBean {

  private static final long serialVersionUID = -6171288227470176272L;

  private static final String CONFIRM_ORDER = "/WEB-INF/jsp/order/ConfirmOrder.jsp";
  private static final String LIST_ORDERS = "/WEB-INF/jsp/order/ListOrders.jsp";
  private static final String NEW_ORDER = "/WEB-INF/jsp/order/NewOrderForm.jsp";
  private static final String SHIPPING = "/WEB-INF/jsp/order/ShippingForm.jsp";
  private static final String VIEW_ORDER = "/WEB-INF/jsp/order/ViewOrder.jsp";

  private static final List<String> CARD_TYPE_LIST;

  private static final Logger LOG = LoggerFactory.getLogger(OrderActionBean.class);

  /** Transient REST client — recreated lazily after session deserialization. */
  private transient RestTemplate restTemplate;

  /**
   * Base URL for Order Service REST API (order CRUD and cart state).
   *
   * <p><strong>Known technical debt:</strong> Service URLs are hardcoded because Stripes
   * ActionBeans do not participate in Spring dependency injection. These constants should
   * be externalized to a configuration source (e.g., JNDI, system properties, or a
   * properties file read at startup) during post-transition cleanup when ActionBeans are
   * retired.</p>
   */
  private static final String ORDER_SERVICE_URL = "http://order-service:8083/api";

  /**
   * Base URL for externalized cart state managed by Order Service REST API.
   *
   * <p><strong>Known technical debt:</strong> Hardcoded for the same reason as
   * {@link #ORDER_SERVICE_URL}. See that field's documentation for context.</p>
   */
  private static final String CART_SERVICE_URL = "http://order-service:8083/api/cart";

  private Order order = new Order();
  private boolean shippingAddressRequired;
  private boolean confirmed;
  private List<Order> orderList;

  static {
    CARD_TYPE_LIST = Collections.unmodifiableList(Arrays.asList("Visa", "MasterCard", "American Express"));
  }

  /**
   * Lazily initializes and returns the {@link RestTemplate} instance used for REST API calls
   * to the Order Service microservice. The RestTemplate is transient (not serialized with the
   * session-scoped ActionBean) and is recreated if null after deserialization.
   *
   * @return the RestTemplate instance
   */
  private RestTemplate getRestTemplate() {
    if (restTemplate == null) {
      restTemplate = new RestTemplate();
    }
    return restTemplate;
  }

  public int getOrderId() {
    return order.getOrderId();
  }

  public void setOrderId(int orderId) {
    order.setOrderId(orderId);
  }

  public Order getOrder() {
    return order;
  }

  public void setOrder(Order order) {
    this.order = order;
  }

  public boolean isShippingAddressRequired() {
    return shippingAddressRequired;
  }

  public void setShippingAddressRequired(boolean shippingAddressRequired) {
    this.shippingAddressRequired = shippingAddressRequired;
  }

  public boolean isConfirmed() {
    return confirmed;
  }

  public void setConfirmed(boolean confirmed) {
    this.confirmed = confirmed;
  }

  public List<String> getCreditCardTypes() {
    return CARD_TYPE_LIST;
  }

  public List<Order> getOrderList() {
    return orderList;
  }

  /**
   * List orders.
   *
   * <p>Retrieves the authenticated user's order history from the Order Service REST API.
   * Redirects to the sign-on page if the user is not authenticated. Falls back to the
   * error page if the REST call fails.</p>
   *
   * @return the resolution
   */
  public Resolution listOrders() {
    HttpSession session = context.getRequest().getSession();
    String username = getAuthenticatedUsername(session);
    if (username == null) {
      setMessage("You must sign on before attempting to view orders.");
      return new ForwardResolution(AccountActionBean.class);
    }
    try {
      Order[] orders = getRestTemplate().getForObject(
          ORDER_SERVICE_URL + "/orders?username={username}", Order[].class, username);
      orderList = orders != null ? Arrays.asList(orders) : Collections.emptyList();
    } catch (Exception e) {
      LOG.warn("Failed to retrieve orders for user {}: {}", username, e.getMessage());
      setMessage("Unable to retrieve order list. Please try again.");
      return new ForwardResolution(ERROR);
    }
    return new ForwardResolution(LIST_ORDERS);
  }

  /**
   * New order form.
   *
   * <p>Initiates the checkout process by retrieving the authenticated user's account and
   * cart data. Cart state is first attempted from the externalized cart store via Order
   * Service REST API, with a fallback to the session-scoped {@link CartActionBean} during
   * the Strangler Fig transition period. Populates the order with account and cart data
   * via {@link Order#initOrder(Account, Cart)}.</p>
   *
   * @return the resolution
   */
  public Resolution newOrderForm() {
    HttpSession session = context.getRequest().getSession();
    clear();

    // Check authentication via session AccountActionBean (during transition, it stores REST-fetched account)
    AccountActionBean accountBean = (AccountActionBean) session.getAttribute("/actions/Account.action");
    if (accountBean == null || !accountBean.isAuthenticated()) {
      setMessage("You must sign on before attempting to check out.  Please sign on and try checking out again.");
      return new ForwardResolution(AccountActionBean.class);
    }

    Account account = accountBean.getAccount();

    // Retrieve cart from externalized state via REST.
    // CartController returns CartDTO JSON (fields: id, items, subTotal, numberOfItems) which
    // cannot be directly deserialized into the monolith Cart class (synchronized HashMap, no
    // matching setters). Instead, we fetch as raw JSON and reconstruct a Cart instance by
    // iterating the items array and calling Cart.addItem() + Cart.setQuantityByItemId().
    Cart cart = null;
    try {
      String cartJson = getRestTemplate().getForObject(
          CART_SERVICE_URL + "/" + session.getId(), String.class);
      if (cartJson != null) {
        ObjectMapper mapper = new ObjectMapper();
        JsonNode root = mapper.readTree(cartJson);
        JsonNode items = root.get("items");
        if (items != null && items.isArray() && items.size() > 0) {
          cart = new Cart();
          for (JsonNode itemNode : items) {
            Item item = new Item();
            item.setItemId(itemNode.get("itemId").asText());
            // Map unitPrice from CartItemDTO to listPrice on Item (used by Order.initOrder)
            if (itemNode.has("unitPrice") && !itemNode.get("unitPrice").isNull()) {
              item.setListPrice(new BigDecimal(itemNode.get("unitPrice").asText()));
            }
            boolean inStock = itemNode.has("inStock") && itemNode.get("inStock").asBoolean();
            cart.addItem(item, inStock);
            int quantity = itemNode.has("quantity") ? itemNode.get("quantity").asInt(1) : 1;
            if (quantity != 1) {
              cart.setQuantityByItemId(item.getItemId(), quantity);
            }
          }
        }
      }
    } catch (Exception e) {
      LOG.warn("Failed to retrieve cart from externalized state, falling back to session: {}", e.getMessage());
    }

    // Fallback: read cart from session CartActionBean during transition
    if (cart == null) {
      CartActionBean cartBean = (CartActionBean) session.getAttribute("/actions/Cart.action");
      if (cartBean != null) {
        cart = cartBean.getCart();
      }
    }

    if (cart != null) {
      order.initOrder(account, cart);
      return new ForwardResolution(NEW_ORDER);
    } else {
      setMessage("An order could not be created because a cart could not be found.");
      return new ForwardResolution(ERROR);
    }
  }

  /**
   * New order.
   *
   * <p>Processes the multi-step order submission. Handles the shipping address &rarr;
   * confirmation &rarr; submission state machine. On final submission, posts the order
   * to the Order Service REST API and clears both the externalized cart state and the
   * session-scoped cart bean.</p>
   *
   * @return the resolution
   */
  public Resolution newOrder() {
    HttpSession session = context.getRequest().getSession();

    if (shippingAddressRequired) {
      shippingAddressRequired = false;
      return new ForwardResolution(SHIPPING);
    } else if (!isConfirmed()) {
      return new ForwardResolution(CONFIRM_ORDER);
    } else if (getOrder() != null) {
      try {
        // Submit order to Order Service REST API and capture the server response
        // (which may include a server-assigned order ID and updated status)
        ResponseEntity<Order> orderResponse = getRestTemplate().postForEntity(
            ORDER_SERVICE_URL + "/orders", order, Order.class);
        Order submittedOrder = orderResponse.getBody();
        if (submittedOrder != null) {
          order = submittedOrder;
        }
      } catch (Exception e) {
        LOG.error("Failed to submit order: {}", e.getMessage());
        setMessage("An error occurred processing your order. Please try again.");
        return new ForwardResolution(ERROR);
      }

      // Clear externalized cart state
      try {
        getRestTemplate().delete(CART_SERVICE_URL + "/" + session.getId());
      } catch (Exception e) {
        LOG.warn("Failed to clear externalized cart: {}", e.getMessage());
      }

      // Also clear session cart bean during transition
      CartActionBean cartBean = (CartActionBean) session.getAttribute("/actions/Cart.action");
      if (cartBean != null) {
        cartBean.clear();
      }

      setMessage("Thank you, your order has been submitted.");
      return new ForwardResolution(VIEW_ORDER);
    } else {
      setMessage("An error occurred processing your order (order was null).");
      return new ForwardResolution(ERROR);
    }
  }

  /**
   * View order.
   *
   * <p>Retrieves a specific order from the Order Service REST API and verifies that
   * the authenticated user owns the order before displaying it. Returns an error if
   * the user is not authenticated, the order cannot be retrieved, or the user does
   * not own the requested order.</p>
   *
   * @return the resolution
   */
  public Resolution viewOrder() {
    HttpSession session = context.getRequest().getSession();
    String username = getAuthenticatedUsername(session);
    if (username == null) {
      setMessage("You must sign on to view orders.");
      return new ForwardResolution(AccountActionBean.class);
    }
    try {
      order = getRestTemplate().getForObject(
          ORDER_SERVICE_URL + "/orders/" + order.getOrderId(), Order.class);
    } catch (Exception e) {
      LOG.warn("Failed to retrieve order {}: {}", order.getOrderId(), e.getMessage());
      setMessage("Unable to retrieve order details.");
      return new ForwardResolution(ERROR);
    }
    if (order == null) {
      setMessage("Order not found.");
      return new ForwardResolution(ERROR);
    }
    if (username.equals(order.getUsername())) {
      return new ForwardResolution(VIEW_ORDER);
    } else {
      order = null;
      setMessage("You may only view your own orders.");
      return new ForwardResolution(ERROR);
    }
  }

  /**
   * Retrieves the authenticated username from the HTTP session.
   *
   * <p>During the Strangler Fig transition period, authentication state is read from the
   * session-scoped {@link AccountActionBean} (which itself now calls Account Service REST
   * API internally). Checks both session attribute keys used by the application:
   * {@code "/actions/Account.action"} (standard Stripes session key) and
   * {@code "accountBean"} (set explicitly in signon for backward compatibility).</p>
   *
   * @param session the current HTTP session
   * @return the authenticated username, or {@code null} if the user is not authenticated
   */
  private String getAuthenticatedUsername(HttpSession session) {
    // Check standard Stripes session attribute for AccountActionBean
    AccountActionBean accountBean = (AccountActionBean) session.getAttribute("/actions/Account.action");
    if (accountBean != null && accountBean.isAuthenticated()) {
      return accountBean.getAccount().getUsername();
    }
    // Also check "accountBean" attribute (set explicitly in signon for backward compatibility)
    accountBean = (AccountActionBean) session.getAttribute("accountBean");
    if (accountBean != null && accountBean.isAuthenticated()) {
      return accountBean.getAccount().getUsername();
    }
    return null;
  }

  /**
   * Clear.
   */
  public void clear() {
    order = new Order();
    shippingAddressRequired = false;
    confirmed = false;
    orderList = null;
  }

}
