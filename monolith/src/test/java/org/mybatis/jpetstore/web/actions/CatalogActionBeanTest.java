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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;

import net.sourceforge.stripes.action.ActionBeanContext;
import net.sourceforge.stripes.action.ForwardResolution;
import net.sourceforge.stripes.action.Message;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mybatis.jpetstore.domain.Category;
import org.mybatis.jpetstore.domain.Item;
import org.mybatis.jpetstore.domain.Product;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

class CatalogActionBeanTest {

  @Test
  void getItemListOutputNull() {

    // Arrange
    final CatalogActionBean catalogActionBean = new CatalogActionBean();

    // Act and Assert result
    assertThat(catalogActionBean.getItemList()).isNull();

  }

  // Test written by Diffblue Cover.
  @Test
  void getProductListOutputNull() {

    // Arrange
    final CatalogActionBean catalogActionBean = new CatalogActionBean();

    // Act and Assert result
    assertThat(catalogActionBean.getProductList()).isNull();

  }

  // Test written by Diffblue Cover.
  @Test
  void getCategoryListOutputNull() {

    // Arrange
    final CatalogActionBean catalogActionBean = new CatalogActionBean();

    // Act and Assert result
    assertThat(catalogActionBean.getCategoryList()).isNull();

  }

  // Test written by Diffblue Cover.
  @Test
  void getItemOutputNull() {

    // Arrange
    final CatalogActionBean catalogActionBean = new CatalogActionBean();

    // Act and Assert result
    assertThat(catalogActionBean.getItem()).isNull();

  }

  // Test written by Diffblue Cover.
  @Test
  void getProductOutputNull() {

    // Arrange
    final CatalogActionBean catalogActionBean = new CatalogActionBean();

    // Act and Assert result
    assertThat(catalogActionBean.getProduct()).isNull();

  }

  // Test written by Diffblue Cover.
  @Test
  void getCategoryOutputNull() {

    // Arrange
    final CatalogActionBean catalogActionBean = new CatalogActionBean();

    // Act and Assert result
    assertThat(catalogActionBean.getCategory()).isNull();

  }

  // Test written by Diffblue Cover.
  @Test
  void getItemIdOutputNull() {

    // Arrange
    final CatalogActionBean catalogActionBean = new CatalogActionBean();

    // Act and Assert result
    assertThat(catalogActionBean.getItemId()).isNull();

  }

  // Test written by Diffblue Cover.
  @Test
  void getProductIdOutputNull() {

    // Arrange
    final CatalogActionBean catalogActionBean = new CatalogActionBean();

    // Act and Assert result
    assertThat(catalogActionBean.getProductId()).isNull();

  }

  // Test written by Diffblue Cover.
  @Test
  void getCategoryIdOutputNull() {

    // Arrange
    final CatalogActionBean catalogActionBean = new CatalogActionBean();

    // Act and Assert result
    assertThat(catalogActionBean.getCategoryId()).isNull();

  }

  // Test written by Diffblue Cover.
  @Test
  void getKeywordOutputNull() {

    // Arrange
    final CatalogActionBean catalogActionBean = new CatalogActionBean();

    // Act and Assert result
    assertThat(catalogActionBean.getKeyword()).isNull();

  }

  // Test written by Diffblue Cover.
  @Test
  void constructorOutputNotNull() {

    // Act, creating object to test constructor
    final CatalogActionBean actual = new CatalogActionBean();

    // Assert result
    assertThat(actual).isNotNull();
    assertThat(actual.getContext()).isNull();

  }

  /**
   * Nested test class for REST-client-based handler method tests. Uses Mockito to mock
   * the {@link RestTemplate} that is injected via reflection into CatalogActionBean's
   * private {@code restTemplate} field, validating all catalog browsing operations
   * (viewCategory, viewProduct, viewItem, searchProducts) against the Catalog Service
   * REST API at {@code http://catalog-service:8082/api}.
   */
  @Nested
  @ExtendWith(MockitoExtension.class)
  class RestClientTests {

    @Mock
    private RestTemplate restTemplate;

    private CatalogActionBean catalogActionBean;

    private static final String CATALOG_SERVICE_URL = "http://catalog-service:8082/api";

