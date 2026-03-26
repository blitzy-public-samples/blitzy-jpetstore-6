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
package com.jpetstore.account.entity;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for Account Service JPA entity classes.
 *
 * <p>Exercises constructors, getters/setters, equals, hashCode, and toString
 * for {@link Account}, {@link Profile}, {@link BannerData}, and {@link Signon}.</p>
 */
class EntityCoverageTest {

    @Nested
    @DisplayName("Account Entity Tests")
    class AccountTests {

        @Test
        @DisplayName("Default constructor creates empty account")
        void shouldCreateEmptyAccount() {
            Account account = new Account();
            assertThat(account.getUserid()).isNull();
            assertThat(account.getEmail()).isNull();
            assertThat(account.getFirstname()).isNull();
            assertThat(account.getLastname()).isNull();
            assertThat(account.getStatus()).isNull();
            assertThat(account.getAddress1()).isNull();
            assertThat(account.getAddress2()).isNull();
            assertThat(account.getCity()).isNull();
            assertThat(account.getState()).isNull();
            assertThat(account.getZip()).isNull();
            assertThat(account.getCountry()).isNull();
            assertThat(account.getPhone()).isNull();
        }

        @Test
        @DisplayName("Parameterized constructor sets all fields")
        void shouldCreateAccountWithAllFields() {
            Account account = new Account("j2ee", "j2ee@example.com", "John",
                    "Doe", "OK", "901 San Antonio Road", "MS UCUP02-206",
                    "Palo Alto", "CA", "94303", "USA", "555-555-5555");

            assertThat(account.getUserid()).isEqualTo("j2ee");
            assertThat(account.getEmail()).isEqualTo("j2ee@example.com");
            assertThat(account.getFirstname()).isEqualTo("John");
            assertThat(account.getLastname()).isEqualTo("Doe");
            assertThat(account.getStatus()).isEqualTo("OK");
            assertThat(account.getAddress1()).isEqualTo("901 San Antonio Road");
            assertThat(account.getAddress2()).isEqualTo("MS UCUP02-206");
            assertThat(account.getCity()).isEqualTo("Palo Alto");
            assertThat(account.getState()).isEqualTo("CA");
            assertThat(account.getZip()).isEqualTo("94303");
            assertThat(account.getCountry()).isEqualTo("USA");
            assertThat(account.getPhone()).isEqualTo("555-555-5555");
        }

        @Test
        @DisplayName("Setters update all fields")
        void shouldSetAllFields() {
            Account account = new Account();
            account.setUserid("testuser");
            account.setEmail("test@example.com");
            account.setFirstname("Jane");
            account.setLastname("Smith");
            account.setStatus("OK");
            account.setAddress1("123 Main St");
            account.setAddress2("Apt 4B");
            account.setCity("Boston");
            account.setState("MA");
            account.setZip("02101");
            account.setCountry("USA");
            account.setPhone("617-555-0100");

            assertThat(account.getUserid()).isEqualTo("testuser");
            assertThat(account.getEmail()).isEqualTo("test@example.com");
            assertThat(account.getFirstname()).isEqualTo("Jane");
            assertThat(account.getLastname()).isEqualTo("Smith");
            assertThat(account.getStatus()).isEqualTo("OK");
            assertThat(account.getAddress1()).isEqualTo("123 Main St");
            assertThat(account.getAddress2()).isEqualTo("Apt 4B");
            assertThat(account.getCity()).isEqualTo("Boston");
            assertThat(account.getState()).isEqualTo("MA");
            assertThat(account.getZip()).isEqualTo("02101");
            assertThat(account.getCountry()).isEqualTo("USA");
            assertThat(account.getPhone()).isEqualTo("617-555-0100");
        }
    }

    @Nested
    @DisplayName("Profile Entity Tests")
    class ProfileTests {

        @Test
        @DisplayName("Default constructor creates empty profile")
        void shouldCreateEmptyProfile() {
            Profile profile = new Profile();
            assertThat(profile.getUserid()).isNull();
            assertThat(profile.getLangpref()).isNull();
            assertThat(profile.getFavcategory()).isNull();
            assertThat(profile.isMylistopt()).isFalse();
            assertThat(profile.isBanneropt()).isFalse();
        }

        @Test
        @DisplayName("Parameterized constructor sets all fields")
        void shouldCreateProfileWithAllFields() {
            Profile profile = new Profile("j2ee", "english", "FISH", true, true);

            assertThat(profile.getUserid()).isEqualTo("j2ee");
            assertThat(profile.getLangpref()).isEqualTo("english");
            assertThat(profile.getFavcategory()).isEqualTo("FISH");
            assertThat(profile.isMylistopt()).isTrue();
            assertThat(profile.isBanneropt()).isTrue();
        }

        @Test
        @DisplayName("Setters update all fields")
        void shouldSetAllProfileFields() {
            Profile profile = new Profile();
            profile.setUserid("testuser");
            profile.setLangpref("japanese");
            profile.setFavcategory("DOGS");
            profile.setMylistopt(true);
            profile.setBanneropt(false);

            assertThat(profile.getUserid()).isEqualTo("testuser");
            assertThat(profile.getLangpref()).isEqualTo("japanese");
            assertThat(profile.getFavcategory()).isEqualTo("DOGS");
            assertThat(profile.isMylistopt()).isTrue();
            assertThat(profile.isBanneropt()).isFalse();
        }

