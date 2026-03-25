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
package com.jpetstore.account.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.jpetstore.account.dto.AccountDTO;
import com.jpetstore.account.entity.Account;
import com.jpetstore.account.entity.BannerData;
import com.jpetstore.account.entity.Profile;
import com.jpetstore.account.entity.Signon;
import com.jpetstore.account.repository.AccountRepository;
import com.jpetstore.account.repository.BannerDataRepository;
import com.jpetstore.account.repository.ProfileRepository;
import com.jpetstore.account.repository.SignonRepository;

/**
 * Pure Mockito unit tests for {@link AccountService} business logic.
 *
 * <p>Mirrors the monolith's {@code org.mybatis.jpetstore.service.AccountServiceTest}
 * pattern: {@code @ExtendWith(MockitoExtension.class)} with {@code @Mock} repository
 * fields and {@code @InjectMocks} for the service under test. No Spring context is
 * loaded — all four JPA repositories are mocked.</p>
 *
 * <p>The monolith test mocked a single {@code AccountMapper}; this test replaces it
 * with four separate repository mocks ({@code AccountRepository},
 * {@code ProfileRepository}, {@code SignonRepository}, {@code BannerDataRepository})
 * matching the decomposed persistence layer.</p>
 *
 * <h3>Test Coverage Summary (8 tests)</h3>
 * <table>
 *   <caption>Test methods and their coverage targets</caption>
 *   <tr><th>#</th><th>Test Method</th><th>Service Method</th><th>Monolith Equivalent</th></tr>
 *   <tr><td>1</td><td>shouldCallRepositoriesToGetAccountByUsername</td>
 *       <td>getAccount(String)</td><td>shouldCallTheMapperToGetAccountAnUsername</td></tr>
 *   <tr><td>2</td><td>shouldReturnEmptyWhenAccountNotFound</td>
 *       <td>getAccount(String)</td><td>(new coverage)</td></tr>
 *   <tr><td>3</td><td>shouldCallSignonRepositoryToAuthenticateAndLoadAccount</td>
 *       <td>getAccountForAuth(String, String)</td>
 *       <td>shouldCallTheMapperToGetAccountAnUsernameAndPassword</td></tr>
 *   <tr><td>4</td><td>shouldReturnEmptyWhenAuthenticationFails</td>
 *       <td>getAccountForAuth(String, String)</td><td>(new coverage)</td></tr>
 *   <tr><td>5</td><td>shouldCallRepositoriesToInsertAnAccount</td>
 *       <td>insertAccount(AccountDTO)</td><td>shouldCallTheMapperToInsertAnAccount</td></tr>
 *   <tr><td>6</td><td>shouldCallRepositoriesToUpdateAnAccountWithPassword</td>
 *       <td>updateAccount(String, AccountDTO)</td>
 *       <td>shouldCallTheMapperToUpdateAnAccount</td></tr>
 *   <tr><td>7</td><td>shouldNotUpdateSignonWhenPasswordIsEmpty</td>
 *       <td>updateAccount(String, AccountDTO)</td><td>(new — conditional logic)</td></tr>
 *   <tr><td>8</td><td>shouldNotUpdateSignonWhenPasswordIsNull</td>
 *       <td>updateAccount(String, AccountDTO)</td><td>(new — conditional logic)</td></tr>
 * </table>
 *
 * @see AccountService
 */
