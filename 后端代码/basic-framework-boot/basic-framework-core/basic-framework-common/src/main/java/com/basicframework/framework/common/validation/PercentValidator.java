package com.basicframework.framework.common.validation;

import com.basicframework.framework.common.util.validation.ValidationUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class PercentValidator implements ConstraintValidator<Percent, Object> {

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value instanceof Number number) {
            return ValidationUtils.isPercent(number);
        }
        if (value instanceof CharSequence charSequence) {
            return ValidationUtils.isPercent(charSequence.toString());
        }
        return false;
    }
}
