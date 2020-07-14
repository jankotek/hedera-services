/*
 * (c) 2016-2020 Swirlds, Inc.
 *
 * This software is the confidential and proprietary information of
 * Swirlds, Inc. ("Confidential Information"). You shall not
 * disclose such Confidential Information and shall use it only in
 * accordance with the terms of the license agreement you entered into
 * with Swirlds.
 *
 * SWIRLDS MAKES NO REPRESENTATIONS OR WARRANTIES ABOUT THE SUITABILITY OF
 * THE SOFTWARE, EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED
 * TO THE IMPLIED WARRANTIES OF MERCHANTABILITY, FITNESS FOR A
 * PARTICULAR PURPOSE, OR NON-INFRINGEMENT. SWIRLDS SHALL NOT BE LIABLE FOR
 * ANY DAMAGES SUFFERED BY LICENSEE AS A RESULT OF USING, MODIFYING OR
 * DISTRIBUTING THIS SOFTWARE OR ITS DERIVATIVES.
 */

package com.swirlds.regression.testRunners;

import com.swirlds.regression.Experiment;
import com.swirlds.regression.jsonConfigs.TestConfig;

import java.util.LinkedList;
import java.util.List;
import java.util.function.BooleanSupplier;

import static com.swirlds.regression.RegressionUtilities.MILLIS;

public class HederaServiceRun implements TestRun {
	@Override
	public void runTest(TestConfig testConfig, Experiment experiment) {
		experiment.startServicesRegression();

		// sleep through the rest of the test
		long testDuration = testConfig.getDuration() * MILLIS;
		List<BooleanSupplier> checkerList = new LinkedList<>();
		experiment.sleepThroughExperimentWithCheckerList(testDuration,
				checkerList);
	}

}
