package fr.enimaloc.blinder.api;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import fr.enimaloc.blinder.support.TestJson;
import org.slf4j.LoggerFactory;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import org.springframework.web.servlet.ModelAndView;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;

/** Filet de sécurité sous /_/api/ : problème JSON, réponse déjà engagée laissée au conteneur. */
class ApiExceptionResolverTest {

    private final ApiExceptionResolver resolver = new ApiExceptionResolver(new ProblemWriter(TestJson.mapper()));

    private MockHttpServletRequest apiRequest() {
        return new MockHttpServletRequest("GET", "/_/api/v1/links");
    }

    @Test
    void unexpectedExceptionBecomesAnInternalProblem() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ModelAndView result = resolver.resolveException(apiRequest(), response, null, new IllegalStateException("boom"));

        assertThat(result).isNotNull();
        assertThat(response.getStatus()).isEqualTo(500);
        assertThat(response.getContentType()).startsWith("application/problem+json");
    }

    @Test
    void errorResponseKeepsItsStatus() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver.resolveException(apiRequest(), response, null, new ResponseStatusException(HttpStatus.NOT_FOUND));

        assertThat(response.getStatus()).isEqualTo(404);
        assertThat(response.getContentAsString()).contains("\"status\":404");
    }

    @Test
    void serverErrorResponseIsAlsoAProblem() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        resolver.resolveException(apiRequest(), response, null,
                new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE));

        assertThat(response.getStatus()).isEqualTo(503);
    }

    @Test
    void serverErrorResponseIsLoggedAsError() {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionResolver.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            resolver.resolveException(apiRequest(), new MockHttpServletResponse(), null,
                    new ResponseStatusException(HttpStatus.BAD_GATEWAY));
            resolver.resolveException(apiRequest(), new MockHttpServletResponse(), null,
                    new ResponseStatusException(HttpStatus.NOT_FOUND));
        } finally {
            logger.detachAppender(appender);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.ERROR);
    }

    private MockHttpServletResponse committedResponse() throws IOException {
        MockHttpServletResponse response = new MockHttpServletResponse();
        response.getWriter().write("partiel");
        response.flushBuffer();
        assertThat(response.isCommitted()).isTrue();
        return response;
    }

    @Test
    void committedResponseIsLeftUntouchedAndTheExceptionReachesTheContainer() throws IOException {
        MockHttpServletResponse response = committedResponse();

        ModelAndView result = resolver.resolveException(apiRequest(), response, null, new IllegalStateException("boom"));

        assertThat(result).isNull();
        assertThat(response.getContentAsString()).isEqualTo("partiel");
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentType()).isNull();
    }

    @Test
    void committedResponseLogsAWarningExceptForClientDisconnects() throws IOException {
        Logger logger = (Logger) LoggerFactory.getLogger(ApiExceptionResolver.class);
        Level previous = logger.getLevel();
        logger.setLevel(Level.DEBUG);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            assertThat(resolver.resolveException(apiRequest(), committedResponse(), null,
                    new IOException("Broken pipe"))).isNull();
            resolver.resolveException(apiRequest(), committedResponse(), null, new IllegalStateException("boom"));
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previous);
        }

        assertThat(appender.list).extracting(ILoggingEvent::getLevel).containsExactly(Level.DEBUG, Level.WARN);
        assertThat(appender.list.get(0).getThrowableProxy()).isNull();
    }

    @Test
    void requestsOutsideTheApiAreIgnored() {
        MockHttpServletResponse response = new MockHttpServletResponse();

        ModelAndView result = resolver.resolveException(new MockHttpServletRequest("GET", "/admin"), response, null,
                new IllegalStateException("boom"));

        assertThat(result).isNull();
    }
}
