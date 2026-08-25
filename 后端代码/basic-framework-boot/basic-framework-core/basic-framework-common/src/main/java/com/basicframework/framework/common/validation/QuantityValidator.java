package com.basicframework.framework.common.validation;

import com.basicframework.framework.common.util.validation.ValidationUtils;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public class QuantityValidator implements ConstraintValidator<Quantity, Object> {

    @Override
    public boolean isValid(Object value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        if (value instanceof Number number) {
            return ValidationUtils.isQuantity(number);
        }
        return false;
    }
}
