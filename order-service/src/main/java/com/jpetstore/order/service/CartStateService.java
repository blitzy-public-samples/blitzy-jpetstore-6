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

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.jpetstore.order.client.CatalogServiceClient;
import com.jpetstore.order.dto.CartDTO;
import com.jpetstore.order.dto.CartItemDTO;
import com.jpetstore.order.entity.CartState;
import com.jpetstore.order.entity.CartState.CartItemData;
import com.jpetstore.order.exception.ResourceNotFoundException;
import com.jpetstore.order.repository.CartStateRepository;

/**
 * Service managing externalized cart state via Redis, replacing the monolith's
 * session-scoped {@code Cart.java}.
 *
 * <p>In the monolith, cart state lived in HTTP session via
 * {@code CartActionBean}'s {@code @SessionScope} annotation, using
 * {@code Collections.synchronizedMap(new HashMap<>())} and an
 * {@code ArrayList<CartItem>}. In the microservice architecture, cart state
 * is externalized to Redis via the {@link CartState} entity, accessed through
 * {@link CartStateRepository}, and managed by this service.</p>
 *
 * <h3>Key behavioral contracts preserved from monolith Cart.java:</h3>
 * <ul>
 *   <li>{@link #containsItemId(String, String)} — mirrors {@code Cart.containsItemId()}</li>
 *   <li>{@link #addItem(String, String)} — mirrors {@code Cart.addItem()} +
 *       {@code CartActionBean.addItemToCart()}: if existing, increment; if new, fetch
 *       from Catalog Service and add with quantity=1</li>
 *   <li>{@link #removeItemById(String, String)} — mirrors {@code Cart.removeItemById()}</li>
 *   <li>{@link #incrementQuantityByItemId(String, String)} — mirrors
 *       {@code Cart.incrementQuantityByItemId()}</li>
 *   <li>{@link #updateQuantity(String, String, int)} — mirrors
 *       {@code Cart.setQuantityByItemId()} with removal when quantity &lt; 1</li>
 *   <li>{@link #updateQuantities(String, Map)} — mirrors
 *       {@code CartActionBean.updateCartQuantities()} bulk update</li>
 *   <li>{@link #getSubTotal(String)} — mirrors {@code Cart.getSubTotal()} with
 *       identical BigDecimal stream-based arithmetic</li>
 *   <li>{@link #getCart(String)} — combines {@code Cart.getCartItemList()},
 *       {@code Cart.getNumberOfItems()}, and {@code Cart.getSubTotal()}</li>
 *   <li>{@link #clearCart(String)} — mirrors {@code CartActionBean.clear()}</li>
 *   <li>{@link #mergeCart(String, String)} — anonymous-to-user cart merge on login
 *       (per AAP Section 0.7.2)</li>
 * </ul>
 *
 * <h3>Cross-service communication:</h3>
 * <p>Item details are fetched from the Catalog Service via
 * {@link CatalogServiceClient#getItem(String)} REST calls, replacing the
 * monolith's in-process {@code @SpringBean CatalogService} injection.</p>
 *
 * <h3>Cart identification:</h3>
 * <p>Carts are keyed by session cookie (for anonymous users) or by username
 * (for authenticated users). On login, the anonymous cart is merged into the
 * user's persistent cart — preserving the existing monolith behavior where
 * unauthenticated users can browse and build a cart before signing in.</p>
 *
 * @see CartState
 * @see CartStateRepository
 * @see CatalogServiceClient
 */
@Service
public class CartStateService {

    private static final Logger log = LoggerFactory.getLogger(CartStateService.class);

    private final CartStateRepository cartStateRepository;
    private final CatalogServiceClient catalogServiceClient;

    /**
     * Constructs a new {@code CartStateService} with the required dependencies.
     *
     * @param cartStateRepository  the Redis repository for cart state persistence
     * @param catalogServiceClient the REST client for Catalog Service item lookups
     */
    public CartStateService(CartStateRepository cartStateRepository,
                            CatalogServiceClient catalogServiceClient) {
        this.cartStateRepository = cartStateRepository;
        this.catalogServiceClient = catalogServiceClient;
    }

