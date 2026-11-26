package com.clucky.promotionloyaltyservice.dto;

import jakarta.validation.Constraint;
import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = ValidLocalizedText.Validator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidLocalizedText {
    String message() default "Localized text is invalid";
    Class<?>[] groups() default {};
    Class<? extends Payload>[] payload() default {};
    boolean required() default false;
    int max() default Integer.MAX_VALUE;

    class Validator implements ConstraintValidator<ValidLocalizedText, LocalizedText> {
        private boolean required;
        private int max;

        @Override
        public void initialize(ValidLocalizedText annotation) {
            this.required = annotation.required();
            this.max = annotation.max();
        }

        @Override
        public boolean isValid(LocalizedText value, ConstraintValidatorContext context) {
            if (value == null) return !required;
            if (required && !value.hasVi()) return false;
            if (max < Integer.MAX_VALUE) {
                if (value.vi() != null && value.vi().length() > max) return false;
                if (value.en() != null && value.en().length() > max) return false;
            }
            return true;
        }
    }
}
