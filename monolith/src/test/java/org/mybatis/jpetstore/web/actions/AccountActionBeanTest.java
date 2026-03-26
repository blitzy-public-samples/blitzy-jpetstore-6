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
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Map;

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
import org.mybatis.jpetstore.domain.Product;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

class AccountActionBeanTest {

  // Test written by Diffblue Cover.
  @Test
  void getMyListOutputNull() {

    // Arrange
    final AccountActionBean accountActionBean = new AccountActionBean();

    // Act and Assert result
    assertThat(accountActionBean.getMyList()).isNull();

  }

  // Test written by Diffblue Cover.
  @Test
  void constructorOutputNotNull() {

    // Act, creating object to test constructor
    final AccountActionBean actual = new AccountActionBean();

    // Assert result
    assertThat(actual).isNotNull();
    assertThat(actual.getContext()).isNull();

  }

  // Test written by Diffblue Cover.
  @Test
  void getPasswordOutputNull() {

    // Arrange
    final AccountActionBean accountActionBean = new AccountActionBean();

    // Act and Assert result
    assertThat(accountActionBean.getPassword()).isNull();

  }

  // Test written by Diffblue Cover.
  @Test
  void isAuthenticatedOutputFalse() {

    // Arrange
    final AccountActionBean accountActionBean = new AccountActionBean();

    // Act and Assert result
    assertThat(accountActionBean.isAuthenticated()).isFalse();

  }

  // Test written by Diffblue Cover.
  @Test
  void getUsernameOutputNull() {

    // Arrange
    final AccountActionBean accountActionBean = new AccountActionBean();

    // Act and Assert result
    assertThat(accountActionBean.getUsername()).isNull();

  }

  // Test written by Diffblue Cover.
  @Test
  void getAccountOutputNotNull() {

    // Arrange
    final AccountActionBean accountActionBean = new AccountActionBean();

    // Act
    final Account actual = accountActionBean.getAccount();

    // Assert result
    assertThat(actual).isNotNull();
    assertThat(actual.getAddress2()).isNull();
    assertThat(actual.getState()).isNull();
    assertThat(actual.getFirstName()).isNull();
    assertThat(actual.getPassword()).isNull();
    assertThat(actual.getLanguagePreference()).isNull();
    assertThat(actual.getFavouriteCategoryId()).isNull();
    assertThat(actual.getCountry()).isNull();
    assertThat(actual.getPhone()).isNull();
    assertThat(actual.getUsername()).isNull();
    assertThat(actual.getLastName()).isNull();
    assertThat(actual.getAddress1()).isNull();
    assertThat(actual.getEmail()).isNull();
    assertThat(actual.getStatus()).isNull();
    assertThat(actual.getBannerName()).isNull();
    assertThat(actual.getZip()).isNull();
    assertThat(actual.getCity()).isNull();

  }

  /**
   * Nested test class for verifying REST-client-based handler methods in the
   * modified AccountActionBean. Tests use Mockito to mock the RestTemplate
   * injected via reflection, simulating calls to Account Service and Catalog
   * Service microservices during the Strangler Fig migration.
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

    private AccountActionBean accountActionBean;

    /** Static mock for StripesFilter — required because RedirectResolution(Class)
     *  internally calls StripesFilter.getConfiguration().getActionResolver().getUrlBinding(). */
    private MockedStatic<StripesFilter> mockedStripesFilter;

    private static final String ACCOUNT_SERVICE_URL = "http://account-service:8081/api/accounts";
    private static final String CATALOG_SERVICE_URL = "http://catalog-service:8082/api";

    @BeforeEach
    void setUp() throws Exception {
      // Mock StripesFilter.getConfiguration() so that RedirectResolution(Class) can
      // resolve the URL binding for CatalogActionBean without a running Stripes container.
      Configuration mockConfig = mock(Configuration.class);
      ActionResolver mockActionResolver = mock(ActionResolver.class);
      lenient().when(mockConfig.getActionResolver()).thenReturn(mockActionResolver);
      lenient().when(mockActionResolver.getUrlBinding(any())).thenReturn("/actions/Catalog.action");
      mockedStripesFilter = mockStatic(StripesFilter.class);
      mockedStripesFilter.when(StripesFilter::getConfiguration).thenReturn(mockConfig);

      accountActionBean = new AccountActionBean();
      // Inject mocked RestTemplate via reflection into private restTemplate field
      Field restTemplateField = AccountActionBean.class.getDeclaredField("restTemplate");
      restTemplateField.setAccessible(true);
      restTemplateField.set(accountActionBean, restTemplate);

      // Set up context mock chain for tests that need it.
      // Use lenient() because not every test exercises every stub path
      // (e.g., signon success uses getRequest/getSession but not getMessages,
      // while signon failure uses getMessages but not getRequest/getSession).
      lenient().when(mockContext.getMessages()).thenReturn(new ArrayList<Message>());
      lenient().when(mockContext.getRequest()).thenReturn(mockRequest);
      lenient().when(mockRequest.getSession()).thenReturn(mockSession);
      accountActionBean.setContext(mockContext);
    }