    // -----------------------------------------------------------------------
    // Public API Methods
    // -----------------------------------------------------------------------

    /**
     * Checks whether the cart contains a specific item.
     *
     * <p>Replaces monolith {@code Cart.containsItemId(String)} (line 55-57):
     * {@code itemMap.containsKey(itemId)}.</p>
     *
     * @param cartId the cart identifier (session ID or username)
     * @param itemId the catalog item identifier to check
     * @return {@code true} if the cart contains the item, {@code false} if the
     *         item is not present or the cart does not exist
     */
    public boolean containsItemId(String cartId, String itemId) {
        return cartStateRepository.findById(cartId)
                .map(cart -> cart.getItems().containsKey(itemId))
                .orElse(false);
    }

    /**
     * Adds an item to the cart, or increments its quantity if already present.
     *
     * <p>Replaces the combined behavior of monolith {@code Cart.addItem(Item, boolean)}
     * (lines 67-78) and {@code CartActionBean.addItemToCart()} (lines 68-87):</p>
     * <ul>
     *   <li>If item already in cart: increment quantity by 1 (matching line 76-77)</li>
     *   <li>If item new: call {@link CatalogServiceClient#getItem(String)} to fetch
     *       item details (listPrice, productName, stock status), create a new
     *       {@link CartItemData} with quantity=1, and add to the cart</li>
     * </ul>
     *
     * <p>The monolith sets quantity=0 then calls {@code cartItem.incrementQuantity()},
     * which results in quantity=1. This method directly sets quantity=1 for new items,
     * achieving the same end state.</p>
     *
     * @param cartId the cart identifier (session ID or username)
     * @param itemId the catalog item identifier to add
     * @return the updated cart as a {@link CartDTO}
     * @throws ResourceNotFoundException if the item cannot be found in the Catalog Service
     */
    public CartDTO addItem(String cartId, String itemId) {
        CartState cart = cartStateRepository.findById(cartId)
                .orElseGet(() -> {
                    CartState newCart = new CartState();
                    newCart.setId(cartId);
                    newCart.setItems(new HashMap<>());
                    log.debug("Created new cart for id: {}", cartId);
                    return newCart;
                });

        if (cart.getItems().containsKey(itemId)) {
            // Item already in cart — increment quantity by 1
            // Matches monolith Cart.addItem() line 77: cartItem.incrementQuantity()
            CartItemData existingItem = cart.getItems().get(itemId);
            existingItem.setQuantity(existingItem.getQuantity() + 1);
            log.debug("Incremented quantity for item {} in cart {} to {}",
                    itemId, cartId, existingItem.getQuantity());
        } else {
            // New item — fetch details from Catalog Service via REST
            // Replaces monolith CartActionBean lines 81-83:
            //   boolean isInStock = catalogService.isItemInStock(workingItemId);
            //   Item item = catalogService.getItem(workingItemId);
            //   cart.addItem(item, isInStock);
            Optional<Map<String, Object>> itemDataOpt = catalogServiceClient.getItem(itemId);
            if (!itemDataOpt.isPresent()) {
                log.warn("Cannot add item to cart: item not found in Catalog Service: itemId={}",
                        itemId);
                throw new ResourceNotFoundException("Item " + itemId + " not found in catalog");
            }

            Map<String, Object> itemData = itemDataOpt.orElseThrow();

            CartItemData newItemData = new CartItemData();
            newItemData.setItemId(itemId);
            // Monolith sets quantity=0, then incrementQuantity() → 1. We set directly to 1.
            newItemData.setQuantity(1);

            // Extract listPrice (unitPrice) from catalog response
            BigDecimal listPrice = extractBigDecimal(itemData, "listPrice");
            newItemData.setUnitPrice(listPrice);

            // Extract product name from catalog response (may be nested in "product" object)
            String productName = extractProductName(itemData);
            newItemData.setProductName(productName);

            // Determine in-stock status from catalog response
            boolean inStock = extractInStock(itemData);
            newItemData.setInStock(inStock);

            cart.getItems().put(itemId, newItemData);
            log.info("Added new item {} to cart {} (unitPrice={}, inStock={}, productName={})",
                    itemId, cartId, listPrice, inStock, productName);
        }

        cart.setLastUpdated(Instant.now());
        cartStateRepository.save(cart);
        return convertToDTO(cart);
    }