        @Test
        @DisplayName("Equals and hashCode work correctly")
        void shouldImplementEqualsAndHashCode() {
            Profile p1 = new Profile("j2ee", "english", "FISH", true, true);
            Profile p2 = new Profile("j2ee", "english", "FISH", true, true);
            Profile p3 = new Profile("other", "english", "FISH", true, true);

            assertThat(p1).isEqualTo(p2);
            assertThat(p1.hashCode()).isEqualTo(p2.hashCode());
            assertThat(p1).isNotEqualTo(p3);
            assertThat(p1).isNotEqualTo(null);
            assertThat(p1).isNotEqualTo("not a profile");
        }

        @Test
        @DisplayName("toString produces meaningful output")
        void shouldProduceToString() {
            Profile profile = new Profile("j2ee", "english", "FISH", true, true);
            String str = profile.toString();
            assertThat(str).contains("j2ee");
            assertThat(str).contains("english");
        }

        @Test
        @DisplayName("Equals reflexive — profile equals itself")
        void shouldBeEqualToItself() {
            Profile p = new Profile("j2ee", "english", "FISH", true, true);
            assertThat(p).isEqualTo(p);
        }
    }

    @Nested
    @DisplayName("BannerData Entity Tests")
    class BannerDataTests {

        @Test
        @DisplayName("Default constructor creates empty BannerData")
        void shouldCreateEmptyBannerData() {
            BannerData bd = new BannerData();
            assertThat(bd.getFavcategory()).isNull();
            assertThat(bd.getBannername()).isNull();
        }

        @Test
        @DisplayName("Parameterized constructor sets fields")
        void shouldCreateWithParams() {
            BannerData bd = new BannerData("FISH", "<image src=\"../images/banner_fish.gif\">");

            assertThat(bd.getFavcategory()).isEqualTo("FISH");
            assertThat(bd.getBannername()).isEqualTo("<image src=\"../images/banner_fish.gif\">");
        }

        @Test
        @DisplayName("Setters update fields")
        void shouldSetFields() {
            BannerData bd = new BannerData();
            bd.setFavcategory("DOGS");
            bd.setBannername("<image src=\"../images/banner_dogs.gif\">");

            assertThat(bd.getFavcategory()).isEqualTo("DOGS");
            assertThat(bd.getBannername()).isEqualTo("<image src=\"../images/banner_dogs.gif\">");
        }

        @Test
        @DisplayName("Equals and hashCode work correctly")
        void shouldImplementEqualsAndHashCode() {
            BannerData bd1 = new BannerData("FISH", "banner_fish");
            BannerData bd2 = new BannerData("FISH", "banner_fish");
            BannerData bd3 = new BannerData("DOGS", "banner_dogs");

            assertThat(bd1).isEqualTo(bd2);
            assertThat(bd1.hashCode()).isEqualTo(bd2.hashCode());
            assertThat(bd1).isNotEqualTo(bd3);
            assertThat(bd1).isNotEqualTo(null);
            assertThat(bd1).isNotEqualTo("not a BannerData");
        }

        @Test
        @DisplayName("toString contains favcategory")
        void shouldProduceToString() {
            BannerData bd = new BannerData("FISH", "banner_fish");
            assertThat(bd.toString()).contains("FISH");
        }

        @Test
        @DisplayName("Equals reflexive — BannerData equals itself")
        void shouldBeEqualToItself() {
            BannerData bd = new BannerData("FISH", "banner_fish");
            assertThat(bd).isEqualTo(bd);
        }
    }

    @Nested
    @DisplayName("Signon Entity Tests")
    class SignonTests {

        @Test
        @DisplayName("Default constructor creates empty Signon")
        void shouldCreateEmptySignon() {
            Signon signon = new Signon();
            assertThat(signon.getUsername()).isNull();
            assertThat(signon.getPassword()).isNull();
        }

        @Test
        @DisplayName("Parameterized constructor sets fields")
        void shouldCreateWithParams() {
            Signon signon = new Signon("j2ee", "$2a$10$hashedpassword");

            assertThat(signon.getUsername()).isEqualTo("j2ee");
            assertThat(signon.getPassword()).isEqualTo("$2a$10$hashedpassword");
        }

        @Test
        @DisplayName("Setters update fields")
        void shouldSetFields() {
            Signon signon = new Signon();
            signon.setUsername("testuser");
            signon.setPassword("secrethash");

            assertThat(signon.getUsername()).isEqualTo("testuser");
            assertThat(signon.getPassword()).isEqualTo("secrethash");
        }

        @Test
        @DisplayName("Equals and hashCode work correctly")
        void shouldImplementEqualsAndHashCode() {
            Signon s1 = new Signon("j2ee", "pass1");
            Signon s2 = new Signon("j2ee", "pass1");
            Signon s3 = new Signon("other", "pass2");

            assertThat(s1).isEqualTo(s2);
            assertThat(s1.hashCode()).isEqualTo(s2.hashCode());
            assertThat(s1).isNotEqualTo(s3);
            assertThat(s1).isNotEqualTo(null);
            assertThat(s1).isNotEqualTo("not a Signon");
        }

        @Test
        @DisplayName("toString contains username")
        void shouldProduceToString() {
            Signon signon = new Signon("j2ee", "pass");
            assertThat(signon.toString()).contains("j2ee");
        }

        @Test
        @DisplayName("Equals reflexive — Signon equals itself")
        void shouldBeEqualToItself() {
            Signon s = new Signon("j2ee", "pass");
            assertThat(s).isEqualTo(s);
        }
    }
}
