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
package com.jpetstore.account.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * Data Transfer Object for the Account Service REST API.
 *
 * <p>Combines fields from the {@code account}, {@code profile}, {@code signon},
 * and {@code bannerdata} tables into a single JSON representation. This DTO
 * replaces the monolith's 4-table JOIN resultMap (AccountMapper.xml) with a
 * service-layer assembly from separate JPA entities.</p>
 *
 * <p>Used by the AccountController for:</p>
 * <ul>
 *   <li>{@code GET /api/accounts/{username}} — returns AccountDTO (password excluded via WRITE_ONLY)</li>
 *   <li>{@code POST /api/accounts} — receives AccountDTO with password for registration</li>
 *   <li>{@code PUT /api/accounts/{username}} — receives AccountDTO with optional password for update</li>
 * </ul>
 *
 * <p>Field names use Java camelCase matching the monolith {@code Account.java} domain class,
 * NOT database column names. The entity layer handles column-to-Java mapping via
 * {@code @Column} annotations.</p>
 *
 * @see com.jpetstore.account.controller.AccountController
 * @see com.jpetstore.account.service.AccountService
 */
public class AccountDTO {

    // -----------------------------------------------------------------------
    // Fields from 'account' table (Account entity)
    // -----------------------------------------------------------------------

    /**
     * Unique username identifier for the account.
     * Maps from: account.userid (PK)
     */
    @NotBlank
    private String username;

    /**
     * Account password from the signon table.
     * This field is WRITE_ONLY — it is accepted for registration (POST) and
     * update (PUT) but never included in JSON responses (GET). This replaces
     * the monolith's manual {@code account.setPassword(null)} pattern in
     * AccountActionBean.signon() at line 169.
     */
    @JsonProperty(access = JsonProperty.Access.WRITE_ONLY)
    private String password;

    /**
     * Email address for the account.
     * Maps from: account.email
     */
    @NotBlank
    @Email
    private String email;

    /**
     * First name of the account holder.
     * Maps from: account.firstname
     * Replaces monolith Stripes {@code @Validate(required = true)} on setFirstName().
     */
    @NotBlank
    private String firstName;

    /**
     * Last name of the account holder.
     * Maps from: account.lastname
     * Replaces monolith Stripes {@code @Validate(required = true)} on setLastName().
     */
    @NotBlank
    private String lastName;

    /**
     * Account status indicator.
     * Maps from: account.status (nullable in schema)
     */
    private String status;

    /**
     * Primary address line.
     * Maps from: account.addr1
     */
    private String address1;

    /**
     * Secondary address line.
     * Maps from: account.addr2 (nullable in schema)
     */
    private String address2;

    /**
     * City for the account address.
     * Maps from: account.city
     */
    private String city;

    /**
     * State or province for the account address.
     * Maps from: account.state
     */
    private String state;

    /**
     * Postal/ZIP code for the account address.
     * Maps from: account.zip
     */
    private String zip;

    /**
     * Country for the account address.
     * Maps from: account.country
     */
    private String country;

    /**
     * Phone number for the account.
     * Maps from: account.phone
     */
    private String phone;

    // -----------------------------------------------------------------------
    // Fields from 'profile' table (Profile entity)
    // -----------------------------------------------------------------------

    /**
     * Preferred language for the user interface.
     * Maps from: profile.langpref (aliased as languagePreference in AccountMapper.xml)
     */
    private String languagePreference;

    /**
     * Favourite product category identifier used for personalization.
     * Maps from: profile.favcategory (aliased as favouriteCategoryId in AccountMapper.xml)
     * Used for the cross-service personalization call:
     * {@code catalogService.getProductListByCategory(account.getFavouriteCategoryId())}
     */
    private String favouriteCategoryId;

    /**
     * Whether the user opted into the personalized product list display.
     * Maps from: profile.mylistopt (int 0/1 converted to boolean)
     */
    private boolean listOption;

    /**
     * Whether the user opted into seeing promotional banner advertisements.
     * Maps from: profile.banneropt (int 0/1 converted to boolean)
     */
    private boolean bannerOption;

    // -----------------------------------------------------------------------
    // Fields from 'bannerdata' table (BannerData entity)
    // -----------------------------------------------------------------------

    /**
     * Name/path of the promotional banner image.
     * Maps from: bannerdata.bannername (joined via profile.favcategory = bannerdata.favcategory)
     */
    private String bannerName;