    /**
     * Removes an item from the cart by its item ID.
     *
     * <p>Replaces monolith {@code Cart.removeItemById(String)} (lines 88-96):
     * removes the item from the map and returns the removed data. In the monolith,
     * it returns the {@code Item} object; here it returns the full updated cart.</p>
     *
     * @param cartId the cart identifier (session ID or username)
     * @param itemId the catalog item identifier to remove
     * @return the updated cart as a {@link CartDTO}
     * @throws ResourceNotFoundException if the cart does not exist or the item is not in the cart
     */
    public CartDTO removeItemById(String cartId, String itemId) {
        CartState cart = cartStateRepository.findById(cartId)
                .orElseThrow(() -> new ResourceNotFoundException("Cart not found: " + cartId));

        CartItemData removed = cart.getItems().remove(itemId);
        if (removed == null) {
            log.warn("Attempted to remove item not in cart: cartId={}, itemId={}", cartId, itemId);
            throw new ResourceNotFoundException("Item " + itemId + " not in cart");
        }

        cart.setLastUpdated(Instant.now());
        cartStateRepository.save(cart);
        log.info("Removed item {} from cart {}", itemId, cartId);
        return convertToDTO(cart);
    }

    /**
     * Increments the quantity of an item in the cart by one.
     *
     * <p>Replaces monolith {@code Cart.incrementQuantityByItemId(String)} (lines 104-107):
     * {@code cartItem = itemMap.get(itemId); cartItem.incrementQuantity();}.</p>
     *
     * <p>The monolith's {@code CartItem.incrementQuantity()} (line 66-68) does
     * {@code quantity++; calculateTotal();}. In the microservice, the total is
     * computed on-the-fly by the service, not stored on the entity.</p>
     *
     * @param cartId the cart identifier (session ID or username)
     * @param itemId the catalog item identifier whose quantity to increment
     * @throws RuntimeException if the cart or item does not exist
     */
    public void incrementQuantityByItemId(String cartId, String itemId) {
        CartState cart = cartStateRepository.findById(cartId)
                .orElseThrow(() -> new RuntimeException("Cart not found: " + cartId));

        CartItemData item = cart.getItems().get(itemId);
        if (item == null) {
            log.error("Cannot increment quantity: item not in cart: cartId={}, itemId={}",
                    cartId, itemId);
            throw new RuntimeException("Item not in cart: " + itemId);
        }

        item.setQuantity(item.getQuantity() + 1);
        cart.setLastUpdated(Instant.now());
        cartStateRepository.save(cart);
        log.debug("Incremented quantity for item {} in cart {} to {}",
                itemId, cartId, item.getQuantity());
    }

