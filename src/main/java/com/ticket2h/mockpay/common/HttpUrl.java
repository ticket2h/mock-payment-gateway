package com.ticket2h.mockpay.common;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.net.URI;
import java.net.URISyntaxException;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

/** URL tuyệt đối {@code http://} hoặc {@code https://} có host. {@code null} được coi là hợp lệ. */
@Documented
@Target({ ElementType.FIELD, ElementType.PARAMETER, ElementType.RECORD_COMPONENT, ElementType.METHOD })
@Retention(RetentionPolicy.RUNTIME)
@Constraint(validatedBy = HttpUrl.Validator.class)
public @interface HttpUrl {

    String message() default "must be an absolute http(s) URL";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

    class Validator implements ConstraintValidator<HttpUrl, String> {

        @Override
        public boolean isValid(String value, ConstraintValidatorContext context) {
            if (value == null) {
                return true;
            }
            try {
                URI uri = new URI(value);
                String scheme = uri.getScheme();
                return uri.isAbsolute() && uri.getHost() != null
                        && ("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme));
            }
            catch (URISyntaxException ex) {
                return false;
            }
        }
    }
}
