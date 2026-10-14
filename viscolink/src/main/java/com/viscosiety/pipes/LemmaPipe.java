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
package com.viscosiety.pipes;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import com.lemmabase.lemma.Engine;
import com.lemmabase.lemma.EvaluateRequest;
import com.lemmabase.lemma.LemmaException;
import com.lemmabase.lemma.ResourceLimits;
import com.lemmabase.lemma.Response;
import com.lemmabase.lemma.Show;
import org.frankframework.configuration.ConfigurationException;
import org.frankframework.core.ParameterException;
import org.frankframework.core.PipeForward;
import org.frankframework.core.PipeLineSession;
import org.frankframework.core.PipeRunException;
import org.frankframework.core.PipeRunResult;
import org.frankframework.doc.Forward;
import org.frankframework.parameters.IParameter;
import org.frankframework.parameters.ParameterValue;
import org.frankframework.parameters.ParameterValueList;
import org.frankframework.pipes.FixedForwardPipe;
import org.frankframework.stream.Message;
import org.frankframework.util.ClassLoaderUtils;

/**
 * Evaluates business rules written in <a href="https://lemma.run">Lemma</a>. The rules files live in
 * the configuration (paths from the configuration root, like {@code styleSheetName}); every
 * {@code <Param>} supplies one {@code data} input of the spec, by name.
 *
 * <pre>{@code
 * <Pipe name="DecideAdvice" className="com.viscosiety.pipes.LemmaPipe"
 *       rulesFile="Common/BuildCocktailAdvice/rules/cocktail_advice.lemma"
 *       spec="cocktail_advice" rules="ingredient,drink_profile" resultSessionKeyPrefix="advice.">
 *   <Param name="temperature" sessionKey="WeatherData" xpathExpression="/weatherData/temperature"/>
 *   <Param name="weather_group" sessionKey="WeatherData" xpathExpression="/weatherData/weatherMain"/>
 *   <Forward name="success" path="Next"/>
 *   <Forward name="veto" path="ERROR"/>
 * </Pipe>
 * }</pre>
 *
 * <p>Works offline: LemmaBase imports ({@code uses @owner/repo ...}) are refused; the embedded
 * standard library ({@code uses lemma units}) is fine. A spec that does not load fails the
 * configuration with each error as {@code file:line:column message}.</p>
 *
 * @ff.parameter * each parameter is a {@code data} input of the spec, matched by name; a bare number for a measure with one unit gets that unit
 */
@Forward(name = "veto", description = "at least one returned rule has no answer for this input (a Lemma veto); without this forward the pipe throws")
public class LemmaPipe extends FixedForwardPipe {

    public static final String VETO_FORWARD_NAME = "veto";

    public enum OutputType { XML, JSON }

    /** Generous for business rules, small enough that a mistaken file cannot exhaust the JVM. */
    private static final long MAX_SOURCE_BYTES = 256 * 1024;

    private String rulesFile;
    private String spec;
    private String rules;
    private String resultSessionKeyPrefix;
    private OutputType outputType = OutputType.XML;
    private boolean explain = false;
    private String effectiveSessionKey;

    private Engine engine;
    private List<String> ruleNames;
    private Map<String, String> singleUnits;
    private PipeForward vetoForward;

    @Override
    public void configure() throws ConfigurationException {
        super.configure();
        if (rulesFile == null || rulesFile.isBlank()) {
            throw new ConfigurationException("attribute [rulesFile] is required");
        }
        if (spec == null || spec.isBlank()) {
            throw new ConfigurationException("attribute [spec] is required");
        }

        Map<String, String> sources = new LinkedHashMap<>();
        for (String path : split(rulesFile)) {
            String source = read(path);
            var registry = LemmaInputs.registryImport(source);
            if (registry.isPresent()) {
                throw new ConfigurationException("rules file [" + path + "]: LemmaBase imports are not supported ("
                        + registry.get() + "); write the rule in the configuration");
            }
            sources.put(path, source);
        }

        Engine loaded;
        try {
            loaded = Engine.create(ResourceLimits.builder().maxSourceSizeBytes(MAX_SOURCE_BYTES));
        } catch (LinkageError e) {
            throw new ConfigurationException("cannot load the Lemma native library: " + e + "; check LEMMA_JNI_LIBRARY");
        }
        Show show;
        try {
            loaded.load(sources);
            show = loaded.show(null, spec, null);
        } catch (LemmaException e) {
            loaded.close();
            String detail = e.errors().isEmpty() ? e.getMessage() : LemmaInputs.describe(e.errors());
            throw new ConfigurationException("Lemma rules in [" + rulesFile + "] for spec [" + spec + "]: " + detail);
        }

        ruleNames = (rules == null || rules.isBlank()) ? List.copyOf(show.rules().keySet()) : split(rules);
        for (String rule : ruleNames) {
            if (!show.rules().containsKey(rule)) {
                loaded.close();
                throw new ConfigurationException("rule [" + rule + "] is not in spec [" + spec + "]; it has " + show.rules().keySet());
            }
        }
        for (IParameter parameter : getParameterList()) {
            if (!show.data().containsKey(parameter.getName())) {
                loaded.close();
                throw new ConfigurationException("parameter [" + parameter.getName() + "] is not a data input of spec ["
                        + spec + "]; its inputs are " + show.data().keySet());
            }
        }
        singleUnits = LemmaInputs.singleUnits(show);
        vetoForward = findForward(VETO_FORWARD_NAME);
        engine = loaded;
    }