    /**
     * Updates the quantity of a specific item in the cart.
     *
     * <p>Replaces monolith {@code Cart.setQuantityByItemId(String, int)} (lines 109-112)
     * combined with the removal behavior from {@code CartActionBean.updateCartQuantities()}
     * (lines 126-128): if quantity &lt; 1, the item is removed from the cart.</p>
     *
     * @param cartId   the cart identifier (session ID or username)
     * @param itemId   the catalog item identifier to update
     * @param quantity the new quantity; if &lt; 1, the item is removed
     * @return the updated cart as a {@link CartDTO}
     * @throws RuntimeException if the cart does not exist or the item is not found
     *                          (when quantity &gt;= 1)
     */
    public CartDTO updateQuantity(String cartId, String itemId, int quantity) {
        CartState cart = cartStateRepository.findById(cartId)
                .orElseThrow(() -> new RuntimeException("Cart not found: " + cartId));

        if (quantity < 1) {
            // Matches monolith CartActionBean lines 126-128:
            //   if (quantity < 1) { cartItems.remove(); }
            cart.getItems().remove(itemId);
            log.debug("Removed item {} from cart {} due to quantity < 1", itemId, cartId);
        } else {
            CartItemData item = cart.getItems().get(itemId);
            if (item == null) {
                log.error("Cannot update quantity: item not in cart: cartId={}, itemId={}",
                        cartId, itemId);
                throw new RuntimeException("Item not in cart: " + itemId);
            }
            item.setQuantity(quantity);
            log.debug("Updated quantity for item {} in cart {} to {}", itemId, cartId, quantity);
        }

        cart.setLastUpdated(Instant.now());
        cartStateRepository.save(cart);
        return convertToDTO(cart);
    }

    /**
     * Bulk-updates quantities for multiple items in the cart.
     *
     * <p>Replaces monolith {@code CartActionBean.updateCartQuantities()} (lines 116-135),
     * which iterates over all cart items, reads quantities from HTTP request parameters,
     * sets each quantity, and removes items with quantity &lt; 1.</p>
     *
     * <p>In the microservice, the request parameters are already parsed into a
     * {@code Map<String, Integer>} by the controller layer, so this method processes
     * the pre-parsed map directly.</p>
     *
     * <p>Invalid items (items in the quantities map but not in the cart) are silently
     * ignored, matching the monolith's behavior where a {@code NumberFormatException}
     * for invalid numeric input is silently caught (CartActionBean line 129-131).</p>
     *
     * @param cartId     the cart identifier (session ID or username)
     * @param quantities a map of item IDs to their new quantities; entries with
     *                   quantity &lt; 1 trigger item removal
     * @return the updated cart as a {@link CartDTO}
     * @throws RuntimeException if the cart does not exist
     */
    public CartDTO updateQuantities(String cartId, Map<String, Integer> quantities) {
        CartState cart = cartStateRepository.findById(cartId)
                .orElseThrow(() -> new RuntimeException("Cart not found: " + cartId));

        for (Map.Entry<String, Integer> entry : quantities.entrySet()) {
            String itemId = entry.getKey();
            int quantity = entry.getValue();

            if (quantity < 1) {
                // Matches monolith CartActionBean lines 126-128: remove if quantity < 1
                cart.getItems().remove(itemId);
                log.debug("Removed item {} from cart {} (bulk update, quantity < 1)",
                        itemId, cartId);
            } else {
                CartItemData item = cart.getItems().get(itemId);
                if (item != null) {
                    item.setQuantity(quantity);
                }
                // Silently ignore items not in cart, matching monolith behavior
                // where NumberFormatException is silently caught
            }
        }

        cart.setLastUpdated(Instant.now());
        cartStateRepository.save(cart);
        log.debug("Bulk updated quantities for cart {}: {} item(s) processed",
                cartId, quantities.size());
        return convertToDTO(cart);
    }

    /**
     * Computes the subtotal of all items in the cart.
     *
     * <p><strong>CRITICAL — Exact BigDecimal replication of monolith
     * {@code Cart.getSubTotal()} (lines 119-123):</strong></p>
     * <pre>
     * return itemList.stream()
     *     .map(cartItem -> cartItem.getItem().getListPrice()
     *         .multiply(new BigDecimal(cartItem.getQuantity())))
     *     .reduce(BigDecimal.ZERO, BigDecimal::add);
     * </pre>
     *
     * <p>In the microservice, {@code cartItem.getItem().getListPrice()} is replaced
     * by {@code CartItemData.getUnitPrice()} (denormalized from Catalog Service at
     * add-time). The BigDecimal arithmetic is identical: {@code unitPrice × quantity}
     * for each item, reduced with {@code BigDecimal::add}.</p>
     *
     * @param cartId the cart identifier (session ID or username)
     * @return the subtotal as a {@link BigDecimal}, or {@code BigDecimal.ZERO} if
     *         the cart is empty or does not exist
     */
    public BigDecimal getSubTotal(String cartId) {
        return cartStateRepository.findById(cartId)
                .map(cart -> cart.getItems().values().stream()
                        .map(item -> item.getUnitPrice()
                                .multiply(new BigDecimal(item.getQuantity())))
                        .reduce(BigDecimal.ZERO, BigDecimal::add))
                .orElse(BigDecimal.ZERO);
    }