    @BeforeEach
    void setUp() throws Exception {
      catalogActionBean = new CatalogActionBean();
      // Use reflection to inject the mocked RestTemplate into the private field
      Field restTemplateField = CatalogActionBean.class.getDeclaredField("restTemplate");
      restTemplateField.setAccessible(true);
      restTemplateField.set(catalogActionBean, restTemplate);
    }

    // --- viewCategory tests ---

    @Test
    void viewCategory_WithValidCategoryId_ShouldPopulateProductListAndCategory() {
      catalogActionBean.setCategoryId("FISH");

      Product[] products = new Product[] { new Product() };
      Category category = new Category();

      when(restTemplate.getForObject(
          eq(CATALOG_SERVICE_URL + "/products?categoryId=FISH"), eq(Product[].class)))
          .thenReturn(products);
      when(restTemplate.getForObject(
          eq(CATALOG_SERVICE_URL + "/categories/FISH"), eq(Category.class)))
          .thenReturn(category);

      ForwardResolution resolution = (ForwardResolution) catalogActionBean.viewCategory();

      assertThat(resolution).isNotNull();
      assertThat(catalogActionBean.getProductList()).hasSize(1);
      assertThat(catalogActionBean.getCategory()).isEqualTo(category);
      verify(restTemplate).getForObject(eq(CATALOG_SERVICE_URL + "/products?categoryId=FISH"), eq(Product[].class));
      verify(restTemplate).getForObject(eq(CATALOG_SERVICE_URL + "/categories/FISH"), eq(Category.class));
    }

    @Test
    void viewCategory_WithNullCategoryId_ShouldNotCallRestTemplate() {
      catalogActionBean.setCategoryId(null);
      ForwardResolution resolution = (ForwardResolution) catalogActionBean.viewCategory();
      assertThat(resolution).isNotNull();
      verifyNoInteractions(restTemplate);
    }

    @Test
    void viewCategory_WhenRestCallFails_ShouldReturnError() {
      catalogActionBean.setCategoryId("FISH");
      ActionBeanContext mockContext = mock(ActionBeanContext.class);
      when(mockContext.getMessages()).thenReturn(new ArrayList<Message>());
      catalogActionBean.setContext(mockContext);

      when(restTemplate.getForObject(anyString(), any(Class.class)))
          .thenThrow(new RestClientException("Service unavailable"));

      Object resolution = catalogActionBean.viewCategory();
      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("Error.jsp");
    }

    // --- viewProduct tests ---

    @Test
    void viewProduct_WithValidProductId_ShouldPopulateItemListAndProduct() {
      catalogActionBean.setProductId("FI-SW-01");

      Item[] items = new Item[] { new Item() };
      Product product = new Product();

      when(restTemplate.getForObject(
          eq(CATALOG_SERVICE_URL + "/items?productId=FI-SW-01"), eq(Item[].class)))
          .thenReturn(items);
      when(restTemplate.getForObject(
          eq(CATALOG_SERVICE_URL + "/products/FI-SW-01"), eq(Product.class)))
          .thenReturn(product);

      ForwardResolution resolution = (ForwardResolution) catalogActionBean.viewProduct();
      assertThat(resolution).isNotNull();
      assertThat(catalogActionBean.getItemList()).hasSize(1);
      assertThat(catalogActionBean.getProduct()).isEqualTo(product);
    }

    @Test
    void viewProduct_WithNullProductId_ShouldNotCallRestTemplate() {
      catalogActionBean.setProductId(null);
      ForwardResolution resolution = (ForwardResolution) catalogActionBean.viewProduct();
      assertThat(resolution).isNotNull();
      verifyNoInteractions(restTemplate);
    }

    @Test
    void viewProduct_WhenRestCallFails_ShouldReturnError() {
      catalogActionBean.setProductId("FI-SW-01");
      ActionBeanContext mockContext = mock(ActionBeanContext.class);
      when(mockContext.getMessages()).thenReturn(new ArrayList<Message>());
      catalogActionBean.setContext(mockContext);

      when(restTemplate.getForObject(anyString(), any(Class.class)))
          .thenThrow(new RestClientException("Service unavailable"));

      Object resolution = catalogActionBean.viewProduct();
      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("Error.jsp");
    }

