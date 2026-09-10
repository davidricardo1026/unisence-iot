package com.unisence.iot.common.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.*;

@Documented
@Constraint(validatedBy = NoInvisibleUnicodeValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface NoInvisibleUnicode {

    String message() default "不能包含不可见 Unicode 字符";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
