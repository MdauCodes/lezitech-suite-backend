package com.lezitech.suite.notify;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/** Sends one plain email. Chosen by EMAIL_PROVIDER: off, log (local), brevo or resend. */
public interface EmailSender {

    record Email(String to, String toName, String subject, String text, String html) {
    }

    void send(Email email);

    String name();

    /** Writes the email to the log instead of sending it (local runs and tests). Keeps the last ones for tests. */
    final class LogSender implements EmailSender {
        private static final Logger log = LoggerFactory.getLogger(LogSender.class);
        public final List<Email> sent = new CopyOnWriteArrayList<>();

        @Override
        public void send(Email e) {
            sent.add(e);
            log.info("Email (not sent, EMAIL_PROVIDER=log) to {}: {}\n{}", e.to(), e.subject(), e.text());
        }

        @Override
        public String name() {
            return "log";
        }
    }

    /** Brevo transactional email API (api.brevo.com/v3/smtp/email). */
    final class BrevoSender extends HttpSender {
        public BrevoSender(String apiKey, String from, String fromName, ObjectMapper json) {
            super(apiKey, from, fromName, json);
        }

        @Override
        public void send(Email e) {
            post("https://api.brevo.com/v3/smtp/email", Map.of("api-key", apiKey), Map.of(
                    "sender", Map.of("email", from, "name", fromName),
                    "to", List.of(Map.of("email", e.to(), "name", e.toName() == null ? e.to() : e.toName())),
                    "subject", e.subject(), "textContent", e.text(), "htmlContent", e.html()));
        }

        @Override
        public String name() {
            return "brevo";
        }
    }

    /** Resend email API (api.resend.com/emails). */
    final class ResendSender extends HttpSender {
        public ResendSender(String apiKey, String from, String fromName, ObjectMapper json) {
            super(apiKey, from, fromName, json);
        }

        @Override
        public void send(Email e) {
            post("https://api.resend.com/emails", Map.of("Authorization", "Bearer " + apiKey), Map.of(
                    "from", fromName + " <" + from + ">", "to", List.of(e.to()),
                    "subject", e.subject(), "text", e.text(), "html", e.html()));
        }

        @Override
        public String name() {
            return "resend";
        }
    }

    abstract class HttpSender implements EmailSender {
        protected final String apiKey;
        protected final String from;
        protected final String fromName;
        private final ObjectMapper json;
        private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        HttpSender(String apiKey, String from, String fromName, ObjectMapper json) {
            this.apiKey = apiKey;
            this.from = from;
            this.fromName = fromName;
            this.json = json;
        }

        protected void post(String url, Map<String, String> headers, Map<String, Object> body) {
            try {
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20))
                        .header("Content-Type", "application/json").header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body)));
                headers.forEach(b::header);
                HttpResponse<String> res = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
                if (res.statusCode() >= 300) {
                    throw new IllegalStateException(name() + " answered " + res.statusCode() + ": " + res.body());
                }
            } catch (IllegalStateException e) {
                throw e;
            } catch (Exception e) {
                throw new IllegalStateException("Couldn't reach " + name() + ": " + e.getMessage(), e);
            }
        }
    }
}
