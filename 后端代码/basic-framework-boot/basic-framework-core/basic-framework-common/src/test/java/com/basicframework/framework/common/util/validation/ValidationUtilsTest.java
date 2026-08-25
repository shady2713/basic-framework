package com.basicframework.framework.common.util.validation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * ValidationUtils 语义验证器测试
 *
 * 正例/反例向量与 docs/contracts/field-catalog.yaml 的 sharedTestVectors
 * 逐条对齐：目录是契约源，本测试把契约向量落到 Java 断言（漂移双保险——
 * 目录侧由 check-field-catalog.mjs 保证，行为侧由本测试钉死）。
 */
class ValidationUtilsTest {

    @ParameterizedTest
    @MethodSource("validMobileVectors")
    void isMobile_acceptsCatalogValidVectors(String mobile) {
        assertThat(ValidationUtils.isMobile(mobile)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("invalidMobileVectors")
    void isMobile_rejectsCatalogInvalidVectorsAndBoundaries(String mobile) {
        assertThat(ValidationUtils.isMobile(mobile)).isFalse();
    }

    static List<String> validMobileVectors() {
        return Arrays.asList("13812345678", "19900001234");
    }

    static List<String> invalidMobileVectors() {
        return Arrays.asList(
                "23812345678", "+8613812345678", "1381234567", "138123456789", "138 2345 678", "", "1381234567a", null);
    }

    @ParameterizedTest
    @MethodSource("validUsernameVectors")
    void isUsername_acceptsCatalogValidVectors(String username) {
        assertThat(ValidationUtils.isUsername(username)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("invalidUsernameVectors")
    void isUsername_rejectsCatalogInvalidVectorsAndBoundaries(String username) {
        assertThat(ValidationUtils.isUsername(username)).isFalse();
    }

    static List<String> validUsernameVectors() {
        return Arrays.asList("admin", "User123", "a1b2", "user_123");
    }

    static List<String> invalidUsernameVectors() {
        return Arrays.asList(
                "abc", "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "1user", "_user", "user name", "用户名", "😀abcd", "", null);
    }

    @Test
    void normalizeUsername_trimsNormalizesAndLowercases() {
        assertThat(ValidationUtils.normalizeUsername("  UsEr_123  ")).isEqualTo("user_123");
        assertThat(ValidationUtils.normalizeUsername(null)).isNull();
    }

    @ParameterizedTest
    @MethodSource("validPasswordVectors")
    void isPassword_acceptsCatalogValidVectors(String password) {
        assertThat(ValidationUtils.isPassword(password)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("invalidPasswordVectors")
    void isPassword_rejectsCatalogInvalidVectorsAndBoundaries(String password) {
        assertThat(ValidationUtils.isPassword(password)).isFalse();
    }

    static List<String> validPasswordVectors() {
        return Arrays.asList("Abcd12", "Admin123");
    }

    static List<String> invalidPasswordVectors() {
        return Arrays.asList(
                "abcdef12",
                "ABCDEF12",
                "Abcdefgh",
                "Abc1",
                "Abcd1234567890abc",
                "Ab cd12",
                "密码Abc123",
                "Abcd12😀",
                "",
                null);
    }

    @ParameterizedTest
    @MethodSource("validEmailVectors")
    void isEmail_acceptsCatalogValidVectors(String email) {
        assertThat(ValidationUtils.isEmail(email)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("invalidEmailVectors")
    void isEmail_rejectsCatalogInvalidVectorsAndBoundaries(String email) {
        assertThat(ValidationUtils.isEmail(email)).isFalse();
    }

    static List<String> validEmailVectors() {
        return Arrays.asList("user@example.com", "USER+tag@Example.COM");
    }

    static List<String> invalidEmailVectors() {
        return Arrays.asList("user@invalid", "user@", "@example.com", "user @example.com", "用户@example.com", "", null);
    }

    @ParameterizedTest
    @ValueSource(strings = {"100", "100.00", "99.99", "0", "0.5"})
    void isPercent_acceptsValidPercentStrings(String percent) {
        assertThat(ValidationUtils.isPercent(percent)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"100.001", "101", "-5", "abc", ""})
    void isPercent_rejectsOutOfRangeMalformedAndBlanks(String percent) {
        assertThat(ValidationUtils.isPercent(percent)).isFalse();
    }

    @Test
    void isPercent_rejectsNull() {
        assertThat(ValidationUtils.isPercent((String) null)).isFalse();
        assertThat(ValidationUtils.isPercent((Number) null)).isFalse();
    }

    @ParameterizedTest
    @MethodSource("validPercentNumberVectors")
    void isPercent_acceptsValidNumbers(Number percent) {
        assertThat(ValidationUtils.isPercent(percent)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("invalidPercentNumberVectors")
    void isPercent_rejectsInvalidNumbers(Number percent) {
        assertThat(ValidationUtils.isPercent(percent)).isFalse();
    }

    static List<Number> validPercentNumberVectors() {
        return Arrays.asList(new BigDecimal("99.99"), new BigDecimal("100"), BigDecimal.ZERO);
    }

    static List<Number> invalidPercentNumberVectors() {
        return Arrays.asList(new BigDecimal("12.345"), new BigDecimal("101"), new BigDecimal("-5"), null);
    }

    @ParameterizedTest
    @MethodSource("validQuantityVectors")
    void isQuantity_acceptsWholeNonNegativeNumbers(Number quantity) {
        assertThat(ValidationUtils.isQuantity(quantity)).isTrue();
    }

    @ParameterizedTest
    @MethodSource("invalidQuantityVectors")
    void isQuantity_rejectsFractionalNegativeAndNull(Number quantity) {
        assertThat(ValidationUtils.isQuantity(quantity)).isFalse();
    }

    static List<Number> validQuantityVectors() {
        return Arrays.asList(0, 100, 100L, new BigDecimal("100.00"));
    }

    static List<Number> invalidQuantityVectors() {
        return Arrays.asList(new BigDecimal("100.5"), new BigDecimal("-3"), null);
    }

    @Nested
    class Validate {

        @Test
        void validate_withoutViolations_returnsNormally() {
            Validator validator = mock(Validator.class);
            Object target = new Object();
            when(validator.validate(eq(target), any(Class[].class))).thenReturn(Set.of());

            ValidationUtils.validate(validator, target);
        }

        @Test
        void validate_withViolations_throwsConstraintViolationException() {
            Validator validator = mock(Validator.class);
            Object target = new Object();
            @SuppressWarnings("unchecked")
            ConstraintViolation<Object> violation = mock(ConstraintViolation.class);
            when(validator.validate(eq(target), any(Class[].class))).thenReturn(Set.of(violation));

            assertThatThrownBy(() -> ValidationUtils.validate(validator, target))
                    .isInstanceOf(ConstraintViolationException.class);
        }
    }
}
