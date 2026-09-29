package com.enginertugrul.iotsensormonitor.controller;

import com.enginertugrul.iotsensormonitor.config.SecurityConfig;
import com.enginertugrul.iotsensormonitor.controller.advice.ApiExceptionHandler;
import com.enginertugrul.iotsensormonitor.controller.advice.ApiMediaTypeExceptionHandler;
import com.enginertugrul.iotsensormonitor.security.EmailVerificationAuthenticationSuccessHandler;
import com.enginertugrul.iotsensormonitor.service.reading.ingestion.SensorReadingIngestionService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;



@WebMvcTest(controllers = SensorIngestionController.class,properties = "spring.config.location=classpath:/application-test.properties")
@ActiveProfiles("test")
@Import({SecurityConfig.class,EmailVerificationAuthenticationSuccessHandler.class,ApiExceptionHandler.class,ApiMediaTypeExceptionHandler.class})
class SensorIngestionControllerWebMvcTest {

    private static final String SENSOR_TOKEN = "test+token/with=reserved&characters%";
    private static final Instant RECORDED_AT = Instant.parse("2026-01-15T11:59:00.123Z");
    private static final String EPOCH_MILLIS = Long.toString(RECORDED_AT.toEpochMilli());

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private SensorReadingIngestionService ingestionService;



    @ParameterizedTest
    @ValueSource(doubles = {-273.15,-12.5,21.75})
    void acceptsTemperatureFormsWithoutLoginOrCsrfAndPreservesEpochMilliseconds(double value) throws Exception {
        submit("/readings/temperature",validForm("celsiusValue",Double.toString(value)))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(ingestionService).ingestTemperature(SENSOR_TOKEN,value,RECORDED_AT);
        verifyNoMoreInteractions(ingestionService);
    }



    @ParameterizedTest
    @ValueSource(doubles = {0.0,58.75,100.0})
    void acceptsHumidityFormsWithoutLoginOrCsrfAndPreservesEpochMilliseconds(double value) throws Exception {
        submit("/readings/humidity",validForm("humidityPercentage",Double.toString(value)))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(ingestionService).ingestHumidity(SENSOR_TOKEN,value,RECORDED_AT);
        verifyNoMoreInteractions(ingestionService);
    }



    @ParameterizedTest
    @ValueSource(booleans = {true,false})
    void acceptsBothMotionStatesWithoutLoginOrCsrfAndPreservesEpochMilliseconds(boolean value) throws Exception {
        submit("/readings/motion",validForm("motionDetected",Boolean.toString(value)))
                .andExpect(status().isOk())
                .andExpect(content().string(""));

        verify(ingestionService).ingestMotion(SENSOR_TOKEN,value,RECORDED_AT);
        verifyNoMoreInteractions(ingestionService);
    }



    @ParameterizedTest
    @MethodSource("invalidCommonFields")
    void rejectsMissingOrInvalidFieldsBeforeInvokingIngestion(Endpoint endpoint,String field,String invalidValue) throws Exception {
        Map<String,String> form = validForm(endpoint.valueField(),endpoint.validValue());

        if (invalidValue == null) {
            form.remove(field);
        } else {
            form.put(field,invalidValue);
        }

        submit(endpoint.path(),form)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(ingestionService);
    }



    @ParameterizedTest
    @CsvSource({
            "/readings/temperature,celsiusValue,-273.16",
            "/readings/temperature,celsiusValue,NaN",
            "/readings/temperature,celsiusValue,Infinity",
            "/readings/temperature,celsiusValue,-Infinity",
            "/readings/temperature,celsiusValue,1e309",
            "/readings/temperature,celsiusValue,warm",
            "/readings/humidity,humidityPercentage,-0.01",
            "/readings/humidity,humidityPercentage,100.01",
            "/readings/humidity,humidityPercentage,NaN",
            "/readings/humidity,humidityPercentage,Infinity",
            "/readings/humidity,humidityPercentage,-Infinity",
            "/readings/humidity,humidityPercentage,wet",
            "/readings/motion,motionDetected,sometimes"
    })
    void rejectsInvalidMeasurementValuesBeforeInvokingIngestion(String path,String field,String invalidValue) throws Exception {
        submit(path,validForm(field,invalidValue))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        verifyNoInteractions(ingestionService);
    }



    @ParameterizedTest
    @MethodSource("endpoints")
    void rejectsJsonRequestBodies(Endpoint endpoint) throws Exception {
        String body = "{\"sensorToken\":\"test-token\",\"" + endpoint.valueField() + "\":"
                + endpoint.validValue() + ",\"recordedAt\":" + EPOCH_MILLIS + "}";

        mockMvc.perform(post(endpoint.path())
                        .contentType(MediaType.APPLICATION_JSON)
                        .accept(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));

        verifyNoInteractions(ingestionService);
    }



    private ResultActions submit(String path,Map<String,String> form) throws Exception {
        MockHttpServletRequestBuilder request = post(path)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .characterEncoding(StandardCharsets.UTF_8)
                .accept(MediaType.APPLICATION_JSON);

        form.forEach((name,value) -> request.formField(name,value));

        return mockMvc.perform(request);
    }



    private static Map<String,String> validForm(String valueField,String value) {
        Map<String,String> form = new LinkedHashMap<>();
        form.put("sensorToken",SENSOR_TOKEN);
        form.put(valueField,value);
        form.put("recordedAt",EPOCH_MILLIS);
        return form;
    }



    private static Stream<Arguments> invalidCommonFields() {
        return endpoints().flatMap(endpoint -> Stream.of(
                arguments(endpoint,"sensorToken",null),
                arguments(endpoint,"sensorToken",""),
                arguments(endpoint,"sensorToken"," \t "),
                arguments(endpoint,"sensorToken","t".repeat(257)),
                arguments(endpoint,endpoint.valueField(),null),
                arguments(endpoint,endpoint.valueField(),""),
                arguments(endpoint,"recordedAt",null),
                arguments(endpoint,"recordedAt",""),
                arguments(endpoint,"recordedAt","not-an-instant"),
                arguments(endpoint,"recordedAt",EPOCH_MILLIS + ".5"),
                arguments(endpoint,"recordedAt","9223372036854775808")
        ));
    }



    private static Stream<Endpoint> endpoints() {
        return Stream.of(
                new Endpoint("/readings/temperature","celsiusValue","21.5"),
                new Endpoint("/readings/humidity","humidityPercentage","58.75"),
                new Endpoint("/readings/motion","motionDetected","true")
        );
    }



    private record Endpoint(String path,String valueField,String validValue) {
    }
}