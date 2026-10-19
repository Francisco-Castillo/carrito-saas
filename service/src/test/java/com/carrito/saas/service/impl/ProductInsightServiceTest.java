package com.carrito.saas.service.impl;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import com.carrito.saas.dto.ProductInsight;

/**
 * Decision table of {@link ProductInsightService#calculate(Double, Double)}.
 *
 * <p>The class is pure: it maps a product's trend percentage and its predicted
 * growth percentage into a five-value insight. Nothing else in the repository
 * pins that mapping — the only caller is a {@code @SpringBootTest} path that
 * never asserts the insight — so these tests are the contract.</p>
 *
 * <p>The convention the table encodes is that "growing" means strictly
 * {@code prediction > 0}: a prediction of exactly zero counts as not growing
 * on both sides of the trend, which is why {@code CRITICAL} and
 * {@code UNSTABLE} accept {@code <= 0} while {@code OPPORTUNITY} and
 * {@code BOOST} require {@code > 0}.</p>
 *
 * <p>A trend of exactly zero returns {@code NEUTRAL} whatever the prediction
 * says, because every branch is strict on the trend sign. That is pinned here
 * as the current contract, not as an endorsement: whether a flat trend should
 * swallow a strongly negative prediction is a product decision, and changing
 * it belongs in its own change with its own reasoning.</p>
 *
 * <p>{@code NaN} inputs are deliberately not pinned: today every comparison
 * against {@code NaN} is false, so the method returns {@code NEUTRAL} by
 * accident rather than by decision. Locking that in would turn an undiscovered
 * gap into a requirement.</p>
 */
class ProductInsightServiceTest {

	private final ProductInsightService service = new ProductInsightService();

	@Test
	void missingTrendOrPredictionIsNeutral() {

		assertThat(service.calculate(null, 10.0))
				.as("a missing trend must not invent an insight")
				.isEqualTo(ProductInsight.NEUTRAL);

		assertThat(service.calculate(10.0, null))
				.as("a missing prediction must not invent an insight")
				.isEqualTo(ProductInsight.NEUTRAL);

		assertThat(service.calculate(null, null))
				.as("two missing inputs are still neutral")
				.isEqualTo(ProductInsight.NEUTRAL);
	}

	@Test
	void fallingTrendWithoutRecoveryIsCritical() {

		assertThat(service.calculate(-10.0, -5.0))
				.as("falling and predicted to keep falling")
				.isEqualTo(ProductInsight.CRITICAL);

		assertThat(service.calculate(-10.0, 0.0))
				.as("a flat prediction is not a recovery: growth means strictly greater than zero")
				.isEqualTo(ProductInsight.CRITICAL);
	}

	@Test
	void fallingTrendWithExpectedRecoveryIsOpportunity() {

		assertThat(service.calculate(-10.0, 0.1))
				.as("the smallest positive prediction counts as recovery, so the boundary is not a range")
				.isEqualTo(ProductInsight.OPPORTUNITY);

		assertThat(service.calculate(-10.0, 25.0))
				.as("falling now but predicted to grow")
				.isEqualTo(ProductInsight.OPPORTUNITY);
	}

	@Test
	void risingTrendWithGrowthIsBoost() {

		assertThat(service.calculate(10.0, 25.0))
				.as("rising and predicted to keep rising")
				.isEqualTo(ProductInsight.BOOST);

		assertThat(service.calculate(0.1, 0.1))
				.as("both sides positive is enough, however small")
				.isEqualTo(ProductInsight.BOOST);
	}

	@Test
	void risingTrendExpectedToFallIsUnstable() {

		assertThat(service.calculate(10.0, -5.0))
				.as("rising now but predicted to fall")
				.isEqualTo(ProductInsight.UNSTABLE);

		assertThat(service.calculate(10.0, 0.0))
				.as("a flat prediction does not count as growth on a rising trend either")
				.isEqualTo(ProductInsight.UNSTABLE);
	}

	@Test
	void flatTrendIsNeutralWhateverThePrediction() {

		assertThat(service.calculate(0.0, -50.0))
				.as("no trend means no insight, even with a strongly negative prediction")
				.isEqualTo(ProductInsight.NEUTRAL);

		assertThat(service.calculate(0.0, 50.0))
				.as("no trend means no insight, even with a strongly positive prediction")
				.isEqualTo(ProductInsight.NEUTRAL);

		assertThat(service.calculate(0.0, 0.0))
				.as("nothing moving anywhere is neutral")
				.isEqualTo(ProductInsight.NEUTRAL);

		assertThat(service.calculate(-0.0, 50.0))
				.as("negative zero compares equal to zero, so it is a flat trend too")
				.isEqualTo(ProductInsight.NEUTRAL);
	}
}
