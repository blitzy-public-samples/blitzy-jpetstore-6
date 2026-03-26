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

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Account Service DTO classes.
 *
 * <p>Exercises constructors, getters, setters, and field access for
 * {@link AccountDTO} and {@link SignonResponse}.</p>
 */
class DtoCoverageTest {

    @Nested
    @DisplayName("AccountDTO Tests")
    class AccountDTOTests {

        @Test
        @DisplayName("Default constructor creates empty DTO")
        void shouldCreateEmptyDTO() {
            AccountDTO dto = new AccountDTO();
            assertThat(dto.getUsername()).isNull();
            assertThat(dto.getPassword()).isNull();
            assertThat(dto.getEmail()).isNull();
            assertThat(dto.getFirstName()).isNull();
            assertThat(dto.getLastName()).isNull();
            assertThat(dto.getStatus()).isNull();
            assertThat(dto.isListOption()).isFalse();
            assertThat(dto.isBannerOption()).isFalse();
        }

        @Test
        @DisplayName("Parameterized constructor sets all fields")
        void shouldCreateWithAllFields() {
            AccountDTO dto = new AccountDTO("j2ee", "j2ee", "j2ee@example.com",
                    "John", "Doe", "OK", "901 San Antonio Road", "MS UCUP02-206",
                    "Palo Alto", "CA", "94303", "USA", "555-555-5555",
                    "english", "FISH", true, true, "banner_fish.gif");

            assertThat(dto.getUsername()).isEqualTo("j2ee");
            assertThat(dto.getPassword()).isEqualTo("j2ee");
            assertThat(dto.getEmail()).isEqualTo("j2ee@example.com");
            assertThat(dto.getFirstName()).isEqualTo("John");
            assertThat(dto.getLastName()).isEqualTo("Doe");
            assertThat(dto.getStatus()).isEqualTo("OK");
            assertThat(dto.getAddress1()).isEqualTo("901 San Antonio Road");
            assertThat(dto.getAddress2()).isEqualTo("MS UCUP02-206");
            assertThat(dto.getCity()).isEqualTo("Palo Alto");
            assertThat(dto.getState()).isEqualTo("CA");
            assertThat(dto.getZip()).isEqualTo("94303");
            assertThat(dto.getCountry()).isEqualTo("USA");
            assertThat(dto.getPhone()).isEqualTo("555-555-5555");
            assertThat(dto.getLanguagePreference()).isEqualTo("english");
            assertThat(dto.getFavouriteCategoryId()).isEqualTo("FISH");
            assertThat(dto.isListOption()).isTrue();
            assertThat(dto.isBannerOption()).isTrue();
            assertThat(dto.getBannerName()).isEqualTo("banner_fish.gif");
        }

        @Test
        @DisplayName("Setters update all fields")
        void shouldSetAllFields() {
            AccountDTO dto = new AccountDTO();
            dto.setUsername("testuser");
            dto.setPassword("secret");
            dto.setEmail("test@test.com");
            dto.setFirstName("Jane");
            dto.setLastName("Smith");
            dto.setStatus("OK");
            dto.setAddress1("456 Elm St");
            dto.setAddress2("Suite 100");
            dto.setCity("Boston");
            dto.setState("MA");
            dto.setZip("02101");
            dto.setCountry("USA");
            dto.setPhone("617-555-0100");
            dto.setLanguagePreference("japanese");
            dto.setFavouriteCategoryId("DOGS");
            dto.setListOption(true);
            dto.setBannerOption(false);
            dto.setBannerName("banner_dogs.gif");

            assertThat(dto.getUsername()).isEqualTo("testuser");
            assertThat(dto.getPassword()).isEqualTo("secret");
            assertThat(dto.getEmail()).isEqualTo("test@test.com");
            assertThat(dto.getFirstName()).isEqualTo("Jane");
            assertThat(dto.getLastName()).isEqualTo("Smith");
            assertThat(dto.getStatus()).isEqualTo("OK");
            assertThat(dto.getAddress1()).isEqualTo("456 Elm St");
            assertThat(dto.getAddress2()).isEqualTo("Suite 100");
            assertThat(dto.getCity()).isEqualTo("Boston");
            assertThat(dto.getState()).isEqualTo("MA");
            assertThat(dto.getZip()).isEqualTo("02101");
            assertThat(dto.getCountry()).isEqualTo("USA");
            assertThat(dto.getPhone()).isEqualTo("617-555-0100");
            assertThat(dto.getLanguagePreference()).isEqualTo("japanese");
            assertThat(dto.getFavouriteCategoryId()).isEqualTo("DOGS");
            assertThat(dto.isListOption()).isTrue();
            assertThat(dto.isBannerOption()).isFalse();
            assertThat(dto.getBannerName()).isEqualTo("banner_dogs.gif");
        }
    }

    @Nested
    @DisplayName("SignonResponse Tests")
    class SignonResponseTests {

        @Test
        @DisplayName("Default constructor creates empty response")
        void shouldCreateEmptyResponse() {
            SignonResponse response = new SignonResponse();
            assertThat(response.getToken()).isNull();
            assertThat(response.getUsername()).isNull();
            assertThat(response.getEmail()).isNull();
        }

        @Test
        @DisplayName("Parameterized constructor sets all fields")
        void shouldCreateWithParams() {
            SignonResponse response = new SignonResponse("jwt.token.here", "j2ee", "j2ee@example.com");

            assertThat(response.getToken()).isEqualTo("jwt.token.here");
            assertThat(response.getUsername()).isEqualTo("j2ee");
            assertThat(response.getEmail()).isEqualTo("j2ee@example.com");
        }

        @Test
        @DisplayName("Setters update all fields")
        void shouldSetFields() {
            SignonResponse response = new SignonResponse();
            response.setToken("new.token");
            response.setUsername("testuser");
            response.setEmail("test@test.com");

            assertThat(response.getToken()).isEqualTo("new.token");
            assertThat(response.getUsername()).isEqualTo("testuser");
            assertThat(response.getEmail()).isEqualTo("test@test.com");
        }
    }
}
