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

import javax.servlet.http.HttpSession;

import net.sourceforge.stripes.action.DefaultHandler;
import net.sourceforge.stripes.action.ForwardResolution;
import net.sourceforge.stripes.action.RedirectResolution;
import net.sourceforge.stripes.action.Resolution;
import net.sourceforge.stripes.action.SessionScope;
import net.sourceforge.stripes.validation.Validate;

import org.mybatis.jpetstore.domain.Account;
import org.mybatis.jpetstore.domain.Product;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

/**
 * The Class AccountActionBean.
 *
 * <p>Session-scoped controller for account creation, sign-on, sign-off, and account editing.
 * Calls Account Service REST API and Catalog Service REST API via {@link RestTemplate},
 * replacing the previous {@code @SpringBean} injections of {@code AccountService} and
 * {@code CatalogService} as part of the monolith-to-microservices Strangler Fig migration.</p>
 *
 * @author Eduardo Macarron
 */
@SessionScope
public class AccountActionBean extends AbstractActionBean {

  private static final long serialVersionUID = 5499663666155758178L;

  private static final String NEW_ACCOUNT = "/WEB-INF/jsp/account/NewAccountForm.jsp";
  private static final String EDIT_ACCOUNT = "/WEB-INF/jsp/account/EditAccountForm.jsp";
  private static final String SIGNON = "/WEB-INF/jsp/account/SignonForm.jsp";

  private static final List<String> LANGUAGE_LIST;
  private static final List<String> CATEGORY_LIST;

  private static final Logger LOG = LoggerFactory.getLogger(AccountActionBean.class);

  private transient RestTemplate restTemplate;

  private static final String ACCOUNT_SERVICE_URL = "http://account-service:8081/api/accounts";
  private static final String CATALOG_SERVICE_URL = "http://catalog-service:8082/api";

  private Account account = new Account();
  private List<Product> myList;
  private boolean authenticated;

  static {
    LANGUAGE_LIST = Collections.unmodifiableList(Arrays.asList("english", "japanese"));
    CATEGORY_LIST = Collections.unmodifiableList(Arrays.asList("FISH", "DOGS", "REPTILES", "CATS", "BIRDS"));
  }

  /**
   * Lazily initializes and returns the {@link RestTemplate} instance used for REST API calls
   * to the Account Service and Catalog Service microservices. The RestTemplate is transient
   * (not serialized with the session-scoped ActionBean) and is recreated if null after
   * deserialization.
   *
   * @return the RestTemplate instance
   */
  private RestTemplate getRestTemplate() {
    if (restTemplate == null) {
      restTemplate = new RestTemplate();
    }
    return restTemplate;
  }

  public Account getAccount() {
    return this.account;
  }

  public String getUsername() {
    return account.getUsername();
  }

  @Validate(required = true, on = { "signon", "newAccount", "editAccount" })
  public void setUsername(String username) {
    account.setUsername(username);
  }

  public String getPassword() {
    return account.getPassword();
  }

  @Validate(required = true, on = { "signon", "newAccount", "editAccount" })
  public void setPassword(String password) {
    account.setPassword(password);
  }

  public List<Product> getMyList() {
    return myList;
  }

  public void setMyList(List<Product> myList) {
    this.myList = myList;
  }

  public List<String> getLanguages() {
    return LANGUAGE_LIST;
  }

  public List<String> getCategories() {
    return CATEGORY_LIST;
  }

  public Resolution newAccountForm() {
    return new ForwardResolution(NEW_ACCOUNT);
  }

  /**
   * New account.
   *
   * <p>Creates a new user account by calling the Account Service REST API. On success,
   * retrieves the full account details and populates the personalized product list from
   * the Catalog Service. On failure, returns to the new account form with an error message.</p>
   *
   * @return the resolution
   */
  public Resolution newAccount() {
    try {
      getRestTemplate().postForObject(ACCOUNT_SERVICE_URL, account, Account.class);
      account = getRestTemplate().getForObject(
          ACCOUNT_SERVICE_URL + "/" + account.getUsername(), Account.class);
    } catch (Exception e) {
      LOG.error("Failed to create account: {}", e.getMessage());
      setMessage("An error occurred creating your account. Please try again.");
      return new ForwardResolution(NEW_ACCOUNT);
    }
    myList = fetchMyList(account.getFavouriteCategoryId());
    authenticated = true;
    return new RedirectResolution(CatalogActionBean.class);
  }

