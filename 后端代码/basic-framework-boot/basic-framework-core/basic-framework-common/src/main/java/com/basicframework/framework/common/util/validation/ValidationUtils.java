package com.basicframework.framework.common.util.validation;

import cn.hutool.core.collection.CollUtil;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Validator;
import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.util.StringUtils;

public class ValidationUtils {

    private static final Pattern PATTERN_MOBILE = Pattern.compile("^1[3-9]\\d{9}$");
    private static final Pattern PATTERN_USERNAME = Pattern.compile("^[a-z][a-z0-9_]{3,29}$");
    private static final Pattern PATTERN_PASSWORD =
            Pattern.compile("^(?=.*[a-z])(?=.*[A-Z])(?=.*\\d)[A-Za-z\\d]{6,16}$");
    private static final Pattern PATTERN_EMAIL = Pattern.compile("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");
    private static final Pattern PATTERN_PERCENT = Pattern.compile("^(100(?:\\.0{1,2})?|\\d{1,2}(?:\\.\\d{1,2})?)$");

    public static boolean isMobile(String mobile) {
        return StringUtils.hasText(mobile) && PATTERN_MOBILE.matcher(mobile).matches();
    }

    public static boolean isUsername(String username) {
        String normalized = normalizeUsername(username);
        return StringUtils.hasText(normalized)
                && PATTERN_USERNAME.matcher(normalized).matches();
    }

    public static String normalizeUsername(String username) {
        if (username == null) {
            return null;
        }
        return Normalizer.normalize(username.trim(), Normalizer.Form.NFC).toLowerCase(Locale.ROOT);
    }

    public static boolean isPassword(String password) {
        return StringUtils.hasText(password)
                && PATTERN_PASSWORD.matcher(password).matches();
    }

    public static boolean isEmail(String email) {
        return StringUtils.hasText(email) && PATTERN_EMAIL.matcher(email).matches();
    }

    public static boolean isPercent(String percent) {
        return StringUtils.hasText(percent) && PATTERN_PERCENT.matcher(percent).matches();
    }

    public static boolean isPercent(Number percent) {
        if (percent == null) {
            return false;
        }
        return isPercent(stripTrailingZeros(new BigDecimal(percent.toString())));
    }

    public static boolean isQuantity(Number quantity) {
        if (quantity == null) {
            return false;
        }
        BigDecimal value = new BigDecimal(quantity.toString());
        return value.compareTo(BigDecimal.ZERO) >= 0
                && value.stripTrailingZeros().scale() <= 0;
    }

    public static void validate(Validator validator, Object object, Class<?>... groups) {
        Set<ConstraintViolation<Object>> constraintViolations = validator.validate(object, groups);
        if (CollUtil.isNotEmpty(constraintViolations)) {
            throw new ConstraintViolationException(constraintViolations);
        }
    }

    private static String stripTrailingZeros(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