    // -----------------------------------------------------------------------
    // Constructors
    // -----------------------------------------------------------------------

    /**
     * Default no-arg constructor required by Jackson for JSON deserialization.
     */
    public AccountDTO() {
        // Default constructor for Jackson deserialization
    }

    /**
     * Parameterized constructor for service-layer assembly from JPA entities.
     * Assembles the DTO from separate Account, Profile, Signon, and BannerData entity data.
     *
     * @param username            the unique username (from account.userid)
     * @param password            the password (from signon.password) — typically null for GET responses
     * @param email               the email address (from account.email)
     * @param firstName           the first name (from account.firstname)
     * @param lastName            the last name (from account.lastname)
     * @param status              the account status (from account.status)
     * @param address1            the primary address line (from account.addr1)
     * @param address2            the secondary address line (from account.addr2)
     * @param city                the city (from account.city)
     * @param state               the state/province (from account.state)
     * @param zip                 the postal code (from account.zip)
     * @param country             the country (from account.country)
     * @param phone               the phone number (from account.phone)
     * @param languagePreference  the preferred language (from profile.langpref)
     * @param favouriteCategoryId the favourite category (from profile.favcategory)
     * @param listOption          whether product list is enabled (from profile.mylistopt)
     * @param bannerOption        whether banner display is enabled (from profile.banneropt)
     * @param bannerName          the banner image name (from bannerdata.bannername)
     */
    public AccountDTO(String username, String password, String email,
                      String firstName, String lastName, String status,
                      String address1, String address2, String city,
                      String state, String zip, String country, String phone,
                      String languagePreference, String favouriteCategoryId,
                      boolean listOption, boolean bannerOption, String bannerName) {
        this.username = username;
        this.password = password;
        this.email = email;
        this.firstName = firstName;
        this.lastName = lastName;
        this.status = status;
        this.address1 = address1;
        this.address2 = address2;
        this.city = city;
        this.state = state;
        this.zip = zip;
        this.country = country;
        this.phone = phone;
        this.languagePreference = languagePreference;
        this.favouriteCategoryId = favouriteCategoryId;
        this.listOption = listOption;
        this.bannerOption = bannerOption;
        this.bannerName = bannerName;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters — account table fields
    // -----------------------------------------------------------------------

    /**
     * Returns the unique username identifier.
     *
     * @return the username
     */
    public String getUsername() {
        return username;
    }

    /**
     * Sets the unique username identifier.
     *
     * @param username the username to set
     */
    public void setUsername(String username) {
        this.username = username;
    }

    /**
     * Returns the account password.
     * Note: This field is annotated with {@code @JsonProperty(access = WRITE_ONLY)}
     * so it will never be serialized into JSON responses.
     *
     * @return the password
     */
    public String getPassword() {
        return password;
    }

    /**
     * Sets the account password.
     * Used during registration (POST) and account update (PUT).
     *
     * @param password the password to set
     */
    public void setPassword(String password) {
        this.password = password;
    }

    /**
     * Returns the email address.
     *
     * @return the email
     */
    public String getEmail() {
        return email;
    }

    /**
     * Sets the email address.
     *
     * @param email the email to set
     */
    public void setEmail(String email) {
        this.email = email;
    }

    /**
     * Returns the first name of the account holder.
     *
     * @return the first name
     */
    public String getFirstName() {
        return firstName;
    }

    /**
     * Sets the first name of the account holder.
     *
     * @param firstName the first name to set
     */
    public void setFirstName(String firstName) {
        this.firstName = firstName;
    }

    /**
     * Returns the last name of the account holder.
     *
     * @return the last name
     */
    public String getLastName() {
        return lastName;
    }

    /**
     * Sets the last name of the account holder.
     *
     * @param lastName the last name to set
     */
    public void setLastName(String lastName) {
        this.lastName = lastName;
    }

    /**
     * Returns the account status indicator.
     *
     * @return the status
     */
    public String getStatus() {
        return status;
    }

    /**
     * Sets the account status indicator.
     *
     * @param status the status to set
     */
    public void setStatus(String status) {
        this.status = status;
    }

    /**
     * Returns the primary address line.
     *
     * @return the primary address
     */
    public String getAddress1() {
        return address1;
    }

    /**
     * Sets the primary address line.
     *
     * @param address1 the primary address to set
     */
    public void setAddress1(String address1) {
        this.address1 = address1;
    }

    /**
     * Returns the secondary address line.
     *
     * @return the secondary address
     */
    public String getAddress2() {
        return address2;
    }

    /**
     * Sets the secondary address line.
     *
     * @param address2 the secondary address to set
     */
    public void setAddress2(String address2) {
        this.address2 = address2;
    }

    /**
     * Returns the city for the account address.
     *
     * @return the city
     */
    public String getCity() {
        return city;
    }

    /**
     * Sets the city for the account address.
     *
     * @param city the city to set
     */
    public void setCity(String city) {
        this.city = city;
    }

    /**
     * Returns the state or province for the account address.
     *
     * @return the state
     */
    public String getState() {
        return state;
    }

    /**
     * Sets the state or province for the account address.
     *
     * @param state the state to set
     */
    public void setState(String state) {
        this.state = state;
    }

    /**
     * Returns the postal/ZIP code for the account address.
     *
     * @return the zip code
     */
    public String getZip() {
        return zip;
    }

    /**
     * Sets the postal/ZIP code for the account address.
     *
     * @param zip the zip code to set
     */
    public void setZip(String zip) {
        this.zip = zip;
    }

    /**
     * Returns the country for the account address.
     *
     * @return the country
     */
    public String getCountry() {
        return country;
    }

    /**
     * Sets the country for the account address.
     *
     * @param country the country to set
     */
    public void setCountry(String country) {
        this.country = country;
    }

    /**
     * Returns the phone number for the account.
     *
     * @return the phone number
     */
    public String getPhone() {
        return phone;
    }

    /**
     * Sets the phone number for the account.
     *
     * @param phone the phone number to set
     */
    public void setPhone(String phone) {
        this.phone = phone;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters — profile table fields
    // -----------------------------------------------------------------------

    /**
     * Returns the preferred language for the user interface.
     *
     * @return the language preference
     */
    public String getLanguagePreference() {
        return languagePreference;
    }

    /**
     * Sets the preferred language for the user interface.
     *
     * @param languagePreference the language preference to set
     */
    public void setLanguagePreference(String languagePreference) {
        this.languagePreference = languagePreference;
    }

    /**
     * Returns the favourite product category identifier used for personalization.
     * This value drives the cross-service call to CatalogService for the
     * personalized product list (myList) display.
     *
     * @return the favourite category ID
     */
    public String getFavouriteCategoryId() {
        return favouriteCategoryId;
    }

    /**
     * Sets the favourite product category identifier.
     *
     * @param favouriteCategoryId the favourite category ID to set
     */
    public void setFavouriteCategoryId(String favouriteCategoryId) {
        this.favouriteCategoryId = favouriteCategoryId;
    }

    /**
     * Returns whether the user opted into the personalized product list display.
     * Uses the JavaBean {@code is} prefix convention for boolean getters.
     *
     * @return {@code true} if the product list is enabled, {@code false} otherwise
     */
    public boolean isListOption() {
        return listOption;
    }

    /**
     * Sets whether the user opted into the personalized product list display.
     *
     * @param listOption {@code true} to enable the product list, {@code false} to disable
     */
    public void setListOption(boolean listOption) {
        this.listOption = listOption;
    }

    /**
     * Returns whether the user opted into seeing promotional banner advertisements.
     * Uses the JavaBean {@code is} prefix convention for boolean getters.
     *
     * @return {@code true} if banners are enabled, {@code false} otherwise
     */
    public boolean isBannerOption() {
        return bannerOption;
    }

    /**
     * Sets whether the user opted into seeing promotional banner advertisements.
     *
     * @param bannerOption {@code true} to enable banners, {@code false} to disable
     */
    public void setBannerOption(boolean bannerOption) {
        this.bannerOption = bannerOption;
    }

    // -----------------------------------------------------------------------
    // Getters and Setters — bannerdata table fields
    // -----------------------------------------------------------------------

    /**
     * Returns the name/path of the promotional banner image.
     *
     * @return the banner name
     */
    public String getBannerName() {
        return bannerName;
    }

    /**
     * Sets the name/path of the promotional banner image.
     *
     * @param bannerName the banner name to set
     */
    public void setBannerName(String bannerName) {
        this.bannerName = bannerName;
    }

}
