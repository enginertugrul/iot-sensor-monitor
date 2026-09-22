package com.enginertugrul.iotsensormonitor.support.web;

import com.enginertugrul.iotsensormonitor.dto.auth.PasswordResetForm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.support.DefaultMessageSourceResolvable;
import org.springframework.validation.BeanPropertyBindingResult;
import org.springframework.validation.BindingResult;
import org.springframework.validation.FieldError;
import org.springframework.validation.ObjectError;

import static org.assertj.core.api.Assertions.assertThat;

class BindingResultSanitizerTest {

    private static final String SOURCE_NAME = "submittedForm";
    private static final String TARGET_NAME = "resetForm";
    private static final String PASSWORD = "submitted-secret-password";
    private static final String CONFIRMATION = "submitted-secret-confirmation";
    private static final String RAW_CODE = "01234567";



    @Test
    void createsIndependentEmptyBindingResultForTheProvidedTargetAndObjectName() {
        PasswordResetForm submitted = submittedForm();
        PasswordResetForm sanitizedTarget = new PasswordResetForm();
        BeanPropertyBindingResult source = new BeanPropertyBindingResult(submitted,SOURCE_NAME);

        BindingResult result = BindingResultSanitizer.copyWithoutRejectedValues(sanitizedTarget,TARGET_NAME,source);

        assertThat(result).isNotSameAs(source);
        assertThat(result.getTarget()).isSameAs(sanitizedTarget);
        assertThat(result.getObjectName()).isEqualTo(TARGET_NAME);
        assertThat(result.hasErrors()).isFalse();
        assertThat(result.getModel())
                .hasSize(2)
                .containsEntry(TARGET_NAME,sanitizedTarget)
                .containsEntry(BindingResult.MODEL_KEY_PREFIX + TARGET_NAME,result);

        assertThat(source.getTarget()).isSameAs(submitted);
        assertThat(source.getObjectName()).isEqualTo(SOURCE_NAME);
        assertThat(source.hasErrors()).isFalse();
        assertThat(submitted.getPassword()).isEqualTo(PASSWORD);
    }



    @ParameterizedTest
    @ValueSource(booleans = {true,false})
    void removesRejectedFieldValueWhilePreservingMetadataAndBindingFailureFlag(boolean bindingFailure) {
        PasswordResetForm submitted = submittedForm();
        PasswordResetForm sanitizedTarget = new PasswordResetForm();
        BeanPropertyBindingResult source = new BeanPropertyBindingResult(submitted,SOURCE_NAME);
        String errorCode = bindingFailure ? "typeMismatch" : "Size";
        String[] codes = {errorCode + ".submittedForm.password",errorCode + ".password",errorCode};
        Object[] arguments = {new DefaultMessageSourceResolvable("password"),8,72};
        FieldError original = new FieldError(SOURCE_NAME,"password",PASSWORD,bindingFailure,codes,arguments,"Invalid password");
        IllegalArgumentException wrappedSource = new IllegalArgumentException("Rejected value: " + PASSWORD);
        original.wrap(wrappedSource);
        source.addError(original);

        BindingResult result = BindingResultSanitizer.copyWithoutRejectedValues(sanitizedTarget,TARGET_NAME,source);
        FieldError copied = result.getFieldError("password");

        assertThat(result.getErrorCount()).isEqualTo(1);
        assertThat(copied).isNotNull().isNotSameAs(original);
        assertMetadata(copied,original);
        assertThat(copied.getField()).isEqualTo("password");
        assertThat(copied.getRejectedValue()).isNull();
        assertThat(copied.isBindingFailure()).isEqualTo(bindingFailure);
        assertThat(copied.contains(IllegalArgumentException.class)).isFalse();
        assertThat(result.getFieldValue("password")).isNull();
        assertThat(result.getRawFieldValue("password")).isNull();
        assertThat(result.toString()).doesNotContain(PASSWORD);

        assertThat(source.getAllErrors()).containsExactly(original);
        assertThat(original.getObjectName()).isEqualTo(SOURCE_NAME);
        assertThat(original.getRejectedValue()).isEqualTo(PASSWORD);
        assertThat(original.contains(IllegalArgumentException.class)).isTrue();
        assertThat(submitted.getPassword()).isEqualTo(PASSWORD);
    }



    @Test
    void copiesGlobalErrorMetadataWithoutRetainingWrappedSource() {
        BeanPropertyBindingResult source = new BeanPropertyBindingResult(submittedForm(),SOURCE_NAME);
        String[] codes = {"PasswordMismatch.submittedForm","PasswordMismatch"};
        Object[] arguments = {new DefaultMessageSourceResolvable("confirmPassword")};
        ObjectError original = new ObjectError(SOURCE_NAME,codes,arguments,"Passwords do not match");
        original.wrap(new IllegalArgumentException("Rejected value: " + CONFIRMATION));
        source.addError(original);

        BindingResult result = BindingResultSanitizer.copyWithoutRejectedValues(new PasswordResetForm(),TARGET_NAME,source);
        ObjectError copied = result.getGlobalError();

        assertThat(result.getGlobalErrorCount()).isEqualTo(1);
        assertThat(result.getFieldErrorCount()).isZero();
        assertThat(copied).isNotNull().isNotSameAs(original);
        assertMetadata(copied,original);
        assertThat(copied.contains(IllegalArgumentException.class)).isFalse();
        assertThat(result.toString()).doesNotContain(CONFIRMATION);

        assertThat(source.getGlobalErrors()).containsExactly(original);
        assertThat(original.getObjectName()).isEqualTo(SOURCE_NAME);
        assertThat(original.contains(IllegalArgumentException.class)).isTrue();
    }



