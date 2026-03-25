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

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.sourceforge.stripes.action.DefaultHandler;
import net.sourceforge.stripes.action.ForwardResolution;
import net.sourceforge.stripes.action.SessionScope;

import org.mybatis.jpetstore.domain.Category;
import org.mybatis.jpetstore.domain.Item;
import org.mybatis.jpetstore.domain.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.client.RestTemplate;

/**
 * The Class CatalogActionBean.
 *
 * <p>Session-scoped catalog browsing controller. Calls Catalog Service REST API
 * via {@link RestTemplate} for all catalog reads (categories, products, items, search).</p>
 *
 * @author Eduardo Macarron
 */
@SessionScope
public class CatalogActionBean extends AbstractActionBean {

  private static final long serialVersionUID = 5849523372175050635L;

  private static final String MAIN = "/WEB-INF/jsp/catalog/Main.jsp";
  private static final String VIEW_CATEGORY = "/WEB-INF/jsp/catalog/Category.jsp";
  private static final String VIEW_PRODUCT = "/WEB-INF/jsp/catalog/Product.jsp";
  private static final String VIEW_ITEM = "/WEB-INF/jsp/catalog/Item.jsp";
  private static final String SEARCH_PRODUCTS = "/WEB-INF/jsp/catalog/SearchProducts.jsp";

  private static final Logger LOG = LoggerFactory.getLogger(CatalogActionBean.class);

  private transient RestTemplate restTemplate;

  private static final String CATALOG_SERVICE_URL = "http://catalog-service:8082/api";

  private String keyword;

  private String categoryId;
  private Category category;
  private List<Category> categoryList;

  private String productId;
  private Product product;
  private List<Product> productList;

  private String itemId;
  private Item item;
  private List<Item> itemList;

  /**
   * Lazily initializes and returns the {@link RestTemplate} instance used for REST API calls
   * to the Catalog Service microservice. The RestTemplate is transient (not serialized with
   * the session-scoped ActionBean) and is recreated if null after deserialization.
   *
   * @return the RestTemplate instance
   */
  private RestTemplate getRestTemplate() {
    if (restTemplate == null) {
      restTemplate = new RestTemplate();
    }
    return restTemplate;
  }

  public String getKeyword() {
    return keyword;
  }

  public void setKeyword(String keyword) {
    this.keyword = keyword;
  }

  public String getCategoryId() {
    return categoryId;
  }

  public void setCategoryId(String categoryId) {
    this.categoryId = categoryId;
  }

  public String getProductId() {
    return productId;
  }

  public void setProductId(String productId) {
    this.productId = productId;
  }

  public String getItemId() {
    return itemId;
  }

  public void setItemId(String itemId) {
    this.itemId = itemId;
  }

  public Category getCategory() {
    return category;
  }

  public void setCategory(Category category) {
    this.category = category;
  }

  public Product getProduct() {
    return product;
  }

  public void setProduct(Product product) {
    this.product = product;
  }

  public Item getItem() {
    return item;
  }

  public void setItem(Item item) {
    this.item = item;
  }

  public List<Category> getCategoryList() {
    return categoryList;
  }

  public void setCategoryList(List<Category> categoryList) {
    this.categoryList = categoryList;
  }

  public List<Product> getProductList() {
    return productList;
  }

  public void setProductList(List<Product> productList) {
    this.productList = productList;
  }

  public List<Item> getItemList() {
    return itemList;
  }

  public void setItemList(List<Item> itemList) {
    this.itemList = itemList;
  }

  @DefaultHandler
  public ForwardResolution viewMain() {
    return new ForwardResolution(MAIN);
  }

  /**
   * View category.
   *
   * <p>Retrieves the product list and category details from the Catalog Service REST API
   * for the currently selected {@code categoryId}. On failure, forwards to the error page
   * with a user-friendly message.</p>
   *
   * @return the forward resolution
   */
  public ForwardResolution viewCategory() {
    if (categoryId != null) {
      try {
        Product[] products = getRestTemplate().getForObject(
            CATALOG_SERVICE_URL + "/products?categoryId=" + categoryId, Product[].class);
        productList = products != null ? Arrays.asList(products) : Collections.emptyList();

        category = getRestTemplate().getForObject(
            CATALOG_SERVICE_URL + "/categories/" + categoryId, Category.class);
      } catch (Exception e) {
        LOG.error("Failed to load category {}: {}", categoryId, e.getMessage());
        setMessage("Unable to load category. Please try again.");
        return new ForwardResolution(ERROR);
      }
    }
    return new ForwardResolution(VIEW_CATEGORY);
  }

  /**
   * View product.
   *
   * <p>Retrieves the item list and product details from the Catalog Service REST API
   * for the currently selected {@code productId}. On failure, forwards to the error page
   * with a user-friendly message.</p>
   *
   * @return the forward resolution
   */
  public ForwardResolution viewProduct() {
    if (productId != null) {
      try {
        Item[] items = getRestTemplate().getForObject(
            CATALOG_SERVICE_URL + "/items?productId=" + productId, Item[].class);
        itemList = items != null ? Arrays.asList(items) : Collections.emptyList();

        product = getRestTemplate().getForObject(
            CATALOG_SERVICE_URL + "/products/" + productId, Product.class);
      } catch (Exception e) {
        LOG.error("Failed to load product {}: {}", productId, e.getMessage());
        setMessage("Unable to load product. Please try again.");
        return new ForwardResolution(ERROR);
      }
    }
    return new ForwardResolution(VIEW_PRODUCT);
  }

  /**
   * View item.
   *
   * <p>Retrieves a single item from the Catalog Service REST API by {@code itemId} and
   * extracts the associated product reference from the deserialized Item domain object.
   * On failure, forwards to the error page with a user-friendly message.</p>
   *
   * @return the forward resolution
   */
  public ForwardResolution viewItem() {
    try {
      item = getRestTemplate().getForObject(
          CATALOG_SERVICE_URL + "/items/" + itemId, Item.class);
      product = item.getProduct();
    } catch (Exception e) {
      LOG.error("Failed to load item {}: {}", itemId, e.getMessage());
      setMessage("Unable to load item. Please try again.");
      return new ForwardResolution(ERROR);
    }
    return new ForwardResolution(VIEW_ITEM);
  }

  /**
   * Search products.
   *
   * <p>Validates that a non-empty keyword is provided, then searches for matching products
   * via the Catalog Service REST API. The keyword is lowercased before sending to preserve
   * the original case-insensitive search behavior. On failure, forwards to the error page
   * with a user-friendly message.</p>
   *
   * @return the forward resolution
   */
  public ForwardResolution searchProducts() {
    if (keyword == null || keyword.length() < 1) {
      setMessage("Please enter a keyword to search for, then press the search button.");
      return new ForwardResolution(ERROR);
    } else {
      try {
        Product[] products = getRestTemplate().getForObject(
            CATALOG_SERVICE_URL + "/products/search?keywords=" + keyword.toLowerCase(), Product[].class);
        productList = products != null ? Arrays.asList(products) : Collections.emptyList();
      } catch (Exception e) {
        LOG.error("Failed to search products for keyword '{}': {}", keyword, e.getMessage());
        setMessage("Unable to search products. Please try again.");
        return new ForwardResolution(ERROR);
      }
      return new ForwardResolution(SEARCH_PRODUCTS);
    }
  }

  /**
   * Clear.
   */
  public void clear() {
    keyword = null;

    categoryId = null;
    category = null;
    categoryList = null;

    productId = null;
    product = null;
    productList = null;

    itemId = null;
    item = null;
    itemList = null;
  }

}