    @Override
    public PipeRunResult doPipe(Message message, PipeLineSession session) throws PipeRunException {
        Map<String, String> values = new LinkedHashMap<>();
        try {
            ParameterValueList pvl = getParameterList().getValues(message, session);
            for (ParameterValue value : pvl) {
                values.put(value.getName(), value.asStringValue());
            }
        } catch (ParameterException e) {
            throw new PipeRunException(this, "unable to resolve parameters", e);
        }

        Response response;
        try {
            EvaluateRequest request = EvaluateRequest.of(spec)
                    .data(LemmaInputs.toData(values, singleUnits))
                    .rules(ruleNames)
                    .explain(explain);
            if (effectiveSessionKey != null) {
                String effective = session.getString(effectiveSessionKey);
                if (effective != null && !effective.isBlank()) {
                    request = request.effective(effective.strip());
                }
            }
            response = engine.evaluate(request);
        } catch (LemmaException e) {
            String detail = e.errors().isEmpty() ? e.getMessage() : LemmaInputs.describe(e.errors());
            throw new PipeRunException(this, "Lemma could not evaluate spec [" + spec + "]: " + detail, e);
        } catch (RuntimeException e) {
            throw new PipeRunException(this, "Lemma could not evaluate spec [" + spec + "]: " + e, e);
        }

        List<String> missing = LemmaResults.missingData(response.results());
        if (!missing.isEmpty()) {
            throw new PipeRunException(this, "spec [" + spec + "] has missing data: " + String.join(", ", missing));
        }
        List<String> unsupported = LemmaResults.unsupported(response.results());
        if (!unsupported.isEmpty()) {
            throw new PipeRunException(this, "rules " + unsupported + " return a spec instance; LemmaPipe returns values only");
        }

        if (resultSessionKeyPrefix != null) {
            response.results().forEach((rule, result) ->
                    LemmaResults.valueText(result).ifPresent(text -> session.put(resultSessionKeyPrefix + rule, text)));
        }

        String output = outputType == OutputType.JSON
                ? LemmaResults.json(response, explain)
                : LemmaResults.xml(response, explain);

        Map<String, String> vetoes = LemmaResults.vetoes(response.results());
        if (!vetoes.isEmpty()) {
            if (vetoForward == null) {
                throw new PipeRunException(this, "rules without an answer: " + vetoes.entrySet().stream()
                        .map(v -> v.getKey() + ": " + v.getValue()).collect(Collectors.joining("; ")));
            }
            return new PipeRunResult(vetoForward, output);
        }
        return new PipeRunResult(getSuccessForward(), output);
    }

    private String read(String path) throws ConfigurationException {
        URL url = ClassLoaderUtils.getResourceURL(this, path);
        if (url == null) {
            throw new ConfigurationException("cannot find rules file [" + path + "]");
        }
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ConfigurationException("cannot read rules file [" + path + "]", e);
        }
    }

    private static List<String> split(String list) {
        return Arrays.stream(list.split(",")).map(String::strip).filter(s -> !s.isEmpty()).toList();
    }

    /** One or more rules files, comma-separated, written from the configuration root. All are loaded together, so their specs may {@code uses} each other. */
    public void setRulesFile(String rulesFile) {
        this.rulesFile = rulesFile;
    }

    /** The spec to evaluate. */
    public void setSpec(String spec) {
        this.spec = spec;
    }

    /** Comma-separated rule names to return; empty returns every rule of the spec. */
    public void setRules(String rules) {
        this.rules = rules;
    }

    /** When set, each returned rule's value is also stored in session key {@code <prefix><rule>}. */
    public void setResultSessionKeyPrefix(String resultSessionKeyPrefix) {
        this.resultSessionKeyPrefix = resultSessionKeyPrefix;
    }

    /** {@code XML} (default) or {@code JSON}. */
    public void setOutputType(OutputType outputType) {
        this.outputType = outputType;
    }

    /**
     * Adds each rule's explanation to the output (and so to the Ladybug trace). With explain=true each
     * {@code <result>} holds the value as text followed by an {@code <explanation>} element, so select the
     * value with {@code result[@rule='x']/text()}, not {@code string(result[@rule='x'])}.
     */
    public void setExplain(boolean explain) {
        this.explain = explain;
    }

    /** Session key with an ISO date-time to evaluate temporal specs at; default now. */
    public void setEffectiveSessionKey(String effectiveSessionKey) {
        this.effectiveSessionKey = effectiveSessionKey;
    }
}