    @AfterEach
    void tearDown() {
      // MockedStatic must be closed after each test to prevent leaking static mock state
      if (mockedStripesFilter != null) {
        mockedStripesFilter.close();
      }
    }

    // --- signon() tests ---

    @Test
    void signon_WithValidCredentials_ShouldSetAuthenticatedAndFetchMyList() {
      accountActionBean.setUsername("j2ee");
      accountActionBean.setPassword("j2ee");

      Account returnedAccount = new Account();
      returnedAccount.setUsername("j2ee");
      returnedAccount.setFavouriteCategoryId("DOGS");

      // Mock the signon POST — Account Service returns account data
      when(restTemplate.postForEntity(
          eq(ACCOUNT_SERVICE_URL + "/signon"), any(Map.class), eq(Account.class)))
          .thenReturn(new ResponseEntity<>(returnedAccount, HttpStatus.OK));

      // Mock the personalization call to Catalog Service
      Product[] products = new Product[] { new Product() };
      when(restTemplate.getForObject(
          eq(CATALOG_SERVICE_URL + "/products?categoryId=DOGS"), eq(Product[].class)))
          .thenReturn(products);

      Resolution resolution = accountActionBean.signon();

      assertThat(resolution).isNotNull();
      assertThat(accountActionBean.isAuthenticated()).isTrue();
      assertThat(accountActionBean.getAccount().getUsername()).isEqualTo("j2ee");
      assertThat(accountActionBean.getAccount().getPassword()).isNull(); // password cleared
      assertThat(accountActionBean.getMyList()).isNotNull().hasSize(1);
      verify(mockSession).setAttribute(eq("accountBean"), eq(accountActionBean));
    }

    @Test
    void signon_WithInvalidCredentials_ShouldNotAuthenticate() {
      accountActionBean.setUsername("wrong");
      accountActionBean.setPassword("wrong");

      // Mock the signon POST to throw — simulates 401 from Account Service
      when(restTemplate.postForEntity(anyString(), any(), eq(Account.class)))
          .thenThrow(new RestClientException("401 Unauthorized"));

      Resolution resolution = accountActionBean.signon();

      assertThat(resolution).isNotNull();
      assertThat(accountActionBean.isAuthenticated()).isFalse();
    }

    @Test
    void signon_WithNullResponse_ShouldShowSignonFailedMessage() {
      accountActionBean.setUsername("j2ee");
      accountActionBean.setPassword("j2ee");

      when(restTemplate.postForEntity(anyString(), any(), eq(Account.class)))
          .thenReturn(new ResponseEntity<>(null, HttpStatus.OK));

      Resolution resolution = accountActionBean.signon();

      assertThat(resolution).isNotNull();
      assertThat(accountActionBean.isAuthenticated()).isFalse();
    }

    // --- newAccount() tests ---

    @Test
    void newAccount_ShouldCallAccountServiceAndSetAuthenticated() {
      Account account = accountActionBean.getAccount();
      account.setUsername("testuser");
      account.setFavouriteCategoryId("FISH");

      Account returnedAccount = new Account();
      returnedAccount.setUsername("testuser");
      returnedAccount.setFavouriteCategoryId("FISH");

      // Mock POST (create) and GET (retrieve)
      when(restTemplate.postForObject(eq(ACCOUNT_SERVICE_URL), any(Account.class), eq(Account.class)))
          .thenReturn(returnedAccount);
      when(restTemplate.getForObject(
          eq(ACCOUNT_SERVICE_URL + "/testuser"), eq(Account.class)))
          .thenReturn(returnedAccount);

      // Mock personalization
      Product[] products = new Product[] { new Product() };
      when(restTemplate.getForObject(
          eq(CATALOG_SERVICE_URL + "/products?categoryId=FISH"), eq(Product[].class)))
          .thenReturn(products);

      Resolution resolution = accountActionBean.newAccount();

      assertThat(resolution).isNotNull();
      assertThat(accountActionBean.isAuthenticated()).isTrue();
      assertThat(accountActionBean.getMyList()).hasSize(1);
    }

