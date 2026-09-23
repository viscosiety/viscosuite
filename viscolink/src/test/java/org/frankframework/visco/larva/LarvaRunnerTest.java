/*
 * Copyright 2026 Viscosiety B.V.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.frankframework.visco.larva;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import org.frankframework.larva.Scenario;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link LarvaRunner#selectByPropertiesPath} -- the pure selection logic that
 * replaces a direct call to {@code TestRunStatus.getScenariosToRun(String)} on a
 * {@code .properties} execute path, because that method NPEs on a miss (see the method's
 * javadoc). No booted F!F {@link org.springframework.context.ApplicationContext} is needed for
 * this piece, so the rest of {@link LarvaRunner#larvaTool()} stays untested here (that needs a
 * real Larva run, exercised by the servlet task's fakes instead).
 */
class LarvaRunnerTest {

	private static Scenario scenario(File file) {
		return new Scenario(file, file.getName(), "desc", new Properties());
	}

	@Test
	void findsTheScenarioRegisteredUnderTheExactPath() {
		File file = new File("/root/OrdersIn/scenario01.properties");
		Scenario scenario = scenario(file);
		Map<Scenario.ID, Scenario> loaded = Map.of(scenario.getId(), scenario);

		List<Scenario> result = LarvaRunner.selectByPropertiesPath(loaded, file.getAbsolutePath());

		assertEquals(List.of(scenario), result);
	}

	@Test
	void returnsEmptyWhenTheFileWasNeverLoaded() {
		// Nothing was ever registered under this path -- the missing-file case from the
		// original bug (List.of(allScenarios.get(id)) would NPE here).
		Map<Scenario.ID, Scenario> loaded = Map.of();

		List<Scenario> result = LarvaRunner.selectByPropertiesPath(loaded, "/root/OrdersIn/missing.properties");

		assertTrue(result.isEmpty());
	}

	@Test
	void returnsEmptyWhenTheFileExistsButWasNotRegistered() {
		// The file is on disk (scenario.active=false, adapter.unstable=true, or no
		// scenario.description) but ScenarioLoader excluded it from allScenarios --
		// this is the case the review finding called out: the file exists, but the id
		// still misses the loaded map. List.of(allScenarios.get(id)) would NPE here too.
		Scenario other = scenario(new File("/root/OrdersIn/scenario01.properties"));
		Map<Scenario.ID, Scenario> loaded = Map.of(other.getId(), other);

		List<Scenario> result = LarvaRunner.selectByPropertiesPath(loaded, "/root/OrdersIn/unregistered.properties");

		assertTrue(result.isEmpty());
	}

	@Test
	void looksUpByScenarioIdEqualityNotObjectIdentity() {
		// The map key built from the scenario's own File and the key built from the raw
		// execute-path string are different Scenario.ID instances; the lookup must go through
		// Scenario.ID#equals (both derived from File#getAbsolutePath()), not object identity.
		File file = new File("/root/OrdersIn/scenario01.properties");
		Scenario scenario = scenario(file);
		Map<Scenario.ID, Scenario> loaded = Map.of(new Scenario.ID(file), scenario);

		List<Scenario> result = LarvaRunner.selectByPropertiesPath(loaded, new File(file.getAbsolutePath()).getAbsolutePath());

		assertEquals(List.of(scenario), result);
	}
}