    /**
     * Retrieves the full cart state as a DTO.
     *
     * <p>Combines the behavior of monolith {@code Cart.getCartItemList()},
     * {@code Cart.getNumberOfItems()}, and {@code Cart.getSubTotal()} into a
     * single comprehensive response. If the cart does not exist in Redis, an
     * empty cart DTO is returned (not null).</p>
     *
     * @param cartId the cart identifier (session ID or username)
     * @return the cart as a {@link CartDTO}, never {@code null}
     */
    public CartDTO getCart(String cartId) {
        CartState state = cartStateRepository.findById(cartId)
                .orElseGet(() -> {
                    CartState emptyCart = new CartState();
                    emptyCart.setId(cartId);
                    emptyCart.setItems(new HashMap<>());
                    return emptyCart;
                });
        log.debug("Retrieved cart for id={}, lastUpdated={}, itemCount={}",
                cartId, state.getLastUpdated(), state.getItems().size());
        return convertToDTO(state);
    }

    /**
     * Clears the entire cart by removing it from Redis.
     *
     * <p>Replaces monolith {@code CartActionBean.clear()} (lines 145-148):
     * {@code cart = new Cart(); workingItemId = null;}. In the microservice,
     * the cart entry is deleted from Redis entirely rather than reset to an
     * empty object.</p>
     *
     * <p>Called by {@code OrderService} after successful order placement to
     * clear the user's cart, or by the user explicitly clearing their cart.</p>
     *
     * @param cartId the cart identifier (session ID or username)
     */
    public void clearCart(String cartId) {
        cartStateRepository.deleteById(cartId);
        log.info("Cart cleared for id: {}", cartId);
    }

