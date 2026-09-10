package com.unisence.iot.admin.aop.annotation;

import java.lang.annotation.*;

@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface OperLog {
    String title() default "";

    BusinessType businessType() default BusinessType.OTHER;

    boolean saveRequestData() default true;

    boolean saveResponseData() default true;
}
