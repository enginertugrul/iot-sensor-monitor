package com.enginertugrul.iotsensormonitor.service.notification;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.text.MessageFormat;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;



class NotificationLocalizationTest {

    private static final Map<String,Integer> ARGUMENT_COUNTS = Map.ofEntries(
            Map.entry("email.alert.temperature.subject",1),
            Map.entry("email.alert.temperature.body",11),
            Map.entry("email.alert.humidity.subject",1),
            Map.entry("email.alert.humidity.body",11),
            Map.entry("email.alert.motion.subject",1),
            Map.entry("email.alert.motion.body",8),
            Map.entry("email.verification.subject",0),
            Map.entry("email.verification.body",2),
            Map.entry("email.passwordRecovery.subject",0),
            Map.entry("email.passwordRecovery.body",2),
            Map.entry("comparisonOperator.ABOVE",0),
            Map.entry("comparisonOperator.BELOW",0),
            Map.entry("alertEventType.MOTION_DETECTED",0),
            Map.entry("measurement.relativeHumidity.symbol",0)
    );



    @Test
    void englishAndTurkishBundlesDeclareEveryNotificationKeyWithoutFallback() throws IOException {
        Properties english = load("messages.properties");
        Properties turkish = load("messages_tr.properties");

        assertThat(notificationKeys(english)).containsExactlyInAnyOrderElementsOf(ARGUMENT_COUNTS.keySet());
        assertThat(notificationKeys(turkish)).containsExactlyInAnyOrderElementsOf(ARGUMENT_COUNTS.keySet());

        for (String key : ARGUMENT_COUNTS.keySet()) {
            assertThat(english.getProperty(key)).as("English value for %s",key).isNotBlank().doesNotContain("\uFFFD");
            assertThat(turkish.getProperty(key)).as("Turkish value for %s",key).isNotBlank().doesNotContain("\uFFFD");
        }
    }



    @ParameterizedTest
    @CsvSource({"messages.properties,en","messages_tr.properties,tr"})
    void notificationTemplatesConsumeAllExpectedArguments(String resource,String languageTag) throws IOException {
        Properties properties = load(resource);
        Locale locale = Locale.forLanguageTag(languageTag);

        for (Map.Entry<String,Integer> entry : ARGUMENT_COUNTS.entrySet()) {
            String key = entry.getKey();
            String pattern = properties.getProperty(key);
            assertThat(pattern).as("%s in %s",key,resource).isNotBlank();

            MessageFormat formatter = new MessageFormat(pattern,locale);
            assertThat(formatter.getFormatsByArgumentIndex())
                    .as("Argument count for %s in %s",key,resource)
                    .hasSize(entry.getValue());

            Object[] arguments = new Object[entry.getValue()];
            for (int index = 0; index < arguments.length; index++) {
                arguments[index] = "[argument-" + index + "]";
            }

            String rendered = formatter.format(arguments);

            for (Object argument : arguments) {
                assertThat(rendered).as("%s in %s",key,resource).contains(argument.toString());
            }

            assertThat(rendered).as("%s in %s",key,resource).doesNotContainPattern("\\{\\d+(?:,[^}]*)?\\}");

            if (key.endsWith(".subject")) {
                assertThat(rendered).as("%s in %s",key,resource).doesNotContain("\n","\r");
            }
        }
    }



    private static Set<String> notificationKeys(Properties properties) {
        return properties.stringPropertyNames().stream()
                .filter(key -> key.startsWith("email.") || key.startsWith("comparisonOperator.")
                        || key.equals("alertEventType.MOTION_DETECTED") || key.equals("measurement.relativeHumidity.symbol"))
                .collect(Collectors.toSet());
    }



    private static Properties load(String resource) throws IOException {
        try (InputStream input = NotificationLocalizationTest.class.getResourceAsStream("/" + resource)) {
            assertThat(input).as("Classpath resource %s",resource).isNotNull();
            Properties properties = new Properties();
            properties.load(new InputStreamReader(input,StandardCharsets.UTF_8));
            return properties;
        }
    }
}