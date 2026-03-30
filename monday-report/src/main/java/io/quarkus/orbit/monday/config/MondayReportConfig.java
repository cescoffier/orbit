/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.quarkus.orbit.monday.config;

import io.smallrye.config.ConfigMapping;
import io.smallrye.config.WithDefault;
import io.smallrye.config.WithName;

import java.util.List;

@ConfigMapping(prefix = "monday-report")
public interface MondayReportConfig {

    @WithName("github-token")
    String githubToken();

    List<String> repositories();

    @WithName("output-dir")
    @WithDefault("reports")
    String outputDir();

    @WithName("heatmap-output-dir")
    @WithDefault("reports/heatmap")
    String heatmapOutputDir();
}