    /**
     * Merges an anonymous cart into a user's persistent cart upon login.
     *
     * <p>Per AAP Section 0.7.2: "On login, the anonymous cart is merged into
     * the user's persistent cart — preserving the existing behavior that
     * unauthenticated users can browse and build a cart before signing in."</p>
     *
     * <p>Merge rules:</p>
     * <ul>
     *   <li>If item exists in both carts: quantities are summed</li>
     *   <li>If item only in anonymous cart: copied to user cart with all
     *       denormalized data (unitPrice, productName, inStock)</li>
     *   <li>If item only in user cart: unchanged</li>
     * </ul>
     *
     * <p>After merging, the anonymous cart is deleted from Redis. If no
     * anonymous cart exists, the user's cart is returned as-is.</p>
     *
     * @param anonymousCartId the anonymous session cart identifier
     * @param userCartId      the authenticated user's cart identifier (username)
     * @return the merged cart as a {@link CartDTO}
     */
    public CartDTO mergeCart(String anonymousCartId, String userCartId) {
        Optional<CartState> anonymousCartOpt = cartStateRepository.findById(anonymousCartId);
        if (!anonymousCartOpt.isPresent()) {
            log.debug("No anonymous cart found for merging: anonymousCartId={}", anonymousCartId);
            return getCart(userCartId);
        }

        CartState anonymousCart = anonymousCartOpt.orElseThrow();
        CartState userCart = cartStateRepository.findById(userCartId)
                .orElseGet(() -> {
                    CartState newCart = new CartState();
                    newCart.setId(userCartId);
                    newCart.setItems(new HashMap<>());
                    return newCart;
                });

        // Merge items from anonymous cart into user cart
        for (Map.Entry<String, CartItemData> entry : anonymousCart.getItems().entrySet()) {
            String itemId = entry.getKey();
            CartItemData anonItem = entry.getValue();

            if (userCart.getItems().containsKey(itemId)) {
                // Item exists in both carts — sum quantities
                CartItemData userItem = userCart.getItems().get(itemId);
                int mergedQuantity = userItem.getQuantity() + anonItem.getQuantity();
                userItem.setQuantity(mergedQuantity);
                log.debug("Merged item {} quantities: user={} + anon={} = {}",
                        itemId,
                        userItem.getQuantity() - anonItem.getQuantity(),
                        anonItem.getQuantity(),
                        mergedQuantity);
            } else {
                // Item only in anonymous cart — copy to user cart
                CartItemData copiedItem = new CartItemData();
                copiedItem.setItemId(anonItem.getItemId());
                copiedItem.setQuantity(anonItem.getQuantity());
                copiedItem.setUnitPrice(anonItem.getUnitPrice());
                copiedItem.setInStock(anonItem.isInStock());
                copiedItem.setProductName(anonItem.getProductName());
                userCart.getItems().put(itemId, copiedItem);
                log.debug("Copied item {} from anonymous cart to user cart (qty={}, price={})",
                        itemId, anonItem.getQuantity(), anonItem.getUnitPrice());
            }
        }

        userCart.setLastUpdated(Instant.now());
        cartStateRepository.save(userCart);

        // Delete the anonymous cart after successful merge
        cartStateRepository.deleteById(anonymousCartId);
        log.info("Merged anonymous cart {} into user cart {} ({} items merged)",
                anonymousCartId, userCartId, anonymousCart.getItems().size());

        return convertToDTO(userCart);
    }

    // -----------------------------------------------------------------------
    // Private Helpers
    // -----------------------------------------------------------------------