  /**
   * Edits the account form.
   *
   * @return the resolution
   */
  public Resolution editAccountForm() {
    return new ForwardResolution(EDIT_ACCOUNT);
  }

  /**
   * Edits the account.
   *
   * <p>Updates the user account by calling the Account Service REST API. On success,
   * retrieves the refreshed account details and repopulates the personalized product
   * list from the Catalog Service. On failure, returns to the edit account form with
   * an error message.</p>
   *
   * @return the resolution
   */
  public Resolution editAccount() {
    try {
      getRestTemplate().put(ACCOUNT_SERVICE_URL + "/" + account.getUsername(), account);
      account = getRestTemplate().getForObject(
          ACCOUNT_SERVICE_URL + "/" + account.getUsername(), Account.class);
    } catch (Exception e) {
      LOG.error("Failed to update account: {}", e.getMessage());
      setMessage("An error occurred updating your account. Please try again.");
      return new ForwardResolution(EDIT_ACCOUNT);
    }
    myList = fetchMyList(account.getFavouriteCategoryId());
    return new RedirectResolution(CatalogActionBean.class);
  }

  /**
   * Signon form.
   *
   * @return the resolution
   */
  @DefaultHandler
  public Resolution signonForm() {
    return new ForwardResolution(SIGNON);
  }

  /**
   * Signon.
   *
   * <p>Authenticates the user by posting credentials to the Account Service REST API signon
   * endpoint. On success, clears the password from the session-held account object, populates
   * the personalized product list from the Catalog Service, and stores this bean in the HTTP
   * session for backward compatibility with other ActionBeans during the Strangler Fig
   * transition. On failure, displays an error message and returns to the signon form.</p>
   *
   * @return the resolution
   */
  public Resolution signon() {
    try {
      java.util.Map<String, String> signonRequest = new java.util.HashMap<>();
      signonRequest.put("username", getUsername());
      signonRequest.put("password", getPassword());

      ResponseEntity<Account> response = getRestTemplate().postForEntity(
          ACCOUNT_SERVICE_URL + "/signon", signonRequest, Account.class);
      account = response.getBody();
    } catch (Exception e) {
      LOG.warn("Signon failed for user {}: {}", getUsername(), e.getMessage());
      account = null;
    }

    if (account == null) {
      String value = "Invalid username or password.  Signon failed.";
      setMessage(value);
      clear();
      return new ForwardResolution(SIGNON);
    } else {
      account.setPassword(null);
      myList = fetchMyList(account.getFavouriteCategoryId());
      authenticated = true;
      HttpSession s = context.getRequest().getSession();
      // Store this bean in session for backward compatibility with OrderActionBean during transition
      s.setAttribute("accountBean", this);
      return new RedirectResolution(CatalogActionBean.class);
    }
  }

  /**
   * Signoff.
   *
   * @return the resolution
   */
  public Resolution signoff() {
    context.getRequest().getSession().invalidate();
    clear();
    return new RedirectResolution(CatalogActionBean.class);
  }

  /**
   * Checks if is authenticated.
   *
   * @return true, if is authenticated
   */
  public boolean isAuthenticated() {
    return authenticated && account != null && account.getUsername() != null;
  }

  /**
   * Clear.
   */
  public void clear() {
    account = new Account();
    myList = null;
    authenticated = false;
  }

  /**
   * Fetches the personalized product list from the Catalog Service REST API.
   * Falls back to an empty list if the service is unavailable (graceful degradation).
   *
   * <p>This method is called from {@link #newAccount()}, {@link #editAccount()},
   * and {@link #signon()} to populate the {@code myList} field that is rendered
   * by the {@code IncludeMyList.jsp} view template.</p>
   *
   * @param favouriteCategoryId the user's preferred category identifier
   * @return the list of products for personalization, or an empty list on failure
   */
  private List<Product> fetchMyList(String favouriteCategoryId) {
    if (favouriteCategoryId == null || favouriteCategoryId.isEmpty()) {
      return Collections.emptyList();
    }
    try {
      Product[] products = getRestTemplate().getForObject(
          CATALOG_SERVICE_URL + "/products?categoryId=" + favouriteCategoryId, Product[].class);
      return products != null ? Arrays.asList(products) : Collections.emptyList();
    } catch (Exception e) {
      LOG.warn("Catalog Service unavailable for myList, falling back to empty list: {}", e.getMessage());
      return Collections.emptyList();
    }
  }

}
