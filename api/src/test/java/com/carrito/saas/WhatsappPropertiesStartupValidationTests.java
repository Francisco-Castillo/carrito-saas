package com.carrito.saas;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;

import com.carrito.saas.service.config.WhatsappProperties;

/**
 * Startup-lifecycle contract of {@link WhatsappProperties} — follow-up 21 of
 * {@code odd/tasks/whatsapp-inbound.md} ("Re-verificación tras el cierre de
 * F1/F2/F4", open gap 21).
 *
 * <p>The four tests in {@code WhatsappWebhookContractTests} call
 * {@code validate()} DIRECTLY, so they prove the method throws and prove
 * nothing about whether the {@code @PostConstruct} wiring ever invokes it:
 * deleting the annotation keeps them green while the application silently
 * boots with a blank secret (the original F1 defect — a 500 per signed POST,
 * which tells Meta to retry forever). These tests therefore assert the
 * CONTEXT, not the method: the context must REFUSE to start when a credential
 * is blank, naming the offending property, and must START when both are
 * valid.</p>
 *
 * <p>Isolated context: an {@link ApplicationContextRunner} with NO component
 * scan, no database and no web server. {@code WhatsappProperties} is
 * registered through {@code @EnableConfigurationProperties} — the mechanism
 * that actually binds {@code @ConfigurationProperties} values (and runs
 * {@code @PostConstruct} AFTER binding, exactly as in the real application) —
 * so this pins the bean's own lifecycle and does not depend on the GLOBAL
 * scanned-{@code @Component} behaviour noted in open gap 20.</p>
 *
 * <p>Sensitivity is by construction, not by accident: the blank cases set the
 * properties EXPLICITLY EMPTY, which overrides the non-blank
 * {@code local-dev-*} field defaults. If binding never happens (or the
 * {@code @PostConstruct} disappears), the defaults survive, the context
 * starts, and the blank cases FAIL — a green run here means the wiring works,
 * not that the test was skipped by the defaults.</p>
 */
class WhatsappPropertiesStartupValidationTests {

	/**
	 * Registers the properties bean exactly the way the binding infrastructure
	 * does: {@code @EnableConfigurationProperties} installs the
	 * {@code ConfigurationPropertiesBindingPostProcessor} (bind BEFORE
	 * {@code @PostConstruct}) and creates the bean from the annotated class.
	 */
	@Configuration(proxyBeanMethods = false)
	@EnableConfigurationProperties(WhatsappProperties.class)
	static class WhatsappPropertiesBindingConfig {
	}

	private final ApplicationContextRunner runner = new ApplicationContextRunner()
			.withUserConfiguration(WhatsappPropertiesBindingConfig.class);

	@Test
	void blankAppSecretRefusesToStartTheContextNamingTheProperty() {

		runner.withPropertyValues("whatsapp.app-secret=", "whatsapp.verify-token=valid-verify-token")
				.run(context -> {

					assertThat(context).hasFailed();

					assertThat(context.getStartupFailure())
							.hasRootCauseInstanceOf(IllegalStateException.class)
							.hasStackTraceContaining("whatsapp.app-secret must not be blank");
				});
	}

	@Test
	void blankVerifyTokenRefusesToStartTheContextNamingTheProperty() {

		runner.withPropertyValues("whatsapp.app-secret=valid-app-secret", "whatsapp.verify-token=")
				.run(context -> {

					assertThat(context).hasFailed();

					assertThat(context.getStartupFailure())
							.hasRootCauseInstanceOf(IllegalStateException.class)
							.hasStackTraceContaining("whatsapp.verify-token must not be blank");
				});
	}

	@Test
	void blankDefaultRegionRefusesToStartTheContextNamingTheProperty() {

		runner.withPropertyValues("whatsapp.app-secret=valid-app-secret",
				"whatsapp.verify-token=valid-verify-token", "whatsapp.default-region=")
				.run(context -> {

					assertThat(context).hasFailed();

					assertThat(context.getStartupFailure())
							.hasRootCauseInstanceOf(IllegalStateException.class)
							.hasStackTraceContaining("whatsapp.default-region must not be blank");
					});
	}

	/**
	 * A region code libphonenumber does not know would silently fail to
	 * canonicalize every LOCALLY stored phone, turning every message into a
	 * NotFound — the app must refuse to start instead.
	 */
	@Test
	void unsupportedDefaultRegionRefusesToStartTheContextNamingTheProperty() {

		runner.withPropertyValues("whatsapp.app-secret=valid-app-secret",
				"whatsapp.verify-token=valid-verify-token", "whatsapp.default-region=XX")
				.run(context -> {

					assertThat(context).hasFailed();

					assertThat(context.getStartupFailure())
							.hasRootCauseInstanceOf(IllegalStateException.class)
							.hasStackTraceContaining("whatsapp.default-region must be a supported");
					});
	}

	@Test
	void populatedCredentialsStartTheContextWithTheBoundValues() {

		runner.withPropertyValues("whatsapp.app-secret=valid-app-secret",
				"whatsapp.verify-token=valid-verify-token", "whatsapp.default-region=AR").run(context -> {

					assertThat(context).hasNotFailed();

					// The values must reach the bean through REAL binding: if
					// the binding mechanism were absent, the non-blank field
					// defaults would keep the context green while these
					// equality assertions fail.
					WhatsappProperties properties = context.getBean(WhatsappProperties.class);
					assertThat(properties.getAppSecret()).isEqualTo("valid-app-secret");
					assertThat(properties.getVerifyToken()).isEqualTo("valid-verify-token");
					assertThat(properties.getDefaultRegion()).isEqualTo("AR");
				});
	}
}
