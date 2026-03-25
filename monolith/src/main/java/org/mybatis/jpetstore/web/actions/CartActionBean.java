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

import java.util.Iterator;

import javax.servlet.http.HttpServletRequest;

import net.sourceforge.stripes.action.ForwardResolution;
import net.sourceforge.stripes.action.Resolution;
import net.sourceforge.stripes.action.SessionScope;

import org.mybatis.jpetstore.domain.Cart;
import org.mybatis.jpetstore.domain.CartItem;
import org.mybatis.jpetstore.domain.Item;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

/**
 * The Class CartActionBean.
 *
 * <p>Session-scoped shopping cart controller. Calls Catalog Service REST API for item
 * lookups and inventory stock checks, and syncs cart state to the externalized
 * cart store managed by Order Service during the Strangler Fig transition period.</p>
 *
 * <p>Local cart state remains authoritative during the coexistence window. External
 * sync is best-effort — failures are logged but not surfaced to the user.</p>
 *
 * @author Eduardo Macarron
 */
@SessionScope
public class CartActionBean extends AbstractActionBean {

  private static final long serialVersionUID = -4038684592582714235L;

  private static final String VIEW_CART = "/WEB-INF/jsp/cart/Cart.jsp";
  private static final String CHECK_OUT = "/WEB-INF/jsp/cart/Checkout.jsp";

  private static final Logger LOG = LoggerFactory.getLogger(CartActionBean.class);

  /** Base URL for Catalog Service REST API (item lookups and inventory checks). */
  private static final String CATALOG_SERVICE_URL = "http://catalog-service:8082/api";

  /** Base URL for externalized cart state managed by Order Service REST API. */
  private static final String CART_SERVICE_URL = "http://order-service:8083/api/cart";

  /**
   * Lazily-initialized RestTemplate for REST API calls to Catalog Service and
   * Order Service. Marked transient because RestTemplate is not serializable
   * and this bean is session-scoped.
   */
  private transient RestTemplate restTemplate;

  private Cart cart = new Cart();
  private String workingItemId;

  /**
   * Returns a lazily-initialized RestTemplate instance. Re-creates the instance
   * after deserialization since the field is transient.
   *
   * @return the RestTemplate for making REST API calls
   */
  private RestTemplate getRestTemplate() {
    if (restTemplate == null) {
      restTemplate = new RestTemplate();
    }
    return restTemplate;
  }

  public Cart getCart() {
    return cart;
  }

  public void setCart(Cart cart) {
    this.cart = cart;
  }

  public void setWorkingItemId(String workingItemId) {
    this.workingItemId = workingItemId;
  }

  /**
   * Adds the item to cart.
   *
   * <p>If the item is already in the cart, its quantity is incremented. Otherwise,
   * the item details and stock status are retrieved from the Catalog Service REST API
   * and the item is added to the cart.</p>
   *
   * <p>After modifying the local cart, the updated state is synced to the externalized
   * cart store via the Order Service REST API (best-effort).</p>
   *
   * @return the resolution forwarding to the cart view or error page
   */
  public Resolution addItemToCart() {

    if (workingItemId == null || workingItemId.trim().isEmpty()) {
      setMessage("Invalid item ID: cannot add item to cart.");
      return new ForwardResolution(ERROR);
    }

    if (cart.containsItemId(workingItemId)) {
      cart.incrementQuantityByItemId(workingItemId);
    } else {
      try {
        // Call Catalog Service REST API for real-time inventory stock check.
        // isInStock is a "real-time" property that must be updated every time
        // an item is added to the cart, even if other item details are cached.
        Integer inventoryCount = getRestTemplate().getForObject(
            CATALOG_SERVICE_URL + "/items/" + workingItemId + "/inventory", Integer.class);
        boolean isInStock = inventoryCount != null && inventoryCount > 0;

        // Call Catalog Service REST API for item details
        Item item = getRestTemplate().getForObject(
            CATALOG_SERVICE_URL + "/items/" + workingItemId, Item.class);
        cart.addItem(item, isInStock);
      } catch (Exception e) {
        LOG.error("Failed to retrieve item {} from Catalog Service: {}", workingItemId, e.getMessage());
        setMessage("Unable to add item to cart. Please try again.");
        return new ForwardResolution(ERROR);
      }
    }

    // Sync to externalized cart state (best-effort during transition)
    syncCartToExternalStore();

    return new ForwardResolution(VIEW_CART);
  }

  /**
   * Removes the item from cart.
   *
   * <p>After removing the item from the local cart, the updated state is synced
   * to the externalized cart store via the Order Service REST API (best-effort).</p>
   *
   * @return the resolution forwarding to the cart view or error page
   */
  public Resolution removeItemFromCart() {

    if (workingItemId == null || workingItemId.trim().isEmpty()) {
      setMessage("Invalid item ID: cannot remove item from cart.");
      return new ForwardResolution(ERROR);
    }

    Item item = cart.removeItemById(workingItemId);

    if (item == null) {
      setMessage("Attempted to remove null CartItem from Cart.");
      return new ForwardResolution(ERROR);
    } else {
      // Sync removal to externalized cart state (best-effort during transition)
      syncCartToExternalStore();
      return new ForwardResolution(VIEW_CART);
    }
  }

  /**
   * Update cart quantities.
   *
   * <p>Reads quantity values from the HTTP request parameters (keyed by item ID)
   * and updates the local cart accordingly. Items with quantity less than 1 are
   * removed from the cart. Invalid numeric input is silently ignored.</p>
   *
   * <p>After updating the local cart, the updated state is synced to the externalized
   * cart store via the Order Service REST API (best-effort).</p>
   *
   * @return the resolution forwarding to the cart view
   */
  public Resolution updateCartQuantities() {
    HttpServletRequest request = context.getRequest();

    Iterator<CartItem> cartItems = getCart().getAllCartItems();
    while (cartItems.hasNext()) {
      CartItem cartItem = cartItems.next();
      String itemId = cartItem.getItem().getItemId();
      try {
        int quantity = Integer.parseInt(request.getParameter(itemId));
        getCart().setQuantityByItemId(itemId, quantity);
        if (quantity < 1) {
          cartItems.remove();
        }
      } catch (NumberFormatException e) {
        // ignore invalid numeric input on purpose
      }
    }

    // Sync updated quantities to externalized cart state (best-effort during transition)
    syncCartToExternalStore();

    return new ForwardResolution(VIEW_CART);
  }

  public ForwardResolution viewCart() {
    return new ForwardResolution(VIEW_CART);
  }

  public ForwardResolution checkOut() {
    return new ForwardResolution(CHECK_OUT);
  }

  public void clear() {
    cart = new Cart();
    workingItemId = null;
  }

  /**
   * Syncs the local cart state to the externalized cart store via Order Service REST API.
   *
   * <p>During the Strangler Fig transition period, the local (session-scoped) cart state
   * remains the authoritative source of truth. This method performs a best-effort PUT
   * to the Order Service's cart endpoint, keyed by the HTTP session ID, so that the
   * externalized store stays approximately in sync.</p>
   *
   * <p>Failures are logged as warnings but are intentionally not surfaced to the user.
   * If the Order Service is temporarily unavailable, the user experience is unaffected
   * because the local cart continues to function normally.</p>
   */
  private void syncCartToExternalStore() {
    try {
      String sessionId = context.getRequest().getSession().getId();
      getRestTemplate().put(CART_SERVICE_URL + "/" + sessionId, cart);
    } catch (Exception e) {
      LOG.warn("Failed to sync cart to external store: {}", e.getMessage());
    }
  }

}
