package com.unisence.iot.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class NoInvisibleUnicodeValidator implements ConstraintValidator<NoInvisibleUnicode, CharSequence> {

    @Override
    public boolean isValid(CharSequence value, ConstraintValidatorContext context) {
        if (value == null) {
            return true;
        }
        return !InvisibleUnicode.contains(value);
    }
}