    @Test
    void preservesMixedErrorOrderAndMultipleErrorsForTheSameField() {
        BeanPropertyBindingResult source = new BeanPropertyBindingResult(submittedForm(),SOURCE_NAME);
        FieldError passwordLength = fieldError("password",PASSWORD,"Password length");
        ObjectError global = new ObjectError(SOURCE_NAME,"Account unavailable");
        FieldError codeFormat = fieldError("code",RAW_CODE,"Code format");
        FieldError passwordPolicy = fieldError("password",PASSWORD,"Password policy");
        FieldError confirmation = fieldError("confirmPassword",CONFIRMATION,"Password mismatch");
        source.addError(passwordLength);
        source.addError(global);
        source.addError(codeFormat);
        source.addError(passwordPolicy);
        source.addError(confirmation);

        BindingResult result = BindingResultSanitizer.copyWithoutRejectedValues(new PasswordResetForm(),TARGET_NAME,source);

        assertThat(result.getErrorCount()).isEqualTo(5);
        assertThat(result.getFieldErrorCount()).isEqualTo(4);
        assertThat(result.getGlobalErrorCount()).isEqualTo(1);
        assertThat(result.getFieldErrors("password")).hasSize(2);
        assertThat(result.getAllErrors()).extracting(ObjectError::getDefaultMessage)
                .containsExactly("Password length","Account unavailable","Code format","Password policy","Password mismatch");
        assertThat(result.getAllErrors()).allSatisfy(error -> assertThat(error.getObjectName()).isEqualTo(TARGET_NAME));
        assertThat(result.getFieldErrors()).allSatisfy(error -> assertThat(error.getRejectedValue()).isNull());

        for (String field : new String[]{"code","password","confirmPassword"}) {
            assertThat(result.getFieldValue(field)).isNull();
            assertThat(result.getRawFieldValue(field)).isNull();
        }

        for (int index = 0; index < source.getErrorCount(); index++) {
            assertThat(result.getAllErrors().get(index)).isNotSameAs(source.getAllErrors().get(index));
        }

        assertThat(result.toString()).doesNotContain(PASSWORD,CONFIRMATION,RAW_CODE);
        assertThat(source.getAllErrors()).containsExactly(passwordLength,global,codeFormat,passwordPolicy,confirmation);
        assertThat(passwordLength.getRejectedValue()).isEqualTo(PASSWORD);
        assertThat(codeFormat.getRejectedValue()).isEqualTo(RAW_CODE);
        assertThat(confirmation.getRejectedValue()).isEqualTo(CONFIRMATION);
    }



    @Test
    void preservesAbsentOptionalMetadataForFieldAndGlobalErrors() {
        BeanPropertyBindingResult source = new BeanPropertyBindingResult(submittedForm(),SOURCE_NAME);
        source.addError(new FieldError(SOURCE_NAME,"code",RAW_CODE,false,null,null,null));
        source.addError(new ObjectError(SOURCE_NAME,null,null,null));

        BindingResult result = BindingResultSanitizer.copyWithoutRejectedValues(new PasswordResetForm(),TARGET_NAME,source);

        assertThat(result.getErrorCount()).isEqualTo(2);
        assertThat(result.getFieldErrorCount()).isEqualTo(1);
        assertThat(result.getGlobalErrorCount()).isEqualTo(1);

        for (ObjectError error : result.getAllErrors()) {
            assertThat(error.getObjectName()).isEqualTo(TARGET_NAME);
            assertThat(error.getCodes()).isNull();
            assertThat(error.getArguments()).isNull();
            assertThat(error.getDefaultMessage()).isNull();
        }

        FieldError copied = result.getFieldError("code");
        assertThat(copied).isNotNull();
        assertThat(copied.getRejectedValue()).isNull();
        assertThat(copied.isBindingFailure()).isFalse();
    }



    @Test
    void copiedAndSourceErrorCollectionsCanChangeIndependently() {
        BeanPropertyBindingResult source = new BeanPropertyBindingResult(submittedForm(),SOURCE_NAME);
        source.addError(fieldError("password",PASSWORD,"Initial password error"));

        BindingResult result = BindingResultSanitizer.copyWithoutRejectedValues(new PasswordResetForm(),TARGET_NAME,source);

        source.addError(fieldError("code",RAW_CODE,"Later source error"));

        assertThat(source.getErrorCount()).isEqualTo(2);
        assertThat(result.getErrorCount()).isEqualTo(1);
        assertThat(result.getFieldErrors("code")).isEmpty();

        result.addError(new ObjectError(TARGET_NAME,"Later copied error"));

        assertThat(result.getGlobalErrorCount()).isEqualTo(1);
        assertThat(source.getGlobalErrors()).isEmpty();
        assertThat(source.getErrorCount()).isEqualTo(2);
    }



    private static PasswordResetForm submittedForm() {
        PasswordResetForm form = new PasswordResetForm();
        form.setCode(RAW_CODE);
        form.setPassword(PASSWORD);
        form.setConfirmPassword(CONFIRMATION);
        return form;
    }



    private static FieldError fieldError(String field,Object rejectedValue,String message) {
        return new FieldError(SOURCE_NAME,field,rejectedValue,false,new String[]{"Invalid." + field,"Invalid"},null,message);
    }



    private static void assertMetadata(ObjectError copied,ObjectError original) {
        assertThat(copied.getObjectName()).isEqualTo(TARGET_NAME);
        assertThat(copied.getCodes()).containsExactly(original.getCodes());
        assertThat(copied.getArguments()).containsExactly(original.getArguments());
        assertThat(copied.getDefaultMessage()).isEqualTo(original.getDefaultMessage());
    }
}