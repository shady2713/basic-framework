package com.basicframework.framework.common.validation;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class CommonConstraintValidatorTest {

    @Test
    void stringValidators_delegateNonEmptyValuesAndLeaveRequirednessToBeanValidation() {
        assertThat(new UsernameValidator().isValid(null, null)).isTrue();
        assertThat(new UsernameValidator().isValid("admin", null)).isTrue();
        assertThat(new UsernameValidator().isValid("abc", null)).isFalse();

        assertThat(new PasswordValidator().isValid("", null)).isTrue();
        assertThat(new PasswordValidator().isValid("Abcd12", null)).isTrue();
        assertThat(new PasswordValidator().isValid("password", null)).isFalse();

        assertThat(new EmailExValidator().isValid("", null)).isTrue();
        assertThat(new EmailExValidator().isValid("user@example.com", null)).isTrue();
        assertThat(new EmailExValidator().isValid("user@invalid", null)).isFalse();

        assertThat(new MobileValidator().isValid(null, null)).isTrue();
        assertThat(new MobileValidator().isValid("13812345678", null)).isTrue();
        assertThat(new MobileValidator().isValid("23812345678", null)).isFalse();
    }

    @Test
    void percentValidator_supportsNumbersAndTextAndRejectsOtherTypes() {
        PercentValidator validator = new PercentValidator();

        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid(new BigDecimal("99.99"), null)).isTrue();
        assertThat(validator.isValid(new BigDecimal("100.001"), null)).isFalse();
        assertThat(validator.isValid("100", null)).isTrue();
        assertThat(validator.isValid("101", null)).isFalse();
        assertThat(validator.isValid(new Object(), null)).isFalse();
    }

    @Test
    void quantityValidator_acceptsOnlyWholeNonNegativeNumbers() {
        QuantityValidator validator = new QuantityValidator();

        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid(100, null)).isTrue();
        assertThat(validator.isValid(new BigDecimal("100.5"), null)).isFalse();
        assertThat(validator.isValid("100", null)).isFalse();
    }
}