@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private ProfileRepository profileRepository;

    @Mock
    private SignonRepository signonRepository;

    @Mock
    private BannerDataRepository bannerDataRepository;

    @InjectMocks
    private AccountService accountService;

    // -----------------------------------------------------------------------
    // Test 1: getAccount(String) — happy path with 3-repository call + DTO assembly
    // Monolith equivalent: shouldCallTheMapperToGetAccountAnUsername (line 73)
    // -----------------------------------------------------------------------

    @Test
    void shouldCallRepositoriesToGetAccountByUsername() {
        // given
        String username = "bar";

        Account account = new Account();
        account.setUserid(username);
        account.setEmail("bar@example.com");
        account.setFirstname("John");
        account.setLastname("Doe");
        account.setStatus("OK");
        account.setAddress1("123 Main St");
        account.setAddress2("Apt 1");
        account.setCity("Springfield");
        account.setState("IL");
        account.setZip("62704");
        account.setCountry("US");
        account.setPhone("555-1234");

        Profile profile = new Profile();
        profile.setUserid(username);
        profile.setLangpref("english");
        profile.setFavcategory("FISH");
        profile.setMylistopt(true);
        profile.setBanneropt(true);

        BannerData bannerData = new BannerData();
        bannerData.setFavcategory("FISH");
        bannerData.setBannername("<image src=\"../images/banner_fish.gif\">");

        when(accountRepository.findById(username)).thenReturn(Optional.of(account));
        when(profileRepository.findById(username)).thenReturn(Optional.of(profile));
        when(bannerDataRepository.findById("FISH")).thenReturn(Optional.of(bannerData));

        // when
        Optional<AccountDTO> result = accountService.getAccount(username);

        // then
        assertThat(result).isPresent();
        AccountDTO dto = result.get();
        assertThat(dto.getUsername()).isEqualTo(username);
        assertThat(dto.getEmail()).isEqualTo("bar@example.com");
        assertThat(dto.getFirstName()).isEqualTo("John");
        assertThat(dto.getLastName()).isEqualTo("Doe");
        assertThat(dto.getLanguagePreference()).isEqualTo("english");
        assertThat(dto.getFavouriteCategoryId()).isEqualTo("FISH");
        assertThat(dto.isListOption()).isTrue();
        assertThat(dto.isBannerOption()).isTrue();
        assertThat(dto.getBannerName()).isEqualTo("<image src=\"../images/banner_fish.gif\">");

        verify(accountRepository).findById(username);
        verify(profileRepository).findById(username);
        verify(bannerDataRepository).findById("FISH");
    }

    // -----------------------------------------------------------------------
    // Test 2: getAccount(String) — username not found returns Optional.empty()
    // Mirrors monolith behavior: mapper returns null from empty resultSet
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnEmptyWhenAccountNotFound() {
        // given
        String username = "nonexistent";
        when(accountRepository.findById(username)).thenReturn(Optional.empty());

        // when
        Optional<AccountDTO> result = accountService.getAccount(username);

        // then
        assertThat(result).isEmpty();
        verify(accountRepository).findById(username);
        verify(profileRepository, never()).findById(any());
        verify(bannerDataRepository, never()).findById(any());
    }

    // -----------------------------------------------------------------------
    // Test 3: getAccountForAuth(String, String) — credential check + account load
    // Monolith equivalent: shouldCallTheMapperToGetAccountAnUsernameAndPassword (line 87)
    // -----------------------------------------------------------------------

    @Test
    void shouldCallSignonRepositoryToAuthenticateAndLoadAccount() {
        // given
        String username = "bar";
        String password = "foo";

        Signon signon = new Signon();
        signon.setUsername(username);
        signon.setPassword(password);

        Account account = new Account();
        account.setUserid(username);
        account.setEmail("bar@example.com");
        account.setFirstname("John");
        account.setLastname("Doe");

        Profile profile = new Profile();
        profile.setUserid(username);
        profile.setLangpref("english");
        profile.setFavcategory("DOGS");

        when(signonRepository.findByUsernameAndPassword(username, password))
                .thenReturn(Optional.of(signon));
        when(accountRepository.findById(username)).thenReturn(Optional.of(account));
        when(profileRepository.findById(username)).thenReturn(Optional.of(profile));
        when(bannerDataRepository.findById("DOGS")).thenReturn(Optional.empty());

        // when
        Optional<AccountDTO> result = accountService.getAccountForAuth(username, password);

        // then
        assertThat(result).isPresent();
        assertThat(result.get().getUsername()).isEqualTo(username);
        verify(signonRepository).findByUsernameAndPassword(username, password);
        verify(accountRepository).findById(username);
    }

    // -----------------------------------------------------------------------
    // Test 4: getAccountForAuth(String, String) — auth failure returns empty
    // -----------------------------------------------------------------------

    @Test
    void shouldReturnEmptyWhenAuthenticationFails() {
        // given
        String username = "bar";
        String password = "wrong";
        when(signonRepository.findByUsernameAndPassword(username, password))
                .thenReturn(Optional.empty());

        // when
        Optional<AccountDTO> result = accountService.getAccountForAuth(username, password);

        // then
        assertThat(result).isEmpty();
        verify(signonRepository).findByUsernameAndPassword(username, password);
        verify(accountRepository, never()).findById(any());
    }

    // -----------------------------------------------------------------------
    // Test 5: insertAccount(AccountDTO) — atomic 3-table insert verification
    // Monolith equivalent: shouldCallTheMapperToInsertAnAccount (line 44)
    // Verifies: Account → Profile → Signon save() calls with ArgumentCaptors
    // -----------------------------------------------------------------------

    @Test
    void shouldCallRepositoriesToInsertAnAccount() {
        // given
        AccountDTO inputDto = new AccountDTO();
        inputDto.setUsername("newuser");
        inputDto.setPassword("secret");
        inputDto.setEmail("new@example.com");
        inputDto.setFirstName("Jane");
        inputDto.setLastName("Smith");
        inputDto.setStatus("OK");
        inputDto.setAddress1("123 Main St");
        inputDto.setAddress2(null);
        inputDto.setCity("Springfield");
        inputDto.setState("IL");
        inputDto.setZip("62704");
        inputDto.setCountry("US");
        inputDto.setPhone("555-1234");
        inputDto.setLanguagePreference("english");
        inputDto.setFavouriteCategoryId("CATS");
        inputDto.setListOption(true);
        inputDto.setBannerOption(false);

        // Stubs for the internal getAccount() readback after the 3 inserts
        Account readbackAccount = new Account();
        readbackAccount.setUserid("newuser");
        readbackAccount.setEmail("new@example.com");
        readbackAccount.setFirstname("Jane");
        readbackAccount.setLastname("Smith");
        readbackAccount.setStatus("OK");
        readbackAccount.setAddress1("123 Main St");
        readbackAccount.setCity("Springfield");
        readbackAccount.setState("IL");
        readbackAccount.setZip("62704");
        readbackAccount.setCountry("US");
        readbackAccount.setPhone("555-1234");

        Profile readbackProfile = new Profile();
        readbackProfile.setUserid("newuser");
        readbackProfile.setLangpref("english");
        readbackProfile.setFavcategory("CATS");
        readbackProfile.setMylistopt(true);
        readbackProfile.setBanneropt(false);

        when(accountRepository.findById("newuser")).thenReturn(Optional.of(readbackAccount));
        when(profileRepository.findById("newuser")).thenReturn(Optional.of(readbackProfile));
        when(bannerDataRepository.findById("CATS")).thenReturn(Optional.empty());

        // when
        AccountDTO result = accountService.insertAccount(inputDto);

        // then — verify the returned DTO
        assertThat(result).isNotNull();
        assertThat(result.getUsername()).isEqualTo("newuser");
        assertThat(result.getEmail()).isEqualTo("new@example.com");
        assertThat(result.getFirstName()).isEqualTo("Jane");
        assertThat(result.getLastName()).isEqualTo("Smith");

        // Verify Account entity saved with correct field mapping
        ArgumentCaptor<Account> accountCaptor = ArgumentCaptor.forClass(Account.class);
        verify(accountRepository).save(accountCaptor.capture());
        Account savedAccount = accountCaptor.getValue();
        assertThat(savedAccount.getUserid()).isEqualTo("newuser");
        assertThat(savedAccount.getEmail()).isEqualTo("new@example.com");
        assertThat(savedAccount.getFirstname()).isEqualTo("Jane");
        assertThat(savedAccount.getLastname()).isEqualTo("Smith");
        assertThat(savedAccount.getAddress1()).isEqualTo("123 Main St");
        assertThat(savedAccount.getCity()).isEqualTo("Springfield");
        assertThat(savedAccount.getState()).isEqualTo("IL");
        assertThat(savedAccount.getZip()).isEqualTo("62704");
        assertThat(savedAccount.getCountry()).isEqualTo("US");
        assertThat(savedAccount.getPhone()).isEqualTo("555-1234");

        // Verify Profile entity saved with correct field mapping
        ArgumentCaptor<Profile> profileCaptor = ArgumentCaptor.forClass(Profile.class);
        verify(profileRepository).save(profileCaptor.capture());
        Profile savedProfile = profileCaptor.getValue();
        assertThat(savedProfile.getUserid()).isEqualTo("newuser");
        assertThat(savedProfile.getLangpref()).isEqualTo("english");
        assertThat(savedProfile.getFavcategory()).isEqualTo("CATS");
        assertThat(savedProfile.isMylistopt()).isTrue();
        assertThat(savedProfile.isBanneropt()).isFalse();

        // Verify Signon entity saved with correct field mapping
        ArgumentCaptor<Signon> signonCaptor = ArgumentCaptor.forClass(Signon.class);
        verify(signonRepository).save(signonCaptor.capture());
        Signon savedSignon = signonCaptor.getValue();
        assertThat(savedSignon.getUsername()).isEqualTo("newuser");
        assertThat(savedSignon.getPassword()).isEqualTo("secret");
    }

    // -----------------------------------------------------------------------
    // Test 6: updateAccount(String, AccountDTO) with non-empty password
    // Monolith equivalent: shouldCallTheMapperToUpdateAnAccount (line 58)
    // All 3 repos updated: Account, Profile, and Signon
    // -----------------------------------------------------------------------

    @Test
    void shouldCallRepositoriesToUpdateAnAccountWithPassword() {
        // given
        String username = "bar";

        AccountDTO dto = new AccountDTO();
        dto.setPassword("newpassword");
        dto.setEmail("updated@example.com");
        dto.setFirstName("Updated");
        dto.setLastName("User");
        dto.setStatus("OK");
        dto.setAddress1("456 Oak Ave");
        dto.setAddress2("Apt 2");
        dto.setCity("Shelbyville");
        dto.setState("IN");
        dto.setZip("46176");
        dto.setCountry("US");
        dto.setPhone("555-5678");
        dto.setLanguagePreference("japanese");
        dto.setFavouriteCategoryId("DOGS");
        dto.setListOption(false);
        dto.setBannerOption(true);

        Account existingAccount = new Account();
        existingAccount.setUserid(username);

        Profile existingProfile = new Profile();
        existingProfile.setUserid(username);

        Signon existingSignon = new Signon();
        existingSignon.setUsername(username);

        when(accountRepository.findById(username)).thenReturn(Optional.of(existingAccount));
        when(profileRepository.findById(username)).thenReturn(Optional.of(existingProfile));
        when(signonRepository.findById(username)).thenReturn(Optional.of(existingSignon));

        // when
        Optional<AccountDTO> result = accountService.updateAccount(username, dto);

        // then
        assertThat(result).isPresent();

        // Verify Account entity updated and saved
        verify(accountRepository).save(existingAccount);
        assertThat(existingAccount.getEmail()).isEqualTo("updated@example.com");
        assertThat(existingAccount.getFirstname()).isEqualTo("Updated");
        assertThat(existingAccount.getLastname()).isEqualTo("User");

        // Verify Profile entity updated and saved
        verify(profileRepository).save(existingProfile);
        assertThat(existingProfile.getLangpref()).isEqualTo("japanese");
        assertThat(existingProfile.getFavcategory()).isEqualTo("DOGS");
        assertThat(existingProfile.isMylistopt()).isFalse();
        assertThat(existingProfile.isBanneropt()).isTrue();

        // Password is non-null and non-empty → signon SHOULD be updated
        verify(signonRepository).findById(username);
        verify(signonRepository).save(existingSignon);
        assertThat(existingSignon.getPassword()).isEqualTo("newpassword");
    }

    // -----------------------------------------------------------------------
    // Test 7: updateAccount — empty password → signon NOT updated
    // Critical conditional logic test from monolith line 71:
    //   Optional.ofNullable("").filter(p -> p.length() > 0) → empty Optional
    // -----------------------------------------------------------------------

    @Test
    void shouldNotUpdateSignonWhenPasswordIsEmpty() {
        // given
        String username = "bar";

        AccountDTO dto = new AccountDTO();
        dto.setPassword("");  // empty string → signon should NOT be updated
        dto.setEmail("updated@example.com");
        dto.setFirstName("Updated");
        dto.setLastName("User");
        dto.setStatus("OK");
        dto.setAddress1("456 Oak Ave");
        dto.setAddress2(null);
        dto.setCity("Shelbyville");
        dto.setState("IN");
        dto.setZip("46176");
        dto.setCountry("US");
        dto.setPhone("555-5678");
        dto.setLanguagePreference("english");
        dto.setFavouriteCategoryId("FISH");
        dto.setListOption(false);
        dto.setBannerOption(false);

        Account existingAccount = new Account();
        existingAccount.setUserid(username);

        Profile existingProfile = new Profile();
        existingProfile.setUserid(username);

        when(accountRepository.findById(username)).thenReturn(Optional.of(existingAccount));
        when(profileRepository.findById(username)).thenReturn(Optional.of(existingProfile));

        // when
        accountService.updateAccount(username, dto);

        // then
        verify(accountRepository).save(existingAccount);
        verify(profileRepository).save(existingProfile);
        // Empty password → signon should NOT be updated (mirrors monolith conditional)
        verify(signonRepository, never()).findById(any());
        verify(signonRepository, never()).save(any(Signon.class));
    }

    // -----------------------------------------------------------------------
    // Test 8: updateAccount — null password → signon NOT updated
    // Critical conditional logic test from monolith line 71:
    //   Optional.ofNullable(null) → empty Optional → ifPresent not called
    // -----------------------------------------------------------------------

    @Test
    void shouldNotUpdateSignonWhenPasswordIsNull() {
        // given
        String username = "bar";

        AccountDTO dto = new AccountDTO();
        dto.setPassword(null);  // null → signon should NOT be updated
        dto.setEmail("updated@example.com");
        dto.setFirstName("Updated");
        dto.setLastName("User");
        dto.setStatus("OK");
        dto.setAddress1("456 Oak Ave");
        dto.setAddress2(null);
        dto.setCity("Shelbyville");
        dto.setState("IN");
        dto.setZip("46176");
        dto.setCountry("US");
        dto.setPhone("555-5678");
        dto.setLanguagePreference("english");
        dto.setFavouriteCategoryId("FISH");
        dto.setListOption(false);
        dto.setBannerOption(false);

        Account existingAccount = new Account();
        existingAccount.setUserid(username);

        Profile existingProfile = new Profile();
        existingProfile.setUserid(username);

        when(accountRepository.findById(username)).thenReturn(Optional.of(existingAccount));
        when(profileRepository.findById(username)).thenReturn(Optional.of(existingProfile));

        // when
        accountService.updateAccount(username, dto);

        // then
        verify(accountRepository).save(existingAccount);
        verify(profileRepository).save(existingProfile);
        // Null password → signon should NOT be updated
        verify(signonRepository, never()).findById(any());
        verify(signonRepository, never()).save(any(Signon.class));
    }
}