    /**
     * Converts a {@link CartState} Redis entity to a {@link CartDTO} REST response.
     *
     * <p>Maps each {@link CartItemData} to a {@link CartItemDTO} and computes
     * the cart subtotal using the same BigDecimal arithmetic as the monolith's
     * {@code Cart.getSubTotal()} (lines 119-123): {@code unitPrice × quantity}
     * for each item, reduced with {@code BigDecimal::add}.</p>
     *
     * @param state the cart state entity from Redis
     * @return the cart DTO with computed subtotal and item totals
     */
    private CartDTO convertToDTO(CartState state) {
        CartDTO dto = new CartDTO();
        dto.setId(state.getId());

        if (state.getItems() == null || state.getItems().isEmpty()) {
            dto.setItems(new ArrayList<>());
            dto.setSubTotal(BigDecimal.ZERO);
            dto.setNumberOfItems(0);
            return dto;
        }

        // Convert CartItemData entries to CartItemDTO list using stream + Collectors
        List<CartItemDTO> itemDTOs = state.getItems().values().stream()
                .map(itemData -> {
                    CartItemDTO itemDTO = new CartItemDTO();
                    itemDTO.setItemId(itemData.getItemId());
                    itemDTO.setQuantity(itemData.getQuantity());
                    itemDTO.setInStock(itemData.isInStock());
                    itemDTO.setUnitPrice(itemData.getUnitPrice());

                    // Compute per-item total: unitPrice × quantity
                    // Matches monolith CartItem.calculateTotal() (line 71-73):
                    //   item.getListPrice().multiply(new BigDecimal(quantity))
                    BigDecimal itemTotal = (itemData.getUnitPrice() != null)
                            ? itemData.getUnitPrice().multiply(new BigDecimal(itemData.getQuantity()))
                            : BigDecimal.ZERO;
                    itemDTO.setTotal(itemTotal);

                    log.debug("Cart item DTO: itemId={}, qty={}, unitPrice={}, total={}, product={}",
                            itemData.getItemId(), itemData.getQuantity(),
                            itemData.getUnitPrice(), itemTotal, itemData.getProductName());

                    return itemDTO;
                })
                .collect(Collectors.toList());

        // Compute subtotal: sum of all item totals
        // Matches monolith Cart.getSubTotal() (lines 119-123):
        //   itemList.stream()
        //     .map(ci -> ci.getItem().getListPrice().multiply(new BigDecimal(ci.getQuantity())))
        //     .reduce(BigDecimal.ZERO, BigDecimal::add)
        BigDecimal subTotal = itemDTOs.stream()
                .map(CartItemDTO::getTotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        dto.setItems(itemDTOs);
        dto.setSubTotal(subTotal);
        dto.setNumberOfItems(itemDTOs.size());

        return dto;
    }

    /**
     * Extracts a {@link BigDecimal} value from a catalog response map.
     *
     * <p>The Catalog Service REST API returns item data as
     * {@code Map<String, Object>}. This method safely extracts and converts
     * the specified key's value to {@code BigDecimal}, handling various
     * numeric types (BigDecimal, Number, String).</p>
     *
     * @param data the catalog item response map
     * @param key  the field name to extract (e.g., "listPrice")
     * @return the extracted value as {@code BigDecimal}, or {@code BigDecimal.ZERO}
     *         if the key is missing, null, or not parseable
     */
    private BigDecimal extractBigDecimal(Map<String, Object> data, String key) {
        Object value = data.get(key);
        if (value == null) {
            log.warn("Missing '{}' field in catalog response, defaulting to ZERO", key);
            return BigDecimal.ZERO;
        }
        if (value instanceof BigDecimal) {
            return (BigDecimal) value;
        }
        if (value instanceof Number) {
            return new BigDecimal(value.toString());
        }
        try {
            return new BigDecimal(value.toString());
        } catch (NumberFormatException e) {
            log.warn("Could not parse BigDecimal from catalog response for key '{}': value='{}'",
                    key, value);
            return BigDecimal.ZERO;
        }
    }

    /**
     * Extracts the product name from a catalog item response map.
     *
     * <p>Checks for a nested "product" object first (matching the Catalog Service's
     * JPA entity structure where {@code Item} has a {@code @ManyToOne Product}),
     * then falls back to a flat "productName" field.</p>
     *
     * @param data the catalog item response map
     * @return the product name, or an empty string if not found
     */
    private String extractProductName(Map<String, Object> data) {
        // Check for nested product object (Catalog Service returns Item with Product)
        Object product = data.get("product");
        if (product instanceof Map) {
            @SuppressWarnings("unchecked")
            Map<String, Object> productMap = (Map<String, Object>) product;
            Object name = productMap.get("name");
            if (name != null) {
                return name.toString();
            }
        }
        // Fallback: check for flat productName field
        Object name = data.get("productName");
        if (name != null) {
            return name.toString();
        }
        log.debug("No product name found in catalog response, defaulting to empty string");
        return "";
    }

    /**
     * Extracts the in-stock status from a catalog item response map.
     *
     * <p>Checks for an explicit "inStock" boolean field first, then falls back
     * to checking if the "quantity" field (from inventory data) is greater than zero.
     * This mirrors the monolith's {@code catalogService.isItemInStock(itemId)}
     * which queries {@code SELECT COUNT(*) FROM INVENTORY WHERE ITEMID = ? AND QTY > 0}.</p>
     *
     * @param data the catalog item response map
     * @return {@code true} if the item is in stock, {@code false} otherwise
     */
    private boolean extractInStock(Map<String, Object> data) {
        // Check for explicit inStock boolean field
        Object inStock = data.get("inStock");
        if (inStock instanceof Boolean) {
            return (Boolean) inStock;
        }
        // Fallback: check inventory quantity > 0
        Object qty = data.get("quantity");
        if (qty instanceof Number) {
            return ((Number) qty).intValue() > 0;
        }
        log.debug("Could not determine stock status from catalog response, defaulting to false");
        return false;
    }
}