    // --- viewItem tests ---

    @Test
    void viewItem_WithValidItemId_ShouldPopulateItemAndProduct() {
      catalogActionBean.setItemId("EST-1");

      Item item = new Item();
      Product product = new Product();
      item.setProduct(product);

      when(restTemplate.getForObject(
          eq(CATALOG_SERVICE_URL + "/items/EST-1"), eq(Item.class)))
          .thenReturn(item);

      ForwardResolution resolution = (ForwardResolution) catalogActionBean.viewItem();
      assertThat(resolution).isNotNull();
      assertThat(catalogActionBean.getItem()).isEqualTo(item);
      assertThat(catalogActionBean.getProduct()).isEqualTo(product);
    }

    @Test
    void viewItem_WhenRestCallFails_ShouldReturnError() {
      catalogActionBean.setItemId("EST-1");
      ActionBeanContext mockContext = mock(ActionBeanContext.class);
      when(mockContext.getMessages()).thenReturn(new ArrayList<Message>());
      catalogActionBean.setContext(mockContext);

      when(restTemplate.getForObject(anyString(), any(Class.class)))
          .thenThrow(new RestClientException("Service unavailable"));

      Object resolution = catalogActionBean.viewItem();
      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("Error.jsp");
    }

    // --- searchProducts tests ---

    @Test
    void searchProducts_WithValidKeyword_ShouldPopulateProductList() {
      catalogActionBean.setKeyword("fish");

      Product[] products = new Product[] { new Product(), new Product() };
      when(restTemplate.getForObject(
          eq(CATALOG_SERVICE_URL + "/products/search?keywords=fish"), eq(Product[].class)))
          .thenReturn(products);

      ForwardResolution resolution = (ForwardResolution) catalogActionBean.searchProducts();
      assertThat(resolution).isNotNull();
      assertThat(catalogActionBean.getProductList()).hasSize(2);
    }

    @Test
    void searchProducts_WithNullKeyword_ShouldReturnError() {
      catalogActionBean.setKeyword(null);
      ActionBeanContext mockContext = mock(ActionBeanContext.class);
      when(mockContext.getMessages()).thenReturn(new ArrayList<Message>());
      catalogActionBean.setContext(mockContext);

      ForwardResolution resolution = (ForwardResolution) catalogActionBean.searchProducts();
      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("Error.jsp");
      verifyNoInteractions(restTemplate);
    }

    @Test
    void searchProducts_WithEmptyKeyword_ShouldReturnError() {
      catalogActionBean.setKeyword("");
      ActionBeanContext mockContext = mock(ActionBeanContext.class);
      when(mockContext.getMessages()).thenReturn(new ArrayList<Message>());
      catalogActionBean.setContext(mockContext);

      ForwardResolution resolution = (ForwardResolution) catalogActionBean.searchProducts();
      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("Error.jsp");
      verifyNoInteractions(restTemplate);
    }

    @Test
    void searchProducts_WhenRestCallFails_ShouldReturnError() {
      catalogActionBean.setKeyword("fish");
      ActionBeanContext mockContext = mock(ActionBeanContext.class);
      when(mockContext.getMessages()).thenReturn(new ArrayList<Message>());
      catalogActionBean.setContext(mockContext);

      when(restTemplate.getForObject(anyString(), any(Class.class)))
          .thenThrow(new RestClientException("Service unavailable"));

      Object resolution = catalogActionBean.searchProducts();
      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("Error.jsp");
    }

    @Test
    void searchProducts_WithUpperCaseKeyword_ShouldLowercaseBeforeSending() {
      catalogActionBean.setKeyword("FISH");

      Product[] products = new Product[] { new Product() };
      when(restTemplate.getForObject(
          eq(CATALOG_SERVICE_URL + "/products/search?keywords=fish"), eq(Product[].class)))
          .thenReturn(products);

      catalogActionBean.searchProducts();
      verify(restTemplate).getForObject(
          eq(CATALOG_SERVICE_URL + "/products/search?keywords=fish"), eq(Product[].class));
    }

  }

}