    @Test
    void newAccount_WhenAccountServiceFails_ShouldReturnNewAccountForm() {
      Account account = accountActionBean.getAccount();
      account.setUsername("testuser");

      when(restTemplate.postForObject(anyString(), any(Account.class), eq(Account.class)))
          .thenThrow(new RestClientException("Service unavailable"));

      Resolution resolution = accountActionBean.newAccount();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("NewAccountForm.jsp");
    }

    // --- editAccount() tests ---

    @Test
    void editAccount_ShouldCallAccountServiceAndRefreshMyList() {
      Account account = accountActionBean.getAccount();
      account.setUsername("j2ee");
      account.setFavouriteCategoryId("CATS");

      Account updatedAccount = new Account();
      updatedAccount.setUsername("j2ee");
      updatedAccount.setFavouriteCategoryId("CATS");

      // Mock PUT (no return) and GET
      doNothing().when(restTemplate).put(eq(ACCOUNT_SERVICE_URL + "/j2ee"), any(Account.class));
      when(restTemplate.getForObject(eq(ACCOUNT_SERVICE_URL + "/j2ee"), eq(Account.class)))
          .thenReturn(updatedAccount);

      Product[] products = new Product[] { new Product() };
      when(restTemplate.getForObject(
          eq(CATALOG_SERVICE_URL + "/products?categoryId=CATS"), eq(Product[].class)))
          .thenReturn(products);

      Resolution resolution = accountActionBean.editAccount();

      assertThat(resolution).isNotNull();
      assertThat(accountActionBean.getMyList()).hasSize(1);
      verify(restTemplate).put(eq(ACCOUNT_SERVICE_URL + "/j2ee"), any(Account.class));
    }

    @Test
    void editAccount_WhenAccountServiceFails_ShouldReturnEditForm() {
      Account account = accountActionBean.getAccount();
      account.setUsername("j2ee");

      doThrow(new RestClientException("Service unavailable"))
          .when(restTemplate).put(anyString(), any(Account.class));

      Resolution resolution = accountActionBean.editAccount();

      assertThat(resolution).isNotNull();
      assertThat(resolution.toString()).contains("EditAccountForm.jsp");
    }

    // --- Personalization fallback tests ---

    @Test
    void fetchMyList_WhenCatalogServiceUnavailable_ShouldReturnEmptyList() {
      accountActionBean.setUsername("j2ee");
      accountActionBean.setPassword("j2ee");

      Account returnedAccount = new Account();
      returnedAccount.setUsername("j2ee");
      returnedAccount.setFavouriteCategoryId("DOGS");

      when(restTemplate.postForEntity(anyString(), any(), eq(Account.class)))
          .thenReturn(new ResponseEntity<>(returnedAccount, HttpStatus.OK));

      // Catalog Service unavailable for personalization
      when(restTemplate.getForObject(contains("/products?categoryId="), eq(Product[].class)))
          .thenThrow(new RestClientException("503 Service Unavailable"));

      Resolution resolution = accountActionBean.signon();

      assertThat(resolution).isNotNull();
      assertThat(accountActionBean.isAuthenticated()).isTrue();
      // myList should fall back to empty list, NOT null, NOT exception
      assertThat(accountActionBean.getMyList()).isNotNull().isEmpty();
    }

    @Test
    void fetchMyList_WithNullFavouriteCategoryId_ShouldReturnEmptyList() {
      accountActionBean.setUsername("j2ee");
      accountActionBean.setPassword("j2ee");

      Account returnedAccount = new Account();
      returnedAccount.setUsername("j2ee");
      returnedAccount.setFavouriteCategoryId(null);

      when(restTemplate.postForEntity(anyString(), any(), eq(Account.class)))
          .thenReturn(new ResponseEntity<>(returnedAccount, HttpStatus.OK));

      Resolution resolution = accountActionBean.signon();

      assertThat(resolution).isNotNull();
      assertThat(accountActionBean.getMyList()).isNotNull().isEmpty();
    }

    // --- signoff() test ---

    @Test
    void signoff_ShouldInvalidateSessionAndClearState() {
      // Pre-set authenticated state
      accountActionBean.getAccount().setUsername("j2ee");

      Resolution resolution = accountActionBean.signoff();

      assertThat(resolution).isNotNull();
      assertThat(accountActionBean.isAuthenticated()).isFalse();
      assertThat(accountActionBean.getMyList()).isNull();
      verify(mockSession).invalidate();
    }
  }
}
