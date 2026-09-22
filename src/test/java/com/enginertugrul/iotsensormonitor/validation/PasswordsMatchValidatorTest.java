package com.enginertugrul.iotsensormonitor.validation;

import jakarta.validation.ConstraintValidatorContext;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder;
import jakarta.validation.ConstraintValidatorContext.ConstraintViolationBuilder.NodeBuilderCustomizableContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class PasswordsMatchValidatorTest {

    private final PasswordsMatchValidator validator = new PasswordsMatchValidator();



    @Test
    void acceptsMissingFormWithoutAddingViolations() {
        ConstraintValidatorContext context = mock(ConstraintValidatorContext.class);

        assertThat(validator.isValid(null,context)).isTrue();

        verifyNoInteractions(context);
    }



    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ","\t\r\n","\u2003"})
    void leavesMissingPasswordsToRequiredFieldConstraints(String value) {
        ConstraintValidatorContext context = mock(ConstraintValidatorContext.class);

        assertThat(validator.isValid(new PasswordPair(value,"password123"),context)).isTrue();
        assertThat(validator.isValid(new PasswordPair("password123",value),context)).isTrue();
        assertThat(validator.isValid(new PasswordPair(value,value),context)).isTrue();

        verifyNoInteractions(context);
    }



    @ParameterizedTest
    @ValueSource(strings = {"password123"," Password 123 ","Şifre123!"})
    void acceptsExactlyMatchingPasswordsWithoutAddingViolations(String password) {
        ConstraintValidatorContext context = mock(ConstraintValidatorContext.class);

        assertThat(validator.isValid(new PasswordPair(password,password),context)).isTrue();

        verifyNoInteractions(context);
    }



    @ParameterizedTest
    @ValueSource(strings = {"Password123"," password123","password123 ","different-password"})
    void attachesMismatchToConfirmPasswordUsingConfiguredMessage(String confirmation) {
        ConstraintValidatorContext context = mock(ConstraintValidatorContext.class);
        ConstraintViolationBuilder builder = mock(ConstraintViolationBuilder.class);
        NodeBuilderCustomizableContext propertyNode = mock(NodeBuilderCustomizableContext.class);
        String messageTemplate = "{test.passwordMismatch}";

        when(context.getDefaultConstraintMessageTemplate()).thenReturn(messageTemplate);
        when(context.buildConstraintViolationWithTemplate(messageTemplate)).thenReturn(builder);
        when(builder.addPropertyNode("confirmPassword")).thenReturn(propertyNode);
        when(propertyNode.addConstraintViolation()).thenReturn(context);

        assertThat(validator.isValid(new PasswordPair("password123",confirmation),context)).isFalse();

        verify(context).disableDefaultConstraintViolation();
        verify(context).buildConstraintViolationWithTemplate(messageTemplate);
        verify(builder).addPropertyNode("confirmPassword");
        verify(propertyNode).addConstraintViolation();
    }



    private record PasswordPair(String password,String confirmPassword) implements PasswordConfirmation {

        @Override
        public String getPassword() {
            return password;
        }

        @Override
        public String getConfirmPassword() {
            return confirmPassword;
        }
    }
}